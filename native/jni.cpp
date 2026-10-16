#include <jni.h>
#include "aicore.h"
static std::string js(JNIEnv* e, jstring s) { if (!s) return ""; const char* c = e->GetStringUTFChars(s, nullptr); std::string r(c); e->ReleaseStringUTFChars(s, c); return r; }
// Java's modified UTF-8 can't take raw 4-byte sequences; build via byte[] -> String(UTF-8)
static jstring mk(JNIEnv* e, const std::string& s) {
    jbyteArray a = e->NewByteArray((jsize)s.size()); e->SetByteArrayRegion(a, 0, (jsize)s.size(), (const jbyte*)s.data());
    jclass sc = e->FindClass("java/lang/String"); jmethodID ctor = e->GetMethodID(sc, "<init>", "([BLjava/lang/String;)V");
    jstring enc = e->NewStringUTF("UTF-8"); jstring r = (jstring)e->NewObject(sc, ctor, a, enc);
    e->DeleteLocalRef(a); e->DeleteLocalRef(enc); return r;
}
extern "C" {
JNIEXPORT jboolean JNICALL Java_com_jason_aikeyboard_ai_LlamaBridge_nativeLoad(JNIEnv* e, jclass, jstring path, jlong off, jint th, jint nctx) {
    return aik::load(js(e, path).c_str(), (long)off, th, nctx); }
JNIEXPORT void JNICALL Java_com_jason_aikeyboard_ai_LlamaBridge_nativeCancel(JNIEnv*, jclass) { aik::cancel(); }
JNIEXPORT void JNICALL Java_com_jason_aikeyboard_ai_LlamaBridge_nativeUnload(JNIEnv*, jclass) { aik::unload(); }
JNIEXPORT jobjectArray JNICALL Java_com_jason_aikeyboard_ai_LlamaBridge_nativePredict(JNIEnv* e, jclass, jstring ctx, jstring prefix, jint k) {
    auto v = aik::predict(js(e, ctx), js(e, prefix), k);
    jobjectArray a = e->NewObjectArray((jsize)v.size(), e->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < v.size(); i++) { jstring s = mk(e, v[i]); e->SetObjectArrayElement(a, (jsize)i, s); e->DeleteLocalRef(s); }
    return a; }
JNIEXPORT jstring JNICALL Java_com_jason_aikeyboard_ai_LlamaBridge_nativeGenerate(JNIEnv* e, jclass, jstring sys, jstring user, jint mt) {
    return mk(e, aik::generate(js(e, sys), js(e, user), mt)); }
}
