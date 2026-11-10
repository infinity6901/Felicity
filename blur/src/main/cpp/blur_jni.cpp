#include "gaussian_blur.h"

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <vector>

#define TAG  "GPUBlur"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * JNI entry point called from GPUBlur.kt.
 *
 * Locks the source bitmap, runs the GPU blur pipeline, and returns a new
 * Bitmap containing the blurred pixels. If the pipeline fails for any reason
 * (no EGL, out of GPU memory, etc.) the original bitmap is returned unchanged
 * so the caller always gets a valid result.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_app_simple_felicity_blur_GPUBlur_nativeBlur(
        JNIEnv *env,
        jobject  /* thiz */,
        jobject bitmap,
        jfloat radius) {

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("AndroidBitmap_getInfo failed");
        return bitmap;
    }

    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        LOGE("Unsupported bitmap format %d or empty dimensions", info.format);
        return bitmap;
    }

    void *srcPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &srcPixels) < 0) {
        LOGE("AndroidBitmap_lockPixels failed");
        return bitmap;
    }

    const size_t rowBytes = static_cast<size_t>(info.width) * 4;
    const uint8_t *tightlyPackedSrc = static_cast<const uint8_t *>(srcPixels);
    std::vector<uint8_t> packedBuffer;

    if (info.stride != rowBytes) {
        packedBuffer.resize(rowBytes * info.height);
        const auto *srcRow = static_cast<const uint8_t *>(srcPixels);
        for (uint32_t y = 0; y < info.height; ++y) {
            std::memcpy(packedBuffer.data() + y * rowBytes, srcRow + y * info.stride, rowBytes);
        }
        tightlyPackedSrc = packedBuffer.data();
    }

    uint8_t *blurred = gaussianBlurGpu(
            tightlyPackedSrc,
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<float>(radius));

    AndroidBitmap_unlockPixels(env, bitmap);

    if (!blurred) {
        LOGE("GPU blur returned null — returning original bitmap");
        return bitmap;
    }

    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Id = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Id);
    jmethodID createId = env->GetStaticMethodID(bitmapClass, "createBitmap",
                                                "(IILandroid/graphics/Bitmap$Config;)"
                                                "Landroid/graphics/Bitmap;");

    jobject resultBitmap = env->CallStaticObjectMethod(
            bitmapClass, createId,
            static_cast<jint>(info.width),
            static_cast<jint>(info.height),
            argb8888);

    if (!resultBitmap) {
        LOGE("Bitmap.createBitmap returned null");
        delete[] blurred;
        return bitmap;
    }

    AndroidBitmapInfo dstInfo{};
    AndroidBitmap_getInfo(env, resultBitmap, &dstInfo);

    void *dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, resultBitmap, &dstPixels) < 0) {
        LOGE("AndroidBitmap_lockPixels on result bitmap failed");
        delete[] blurred;
        return bitmap;
    }

    if (dstInfo.stride == rowBytes || dstInfo.stride == 0) {
        std::memcpy(dstPixels, blurred, rowBytes * info.height);
    } else {
        auto *dstRow = static_cast<uint8_t *>(dstPixels);
        for (uint32_t y = 0; y < info.height; ++y) {
            std::memcpy(dstRow + y * dstInfo.stride, blurred + y * rowBytes, rowBytes);
        }
    }

    AndroidBitmap_unlockPixels(env, resultBitmap);
    delete[] blurred;

    return resultBitmap;
}
