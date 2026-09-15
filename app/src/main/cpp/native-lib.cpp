#include <jni.h>
#include <string>

// P1 stub + P7/P10 JNI seams. whisper.cpp / llama.cpp are NOT vendored
// yet (see docs/MODEL_LICENSES.md P7.1): these symbols report unavailable
// so the Kotlin bridges map cleanly to MODEL_MISSING instead of crashing.
// When the reviewed revisions land, whisper_adapter.cpp / llama_adapter.cpp
// implement the real entry points behind the same signatures.
extern "C" JNIEXPORT jstring JNICALL
Java_com_scraper_classroomcapture_NativeStub_nativeVersion(JNIEnv* env, jclass) {
  std::string v = "scraper-native-p7-stub";
  return env->NewStringUTF(v.c_str());
}

// WhisperBridge seam (P7.2). False until whisper.cpp is vendored.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_scraper_classroomcapture_asr_JniWhisperBridge_nativeIsWhisperAvailable(JNIEnv* env, jobject) {
  return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_scraper_classroomcapture_asr_JniWhisperBridge_nativeLoadModel(JNIEnv* env, jobject, jstring) {
  return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_scraper_classroomcapture_asr_JniWhisperBridge_nativeUnload(JNIEnv* env, jobject) {}

// LlamaBridge seam (P10.2). False until llama.cpp is vendored.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_scraper_classroomcapture_llm_JniLlamaBridge_nativeIsLlamaAvailable(JNIEnv* env, jobject) {
  return JNI_FALSE;
}
