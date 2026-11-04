#include <jni.h>
#include <string.h>

/* Fotogramma RGBA nella memoria del motore delle telecamere: si copia in un colpo solo dentro il buffer della bitmap. */
JNIEXPORT void JNICALL Java_it_ponte_CamMem_copy(JNIEnv *env, jclass cls, jlong addr, jobject dst, jint len) {
    void *d = (*env)->GetDirectBufferAddress(env, dst);
    if (d && addr) memcpy(d, (const void *)(intptr_t)addr, (size_t)len);
}
