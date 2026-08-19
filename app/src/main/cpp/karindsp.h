/*
 * karindsp.h
 * API de convolución FIR por bloques con FFT (overlap-save) acelerada por NEON.
 * Replica exactamente la lógica del Convolver.kt de KarinFLiX.
 */
#ifndef KARINDSP_H
#define KARINDSP_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct KarinConv KarinConv;

KarinConv* karin_conv_new(int blockSize);
void karin_conv_free(KarinConv* c);
void karin_conv_set_ir(KarinConv* c, const float* ir, int len);
void karin_conv_reset(KarinConv* c);
/* window: fftSize (= 2*blockSize) muestras listas para FFT. out: blockSize muestras. */
void karin_conv_render(KarinConv* c, const float* window, float* out);

#ifdef __cplusplus
}
#endif

#endif /* KARINDSP_H */
