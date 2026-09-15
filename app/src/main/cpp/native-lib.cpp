#include <jni.h>
#include <string>

// P1 stub. Real whisper/llama JNI adapters land in P7/P10 behind
// Kotlin interfaces (ASREngine / LocalLLMEngine). Never called from UI yet.
extern "C" JNIEXPORT jstring JNICALL
Java_com_scraper_classroomcapture_NativeStub_nativeVersion(JNIEnv* env, jclass) {
  std::string v = "scraper-native-p1-stub";
  return env->NewStringUTF(v.c_str());
}
