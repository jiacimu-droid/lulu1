#include <jni.h>
#include <cstdlib>
#include <cstring>
#include "Live2DCubismCore.h"

// This native compatibility probe intentionally does not draw or claim a first
// GPU frame. Only an official SDK package may supply the proprietary Core.
extern "C" JNIEXPORT jint JNICALL
Java_com_jiacimu_lulu_NativeCubismProbe_nativeDrawableCount(
    JNIEnv* env, jobject, jbyteArray input) {
    if (!input) return -1;
    const jsize bytes = env->GetArrayLength(input);
    if (bytes <= 0 || bytes > 100 * 1024 * 1024) return -1;
    void* mocData = nullptr;
    if (posix_memalign(&mocData, csmAlignofMoc, static_cast<size_t>(bytes)) != 0) return -2;
    env->GetByteArrayRegion(input, 0, bytes, reinterpret_cast<jbyte*>(mocData));
    if (env->ExceptionCheck()) { free(mocData); return -3; }
    const auto* moc = csmReviveMocInPlace(mocData, static_cast<csmUint32>(bytes));
    if (!moc) { free(mocData); return -4; }
    const auto modelBytes = csmGetSizeofModel(moc);
    void* modelData = nullptr;
    if (modelBytes == 0 || posix_memalign(&modelData, csmAlignofModel,
                                          static_cast<size_t>(modelBytes)) != 0) {
        free(mocData);
        return -5;
    }
    const auto* model = csmInitializeModelInPlace(moc, modelData, modelBytes);
    const jint drawables = model ? static_cast<jint>(csmGetDrawableCount(model)) : -6;
    free(modelData);
    free(mocData);
    return drawables;
}
