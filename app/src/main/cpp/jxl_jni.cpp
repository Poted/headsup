// JNI side of app.headsup.media.NativeJxl.
#include <jni.h>

#include <cstdlib>
#include <string>
#include <vector>

#include "jxl_decode.h"

namespace {

void Throw(JNIEnv* env, const std::string& message) {
  jclass type = env->FindClass("java/lang/IllegalStateException");
  if (type) env->ThrowNew(type, message.c_str());
}

JxlPicture* Of(jlong handle) { return reinterpret_cast<JxlPicture*>(handle); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_app_headsup_media_NativeJxl_decodeNative(JNIEnv* env, jobject, jbyteArray input) {
  const jsize size = env->GetArrayLength(input);
  std::vector<uint8_t> bytes;
  try {
    bytes.resize(static_cast<size_t>(size));
  } catch (...) {
    Throw(env, "Not enough memory to develop this file");
    return 0;
  }
  env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
  auto* picture = new JxlPicture();
  std::string error;
  if (!DecodeJxlLinear(bytes.data(), bytes.size(), picture, &error)) {
    free(picture->pixels);
    delete picture;
    Throw(env, error);
    return 0;
  }
  return reinterpret_cast<jlong>(picture);
}

extern "C" JNIEXPORT jintArray JNICALL Java_app_headsup_media_NativeJxl_size(JNIEnv* env, jobject, jlong handle) {
  jintArray size = env->NewIntArray(2);
  const jint values[2] = {static_cast<jint>(Of(handle)->width), static_cast<jint>(Of(handle)->height)};
  env->SetIntArrayRegion(size, 0, 2, values);
  return size;
}

extern "C" JNIEXPORT jobject JNICALL Java_app_headsup_media_NativeJxl_pixels(JNIEnv* env, jobject, jlong handle) {
  return env->NewDirectByteBuffer(Of(handle)->pixels, static_cast<jlong>(Of(handle)->bytes));
}

extern "C" JNIEXPORT void JNICALL Java_app_headsup_media_NativeJxl_release(JNIEnv*, jobject, jlong handle) {
  if (!handle) return;
  free(Of(handle)->pixels);
  delete Of(handle);
}
