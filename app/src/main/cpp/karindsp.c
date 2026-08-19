/*
 * karindsp.c
 * Convolución FIR particionada (uniform partitioned overlap-save) + FFT radix-2
 * con aceleración NEON. Es el equivalente nativo del Convolver.kt de KarinFLiX:
 *
 *  - FFT de tamaño fftSize = 2*blockSize (1024). El IR se divide en particiones
 *    de blockSize muestras; cada partición se FFT'ea una vez al cargarla.
 *  - Por bloque de entrada: 1 FFT, acumulación espectral (partición * historial
 *    de espectros en anillo) y 1 IFFT.
 *  - El camino seco lo alinea AudioEnhanceProcessor con latencySamples() = blockSize.
 */

#include "karindsp.h"

#include <stdlib.h>
#include <string.h>
#include <math.h>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define KARINDSP_NEON 1
#else
#define KARINDSP_NEON 0
#endif

#define KARIN_MAX_FFT 1024

struct KarinConv {
    int blockSize;
    int fftSize;
    int numPartitions;
    int ringSize;
    int writePos;

    /* Twiddle table fija de 1025 entradas (igual que MAX_FFT_SIZE+1 en Kotlin). */
    float twRe[KARIN_MAX_FFT + 1];
    float twIm[KARIN_MAX_FFT + 1];

    float** irRe;
    float** irIm;
    float** ringRe;
    float** ringIm;

    float* fr;
    float* fi;
    float* tr;
    float* ti;
};

static void karin_fft(float* re, float* im, const float* twRe, const float* twIm, int n, int forward) {
    /* Permutación bit-reversal (escalar, O(n)). */
    int j = 0;
    for (int i = 1; i < n; i++) {
        int bit = n >> 1;
        while (j & bit) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            float t = re[i]; re[i] = re[j]; re[j] = t;
            t = im[i]; im[i] = im[j]; im[j] = t;
        }
    }

    const float sign = forward ? -1.0f : 1.0f;
    int len = 2;
    while (len <= n) {
        const int half = len >> 1;
        const int step = KARIN_MAX_FFT / len;
#if KARINDSP_NEON
        if (half >= 4) {
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k += 4) {
                    const float32x4_t wr = {
                        twRe[(k + 0) * step], twRe[(k + 1) * step],
                        twRe[(k + 2) * step], twRe[(k + 3) * step]
                    };
                    const float32x4_t wi = {
                        sign * twIm[(k + 0) * step], sign * twIm[(k + 1) * step],
                        sign * twIm[(k + 2) * step], sign * twIm[(k + 3) * step]
                    };
                    const float32x4_t ur = vld1q_f32(re + i + k);
                    const float32x4_t ui = vld1q_f32(im + i + k);
                    const float32x4_t vr = vld1q_f32(re + i + k + half);
                    const float32x4_t vi = vld1q_f32(im + i + k + half);

                    /* Mariposa: tr = vr*wr - vi*wi ; ti = vr*wi + vi*wr */
                    const float32x4_t tr = vsubq_f32(vmulq_f32(vr, wr), vmulq_f32(vi, wi));
                    const float32x4_t ti = vaddq_f32(vmulq_f32(vr, wi), vmulq_f32(vi, wr));

                    vst1q_f32(re + i + k, vaddq_f32(ur, tr));
                    vst1q_f32(im + i + k, vaddq_f32(ui, ti));
                    vst1q_f32(re + i + k + half, vsubq_f32(ur, tr));
                    vst1q_f32(im + i + k + half, vsubq_f32(ui, ti));
                }
            }
        } else
#endif
        {
            for (int i = 0; i < n; i += len) {
                for (int k = 0; k < half; k++) {
                    const int idx = k * step;
                    const float wr = twRe[idx];
                    const float wi = sign * twIm[idx];
                    const float ur = re[i + k];
                    const float ui = im[i + k];
                    const float vr = re[i + k + half];
                    const float vi = im[i + k + half];
                    const float tr = vr * wr - vi * wi;
                    const float ti = vr * wi + vi * wr;
                    re[i + k] = ur + tr;
                    im[i + k] = ui + ti;
                    re[i + k + half] = ur - tr;
                    im[i + k + half] = ui - ti;
                }
            }
        }
        len <<= 1;
    }

    if (!forward) {
        const float inv = 1.0f / (float)n;
        for (int i = 0; i < n; i++) {
            re[i] *= inv;
            im[i] *= inv;
        }
    }
}

/* tr += xr*hr - xi*hi ; ti += xr*hi + xi*hr (producto complejo acumulado). */
static void karin_accumulate(KarinConv* c, const float* xr, const float* xi,
                             const float* hr, const float* hi) {
    const int fft = c->fftSize;
    float* tr = c->tr;
    float* ti = c->ti;
#if KARINDSP_NEON
    for (int b = 0; b < fft; b += 4) {
        const float32x4_t xrv = vld1q_f32(xr + b);
        const float32x4_t xiv = vld1q_f32(xi + b);
        const float32x4_t hrv = vld1q_f32(hr + b);
        const float32x4_t hiv = vld1q_f32(hi + b);
        const float32x4_t a = vmulq_f32(xrv, hrv);
        const float32x4_t b2 = vmulq_f32(xiv, hiv);
        const float32x4_t c2 = vmulq_f32(xrv, hiv);
        const float32x4_t d2 = vmulq_f32(xiv, hrv);
        float32x4_t trv = vld1q_f32(tr + b);
        float32x4_t tiv = vld1q_f32(ti + b);
        trv = vaddq_f32(trv, vsubq_f32(a, b2));
        tiv = vaddq_f32(tiv, vaddq_f32(c2, d2));
        vst1q_f32(tr + b, trv);
        vst1q_f32(ti + b, tiv);
    }
#else
    for (int b = 0; b < fft; b++) {
        tr[b] += xr[b] * hr[b] - xi[b] * hi[b];
        ti[b] += xr[b] * hi[b] + xi[b] * hr[b];
    }
#endif
}

KarinConv* karin_conv_new(int blockSize) {
    if (blockSize < 16 || blockSize > 512 || (blockSize & (blockSize - 1)) != 0) return NULL;

    KarinConv* c = calloc(1, sizeof(KarinConv));
    if (!c) return NULL;

    c->blockSize = blockSize;
    c->fftSize = blockSize * 2;

    for (int k = 0; k <= KARIN_MAX_FFT; k++) {
        const double ang = 2.0 * 3.14159265358979323846 * (double)k / (double)KARIN_MAX_FFT;
        c->twRe[k] = (float)cos(ang);
        c->twIm[k] = (float)sin(ang);
    }

    c->fr = (float*)malloc(sizeof(float) * (size_t)c->fftSize);
    c->fi = (float*)malloc(sizeof(float) * (size_t)c->fftSize);
    c->tr = (float*)malloc(sizeof(float) * (size_t)c->fftSize);
    c->ti = (float*)malloc(sizeof(float) * (size_t)c->fftSize);
    if (!c->fr || !c->fi || !c->tr || !c->ti) {
        karin_conv_free(c);
        return NULL;
    }
    return c;
}

void karin_conv_free(KarinConv* c) {
    if (!c) return;
    for (int i = 0; i < c->numPartitions; i++) {
        free(c->irRe[i]);
        free(c->irIm[i]);
    }
    free(c->irRe);
    free(c->irIm);
    for (int i = 0; i < c->ringSize; i++) {
        free(c->ringRe[i]);
        free(c->ringIm[i]);
    }
    free(c->ringRe);
    free(c->ringIm);
    free(c->fr);
    free(c->fi);
    free(c->tr);
    free(c->ti);
    free(c);
}

void karin_conv_set_ir(KarinConv* c, const float* ir, int len) {
    if (!c) return;

    for (int i = 0; i < c->numPartitions; i++) {
        free(c->irRe[i]);
        free(c->irIm[i]);
    }
    free(c->irRe);
    free(c->irIm);
    c->irRe = NULL;
    c->irIm = NULL;
    for (int i = 0; i < c->ringSize; i++) {
        free(c->ringRe[i]);
        free(c->ringIm[i]);
    }
    free(c->ringRe);
    free(c->ringIm);
    c->ringRe = NULL;
    c->ringIm = NULL;
    c->numPartitions = 0;
    c->ringSize = 0;
    c->writePos = 0;

    if (!ir || len <= 0) return;

    const int bs = c->blockSize;
    c->numPartitions = (len + bs - 1) / bs;
    c->irRe = (float**)calloc((size_t)c->numPartitions, sizeof(float*));
    c->irIm = (float**)calloc((size_t)c->numPartitions, sizeof(float*));
    if (!c->irRe || !c->irIm) return;

    for (int k = 0; k < c->numPartitions; k++) {
        c->irRe[k] = (float*)calloc((size_t)c->fftSize, sizeof(float));
        c->irIm[k] = (float*)calloc((size_t)c->fftSize, sizeof(float));
        if (!c->irRe[k] || !c->irIm[k]) return;
        const int off = k * bs;
        const int n = (len - off) < bs ? (len - off) : bs;
        if (n > 0) memcpy(c->irRe[k], ir + off, sizeof(float) * (size_t)n);
        karin_fft(c->irRe[k], c->irIm[k], c->twRe, c->twIm, c->fftSize, 1);
    }

    c->ringSize = c->numPartitions + 1;
    c->ringRe = (float**)calloc((size_t)c->ringSize, sizeof(float*));
    c->ringIm = (float**)calloc((size_t)c->ringSize, sizeof(float*));
    if (!c->ringRe || !c->ringIm) return;
    for (int i = 0; i < c->ringSize; i++) {
        c->ringRe[i] = (float*)calloc((size_t)c->fftSize, sizeof(float));
        c->ringIm[i] = (float*)calloc((size_t)c->fftSize, sizeof(float));
        if (!c->ringRe[i] || !c->ringIm[i]) return;
    }
}

void karin_conv_reset(KarinConv* c) {
    if (!c) return;
    c->writePos = 0;
    for (int i = 0; i < c->ringSize; i++) {
        if (c->ringRe[i]) memset(c->ringRe[i], 0, sizeof(float) * (size_t)c->fftSize);
        if (c->ringIm[i]) memset(c->ringIm[i], 0, sizeof(float) * (size_t)c->fftSize);
    }
}

void karin_conv_render(KarinConv* c, const float* window, float* out) {
    if (!c || !window || !out) return;
    const int fft = c->fftSize;
    const int bs = c->blockSize;
    if (c->numPartitions == 0 || !c->irRe || !c->ringRe) {
        memset(out, 0, sizeof(float) * (size_t)bs);
        return;
    }

    memcpy(c->fr, window, sizeof(float) * (size_t)fft);
    memset(c->fi, 0, sizeof(float) * (size_t)fft);
    karin_fft(c->fr, c->fi, c->twRe, c->twIm, fft, 1);

    memcpy(c->ringRe[c->writePos], c->fr, sizeof(float) * (size_t)fft);
    memcpy(c->ringIm[c->writePos], c->fi, sizeof(float) * (size_t)fft);

    memset(c->tr, 0, sizeof(float) * (size_t)fft);
    memset(c->ti, 0, sizeof(float) * (size_t)fft);

    int idx = c->writePos;
    for (int k = 0; k < c->numPartitions; k++) {
        karin_accumulate(c, c->ringRe[idx], c->ringIm[idx], c->irRe[k], c->irIm[k]);
        idx--;
        if (idx < 0) idx = c->ringSize - 1;
    }

    c->writePos++;
    if (c->writePos >= c->ringSize) c->writePos = 0;

    karin_fft(c->tr, c->ti, c->twRe, c->twIm, fft, 0);
    memcpy(out, c->tr + bs, sizeof(float) * (size_t)bs);
}
