// libaikvoice.so: whisper.cpp speech-to-text, fully on-device. Own static ggml copy, all non-JNI symbols hidden.
#include "whisper.h"
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <thread>
#include <algorithm>

static std::mutex g_mu;
static whisper_context *g_ctx = nullptr;
static int g_threads = 4;
static std::atomic<bool> g_abort{false};

// The model may sit inside the APK (stored zip entry at offset, length bytes). whisper.cpp reads tensors until eof(), so
// eof MUST be reported at the end of the entry, not the end of the APK (v7 bug: it read the zip central directory as tensors).
struct FileLoader { FILE *f; long long remaining; bool eof; };
static size_t ld_read(void *c, void *out, size_t n) {
    auto *l = (FileLoader *)c;
    if (l->remaining >= 0 && (long long)n > l->remaining) { n = (size_t)l->remaining; l->eof = true; }
    size_t r = n ? fread(out, 1, n, l->f) : 0; if (r < n || feof(l->f)) l->eof = true;
    if (l->remaining >= 0) { l->remaining -= r; }
    return r;
}
static bool ld_eof(void *c) { return ((FileLoader *)c)->eof; }
static void ld_close(void *c) { auto *l = (FileLoader *)c; fclose(l->f); }

static std::string g_err;
extern "C" const char *aikv_error() { return g_err.c_str(); }
extern "C" bool aikv_load(const char *path, long long offset, long long length, int threads) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_ctx) return true;
    FILE *f = fopen(path, "rb"); if (!f) { g_err = std::string("cannot open ") + path; return false; }
    if (offset > 0 && fseeko(f, (off_t)offset, SEEK_SET) != 0) { fclose(f); g_err = "seek failed"; return false; }
    FileLoader fl{f, length > 0 ? length : -1, false};
    whisper_model_loader ld{&fl, ld_read, ld_eof, ld_close};
    whisper_context_params cp = whisper_context_default_params(); cp.use_gpu = false; cp.flash_attn = true;
    g_ctx = whisper_init_with_params(&ld, cp);
    g_threads = threads > 0 ? threads : 4;
    if (!g_ctx) g_err = "whisper_init failed (corrupt or truncated model)";
    return g_ctx != nullptr;
}
extern "C" void aikv_unload() { std::lock_guard<std::mutex> lk(g_mu); if (g_ctx) whisper_free(g_ctx); g_ctx = nullptr; }
static bool abort_cb(void *) { return g_abort.load(); }
extern "C" std::string aikv_transcribe(const float *pcm, int n, const char *prompt, bool fast) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_ctx || n <= 0) return "";
    g_abort = false;
    whisper_full_params p = whisper_full_default_params(fast ? WHISPER_SAMPLING_GREEDY : WHISPER_SAMPLING_BEAM_SEARCH);
    p.n_threads = g_threads; p.language = "en"; p.translate = false; p.no_context = true; p.single_segment = false;
    p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
    p.suppress_blank = true; p.suppress_nst = true; p.no_timestamps = true; p.temperature_inc = fast ? 0.0f : 0.2f;
    p.beam_search.beam_size = 3;
    if (prompt && *prompt) p.initial_prompt = prompt;
    p.abort_callback = abort_cb;
    // partials: shrink encoder window to the clip length (+1 s), ~3-10x faster on short clips; finals use the full 30 s window
    if (fast) { int need = (int)std::max(n, 16000) / 320; p.audio_ctx = std::min(1500, ((need * 3 / 2 + 128) + 63) / 64 * 64); }
    // whisper needs >= 1 s; pad short clips with silence
    std::vector<float> buf(pcm, pcm + n); if (buf.size() < 16000 + 1600) buf.resize(16000 + 1600, 0.f);
    if (whisper_full(g_ctx, p, buf.data(), (int)buf.size()) != 0) return "";
    std::string out; int ns = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < ns; i++) out += whisper_full_get_segment_text(g_ctx, i);
    return out;
}
extern "C" void aikv_abort() { g_abort = true; }

// ---- JNI: com.jason.aikeyboard.ai.WhisperBridge ----
extern "C" JNIEXPORT jboolean JNICALL Java_com_jason_aikeyboard_ai_WhisperBridge_nativeLoad(JNIEnv *e, jclass, jstring path, jlong off, jlong len, jint th) {
    const char *p = e->GetStringUTFChars(path, nullptr); bool ok = aikv_load(p, (long long)off, (long long)len, th); e->ReleaseStringUTFChars(path, p); return ok;
}
extern "C" JNIEXPORT void JNICALL Java_com_jason_aikeyboard_ai_WhisperBridge_nativeUnload(JNIEnv *, jclass) { aikv_unload(); }
extern "C" JNIEXPORT void JNICALL Java_com_jason_aikeyboard_ai_WhisperBridge_nativeAbort(JNIEnv *, jclass) { aikv_abort(); }
extern "C" JNIEXPORT jstring JNICALL Java_com_jason_aikeyboard_ai_WhisperBridge_nativeTranscribe(JNIEnv *e, jclass, jfloatArray a, jint n, jstring prompt, jboolean fast) {
    jfloat *d = e->GetFloatArrayElements(a, nullptr);
    const char *pr = prompt ? e->GetStringUTFChars(prompt, nullptr) : nullptr;
    std::string s = aikv_transcribe(d, std::min<int>(n, e->GetArrayLength(a)), pr, fast);
    if (pr) e->ReleaseStringUTFChars(prompt, pr);
    e->ReleaseFloatArrayElements(a, d, JNI_ABORT);
    return e->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_com_jason_aikeyboard_ai_WhisperBridge_nativeError(JNIEnv *e, jclass) { return e->NewStringUTF(g_err.c_str()); }
extern "C" JNIEXPORT jstring JNICALL Java_com_jason_aikeyboard_ai_WhisperBridge_nativeSystemInfo(JNIEnv *e, jclass) { return e->NewStringUTF(whisper_print_system_info()); }
