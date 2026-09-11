/*
 * karindsp_jni.c
 * Puente JNI para NativeDsp (Kotlin). La librería se carga con
 * System.loadLibrary("karindsp"); los métodos nativos se registran en JNI_OnLoad
 * para no depender del mangling del nombre (robusto ante R8).
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "karindsp.h"

static KarinConv* to_conv(jlong handle) {
    return (KarinConv*)(intptr_t)handle;
}

static jlong nativeCreate(JNIEnv* env, jobject thiz, jint blockSize) {
    KarinConv* c = karin_conv_new((int)blockSize);
    if (!c) return 0L;
    return (jlong)(intptr_t)c;
}

static void nativeSetIr(JNIEnv* env, jobject thiz, jlong handle, jfloatArray ir) {
    KarinConv* c = to_conv(handle);
    if (!c) return;
    jsize len = ir ? (*env)->GetArrayLength(env, ir) : 0;
    const float* p = NULL;
    if (len > 0) {
        p = (*env)->GetFloatArrayElements(env, ir, NULL);
        if (!p) return;
    }
    karin_conv_set_ir(c, p, (int)len);
    if (p) (*env)->ReleaseFloatArrayElements(env, ir, (jfloat*)p, JNI_ABORT);
}

static void nativeRender(JNIEnv* env, jobject thiz, jlong handle, jfloatArray window, jfloatArray out) {
    KarinConv* c = to_conv(handle);
    if (!c) return;
    jfloat* w = (*env)->GetFloatArrayElements(env, window, NULL);
    if (!w) return;
    jfloat* o = (*env)->GetFloatArrayElements(env, out, NULL);
    if (!o) {
        (*env)->ReleaseFloatArrayElements(env, window, w, JNI_ABORT);
        return;
    }
    karin_conv_render(c, w, o);
    (*env)->ReleaseFloatArrayElements(env, out, o, 0);
    (*env)->ReleaseFloatArrayElements(env, window, w, JNI_ABORT);
}

static void nativeReset(JNIEnv* env, jobject thiz, jlong handle) {
    karin_conv_reset(to_conv(handle));
}

static void nativeFree(JNIEnv* env, jobject thiz, jlong handle) {
    karin_conv_free(to_conv(handle));
}

static const JNINativeMethod kMethods[] = {
    { "nativeCreate", "(I)J",     (void*)nativeCreate },
    { "nativeSetIr",  "(J[F)V",   (void*)nativeSetIr },
    { "nativeRender", "(J[F[F)V", (void*)nativeRender },
    { "nativeReset",  "(J)V",     (void*)nativeReset },
    { "nativeFree",   "(J)V",     (void*)nativeFree },
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "com/karin/streamtv/player/dsp/NativeDsp");
    if (!cls) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, kMethods, 5) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
