#ifndef OPENXR_MANAGER_H
#define OPENXR_MANAGER_H

#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

// JNI exports for VRStreamActivity
JNIEXPORT jobject JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeInitVR(
        JNIEnv* env, jobject thiz, jobject activity, jint stream_width, jint stream_height,
        jboolean stereo_conversion_enabled, jfloat stereo_depth_intensity);
JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeStartRenderLoop(JNIEnv* env, jobject thiz, jobject surface);
JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeSetSettingsOverlay(
        JNIEnv* env, jobject thiz, jbyteArray rgba_pixels, jint width, jint height);
JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeSetDepthPipelineReady(
        JNIEnv* env, jobject thiz, jboolean ready);
JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeSetDepthMap(
        JNIEnv* env, jobject thiz, jbyteArray depth_map, jint width, jint height);
JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeStopVR(JNIEnv* env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif // OPENXR_MANAGER_H
