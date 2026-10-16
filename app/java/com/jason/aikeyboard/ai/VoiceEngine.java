package com.jason.aikeyboard.ai;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jason.aikeyboard.core.Vad;
import com.jason.aikeyboard.core.VoiceText;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Voice typing. Engine WHISPER: AudioRecord (any rate, resampled to 16 kHz) -> energy VAD -> whisper.cpp (libaikvoice) on
 * this phone; partial text every ~1 s, accurate final pass on stop; long dictation cut into chunks at pauses (<= 28 s).
 * Engine ANDROID: SpeechRecognizer.createOnDeviceSpeechRecognizer (Android 12+). Whisper failing to load falls back to it.
 * Every step is logged (VoiceLog) so failures are visible. Callbacks arrive on the main thread.
 */
public final class VoiceEngine {
    public interface Listener {
        void onVoiceState(String state);       // "loading", "listening", "transcribing", "idle"
        void onVoiceLevel(float level);         // 0..1
        void onVoicePartial(String text);
        void onVoiceFinal(String text, boolean done);
        void onVoiceError(String message);
        default void onVoiceEngineChanged(String label) {}
    }
    /** Audio source; real one wraps AudioRecord, tests feed WAV files. read() blocks ~one frame, returns <0 at end. */
    public interface PcmSource { int read(short[] buf, int n); void close(); default int rate() { return RATE; } default String describe() { return "test source"; } }
    public enum Engine { WHISPER, ANDROID }
    private static final String TAG = "AIKeyboard";
    public static final String ASSET = "whisper.bin";
    public static final int RATE = 16000;
    private static VoiceEngine instance;
    public static synchronized VoiceEngine get(Context c) { if (instance == null) instance = new VoiceEngine(c.getApplicationContext()); return instance; }
    public static synchronized void resetInstanceForTests() { instance = null; }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService asr = Executors.newSingleThreadExecutor(r -> new Thread(r, "voice-asr"));
    private volatile boolean modelReady, loadFailed; private volatile String loadError = "";
    private final AtomicInteger session = new AtomicInteger();
    private final AtomicBoolean partialBusy = new AtomicBoolean();
    private volatile boolean stopRequested, cancelled, running;
    private Listener listener;
    public final Vad vad = new Vad();
    public volatile long lastFinalMs, lastPartialMs, lastAudioMs, loadMs; public volatile int partialCount;
    public volatile String lastSourceInfo = "", lastFinalText = "", modelInfo = ""; public volatile int lastRate; public volatile double lastPeakDb = -120; public volatile float lastGain = 1;
    public final VoiceLog log;
    /** test hooks */
    public static volatile java.util.function.Supplier<PcmSource> sourceOverride;
    public static volatile String modelPathOverride; public static volatile long modelOffsetOverride, modelLengthOverride = -1;
    public static volatile Boolean androidAvailableOverride;
    private Object androidRec;

    private VoiceEngine(Context c) { app = c; log = new VoiceLog(c); }
    public boolean isRunning() { return running; }
    public boolean modelReady() { return modelReady; }
    public boolean loadFailed() { return loadFailed; }
    public String loadError() { return loadError; }

    static int threads() { int n = Runtime.getRuntime().availableProcessors(); return Math.max(2, Math.min(4, n - 2)); }
    public void preload() { asr.execute(this::loadNow); }
    /** Loads whisper on the ASR thread; idempotent; never throws. */
    boolean loadNow() {
        if (modelReady) return true;
        long t0 = System.currentTimeMillis();
        try {
            if (!WhisperBridge.loadLibrary()) throw new IllegalStateException("voice library not loaded (" + WhisperBridge.libError() + ")");
            log.add("lib loaded; " + safe(WhisperBridge::nativeSystemInfo));
            String path = modelPathOverride; long off = modelOffsetOverride, len = modelLengthOverride;
            if (path == null) {
                AssetFileDescriptor afd = app.getAssets().openFd(ASSET); // stored (uncompressed) entry inside the APK
                off = afd.getStartOffset(); len = afd.getDeclaredLength() > 0 ? afd.getDeclaredLength() : afd.getLength(); afd.close();
                path = app.getApplicationInfo().sourceDir;
            }
            modelInfo = "model " + path + " @" + off + " len " + len;
            log.add(modelInfo + ", threads " + threads());
            if (!WhisperBridge.nativeLoad(path, off, len, threads())) throw new IllegalStateException("speech model failed to load: " + safe(WhisperBridge::nativeError));
            modelReady = true; loadFailed = false; loadMs = System.currentTimeMillis() - t0; log.add("whisper ready in " + loadMs + " ms");
        } catch (Throwable t) {
            loadFailed = true; loadError = t.getClass().getSimpleName().equals("IllegalStateException") ? String.valueOf(t.getMessage()) : t.toString();
            log.add("LOAD FAILED: " + loadError); Log.e(TAG, "whisper load failed", t);
        }
        return modelReady;
    }
    private static String safe(java.util.function.Supplier<String> s) { try { return s.get(); } catch (Throwable t) { return t.toString(); } }
    public static boolean androidAvailable(Context c) {
        Boolean o = androidAvailableOverride; if (o != null) return o;
        try { return android.os.Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(c); } catch (Throwable t) { return false; }
    }

    public void start(Engine engine, Listener l) {
        cancelNow(); listener = l; stopRequested = false; cancelled = false; running = true; partialCount = 0; lastFinalText = "";
        int sid = session.incrementAndGet();
        log.add("---- session " + sid + " engine " + engine);
        if (engine == Engine.WHISPER && loadFailed && androidAvailable(app)) { // known-bad whisper: go straight to Android
            if (switchToAndroid(sid, "Local Whisper failed (" + loadError + ")")) return;
        }
        if (engine == Engine.ANDROID && sourceOverride == null) {
            if (startAndroid(sid)) return;
            post(sid, () -> listener.onVoiceError("Android on-device recognizer not available, using Local Whisper"));
        }
        startWhisper(sid);
    }
    private boolean switchToAndroid(int sid, String why) {
        log.add(why + " -> Android on-device recognizer");
        post(sid, () -> { listener.onVoiceError(why + ". Using Android on-device recognizer."); listener.onVoiceEngineChanged("Android on-device"); });
        if (sourceOverride != null) { // tests: no real recognizer; report and end
            running = false; post(sid, () -> { listener.onVoiceFinal("", true); listener.onVoiceState("idle"); }); return true;
        }
        return startAndroid(sid);
    }
    public void stop() {
        stopRequested = true; log.add("stop requested");
        if (androidRec != null) try { ((android.speech.SpeechRecognizer) androidRec).stopListening(); } catch (Throwable ignored) {}
    }
    public void cancel() { cancelNow(); }
    private void cancelNow() {
        if (!running) return;
        log.add("cancelled");
        cancelled = true; stopRequested = true; running = false; session.incrementAndGet();
        try { if (modelReady) WhisperBridge.nativeAbort(); } catch (Throwable ignored) {}
        if (androidRec != null) { try { ((android.speech.SpeechRecognizer) androidRec).destroy(); } catch (Throwable ignored) {} androidRec = null; }
        if (listener != null) listener.onVoiceState("idle");
    }
    private void post(int sid, Runnable r) { main.post(() -> { if (sid == session.get() && listener != null) r.run(); }); }
    private void fail(int sid, String msg) {
        running = false; log.add("ERROR: " + msg);
        post(sid, () -> { listener.onVoiceError(msg); listener.onVoiceState("idle"); });
    }

    // ---------------- whisper ----------------
    private void startWhisper(int sid) {
        post(sid, () -> listener.onVoiceState(modelReady ? "listening" : "loading"));
        if (!modelReady) preload();
        Thread t = new Thread(() -> { try { recordLoop(sid); } catch (Throwable e) { Log.e(TAG, "voice", e); fail(sid, "Voice error: " + e); } }, "voice-rec");
        t.start();
    }
    /** Opens the microphone: VOICE_RECOGNITION then MIC, 16 kHz preferred, else 48/44.1 kHz (resampled). */
    public static PcmSource openMic(VoiceLog log) {
        int[] rates = {16000, 48000, 44100};
        int[] srcs = {android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, android.media.MediaRecorder.AudioSource.MIC};
        StringBuilder tried = new StringBuilder();
        for (int src : srcs) for (int rate : rates) {
            try {
                int min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) { tried.append(rate).append(":minbuf ").append(min).append("; "); continue; }
                @SuppressWarnings("MissingPermission")
                android.media.AudioRecord ar = new android.media.AudioRecord(src, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate));
                if (ar.getState() != android.media.AudioRecord.STATE_INITIALIZED) { ar.release(); tried.append(src).append('/').append(rate).append(":uninit; "); continue; }
                ar.startRecording();
                if (ar.getRecordingState() != android.media.AudioRecord.RECORDSTATE_RECORDING) { ar.release(); tried.append(src).append('/').append(rate).append(":not recording; "); continue; }
                final int actual = ar.getSampleRate(); final String desc = (src == srcs[0] ? "VOICE_RECOGNITION" : "MIC") + " " + actual + " Hz (asked " + rate + ")";
                if (log != null) log.add("mic: " + desc + (tried.length() > 0 ? "; tried " + tried : ""));
                return new PcmSource() {
                    public int read(short[] b, int n) { int r = ar.read(b, 0, n); return r == 0 ? 0 : r < 0 ? -1 : r; }
                    public void close() { try { ar.stop(); } catch (Throwable ignored) {} ar.release(); }
                    public int rate() { return actual; }
                    public String describe() { return desc; }
                };
            } catch (Throwable t) { tried.append(src).append('/').append(rate).append(':').append(t.getClass().getSimpleName()).append(' ').append(t.getMessage()).append("; "); }
        }
        throw new IllegalStateException("microphone unavailable: " + tried);
    }
    private void recordLoop(int sid) {
        PcmSource src;
        try { src = sourceOverride != null ? sourceOverride.get() : openMic(log); }
        catch (Throwable t) { fail(sid, "Microphone error: " + t.getMessage()); return; }
        int rate = src.rate(); lastRate = rate; lastSourceInfo = src.describe();
        vad.reset(); vad.rate = rate;
        int frame = rate / 50; // 20 ms
        float[] buf = new float[RATE * 30]; int n = 0; short[] fr = new short[frame]; AudioUtil.Stream rs = new AudioUtil.Stream(rate);
        boolean announced = false; long lastPartialAt = 0; boolean any = false; int sinceChunkSpeech = 0; long audioMs = 0, frames = 0; int zeroReads = 0; boolean switched = false;
        try {
            while (!stopRequested) {
                int r = src.read(fr, frame); if (r < 0) { log.add("mic read returned " + r); break; }
                if (r == 0) { if (++zeroReads > 500) { log.add("mic gives no data"); break; } continue; }
                frames++;
                boolean sp = vad.feed(fr, r); audioMs += r * 1000L / rate;
                n = rs.push(fr, r, buf, n);
                if (sp) sinceChunkSpeech++;
                final float lv = vad.level(); post(sid, () -> listener.onVoiceLevel(lv));
                if (!announced && modelReady) { announced = true; post(sid, () -> listener.onVoiceState("listening")); } // v8: leave 'Loading…' once the model is ready
                if (loadFailed && !modelReady) { // whisper can't run: switch engines instead of silently doing nothing
                    src.close(); src = null;
                    if (androidAvailable(app)) { switched = true; final String why = "Local Whisper failed (" + loadError + ")"; main.post(() -> { if (sid == session.get()) switchToAndroid(sid, why); }); }
                    else fail(sid, "Voice error: " + loadError);
                    return;
                }
                if (modelReady && vad.heardSpeech() && n - lastPartialAt >= RATE && !partialBusy.get()) { // ~1 s of new audio
                    lastPartialAt = n; partialBusy.set(true); final float[] copy = java.util.Arrays.copyOf(buf, n); final int cn = n;
                    asr.execute(() -> { try { if (sid != session.get()) return; long t0 = System.nanoTime(); AudioUtil.normalize(copy, cn);
                        String t = VoiceText.clean(WhisperBridge.nativeTranscribe(copy, cn, null, true)); lastPartialMs = (System.nanoTime() - t0) / 1_000_000; partialCount++;
                        if (!t.isEmpty() && !stopRequested) post(sid, () -> listener.onVoicePartial(t)); }
                        catch (Throwable e) { log.add("partial failed: " + e); } finally { partialBusy.set(false); } });
                }
                if ((n >= RATE * 20 && !vad.inSpeech() && vad.silentMs() >= 400) || n >= buf.length - RATE / 10) {
                    if (sinceChunkSpeech > 10) { finalPass(sid, java.util.Arrays.copyOf(buf, n), n, false); any = true; }
                    n = 0; lastPartialAt = 0; sinceChunkSpeech = 0;
                }
                if (vad.shouldStop()) { log.add("auto-stop (vad)"); break; }
            }
        } finally { if (src != null) src.close(); }
        lastAudioMs = audioMs; lastPeakDb = vad.peakDb();
        log.add(String.format(java.util.Locale.ROOT, "audio %d ms, %d frames @%d Hz, peak %.1f dBFS, speech=%b, 16k samples %d", audioMs, frames, rate, vad.peakDb(), vad.heardSpeech(), n));
        if (cancelled || sid != session.get() || switched) return;
        post(sid, () -> listener.onVoiceState("transcribing"));
        // v8: transcribe whenever there was speech, or the user pressed Stop on >= 0.5 s of audio that isn't pure silence
        boolean worth = sinceChunkSpeech > 5 || (!any && vad.heardSpeech()) || (stopRequested && n >= RATE / 2 && vad.peakDb() > -70);
        if (worth) finalPass(sid, java.util.Arrays.copyOf(buf, n), n, true);
        else asr.execute(() -> { running = false; log.add("nothing heard"); post(sid, () -> { listener.onVoiceError(vad.heardSpeech() ? "Didn't catch that" : "No speech heard (peak " + Math.round(vad.peakDb()) + " dBFS). Check the mic isn't blocked."); listener.onVoiceFinal("", true); listener.onVoiceState("idle"); }); });
    }
    private void finalPass(int sid, float[] pcm, int n, boolean done) {
        asr.execute(() -> {
            if (sid != session.get()) return;
            try {
                if (!loadNow()) { fail(sid, "Voice error: " + loadError); return; }
                lastGain = AudioUtil.normalize(pcm, n);
                long t0 = System.nanoTime();
                String raw = WhisperBridge.nativeTranscribe(pcm, n, null, false);
                String t = VoiceText.clean(raw);
                lastFinalMs = (System.nanoTime() - t0) / 1_000_000; lastFinalText = t;
                log.add("final " + lastFinalMs + " ms, gain " + lastGain + ": [" + raw + "]");
                if (done) running = false;
                post(sid, () -> { if (t.isEmpty() && done) listener.onVoiceError("Didn't catch that"); listener.onVoiceFinal(t, done); if (done) listener.onVoiceState("idle"); });
            } catch (Throwable e) { Log.e(TAG, "final", e); fail(sid, "Voice error: " + e); }
        });
    }
    /** Blocking transcription of 16 kHz audio (diagnostics). */
    public String transcribeNow(float[] pcm, int n) throws Exception {
        return asr.submit(() -> { if (!loadNow()) return "(model not loaded: " + loadError + ")"; AudioUtil.normalize(pcm, n); return VoiceText.clean(WhisperBridge.nativeTranscribe(pcm, n, null, false)); }).get();
    }
    public void awaitIdleForTest() { try { asr.submit(() -> {}).get(); } catch (Exception e) { throw new RuntimeException(e); } }

    // ---------------- Android on-device recognizer ----------------
    private boolean startAndroid(int sid) {
        if (!androidAvailable(app)) return false;
        try {
            android.speech.SpeechRecognizer sr = android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(app);
            androidRec = sr;
            sr.setRecognitionListener(new android.speech.RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { post(sid, () -> listener.onVoiceState("listening")); }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float db) { post(sid, () -> listener.onVoiceLevel(Math.max(0, Math.min(1, (db + 2) / 12f)))); }
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { post(sid, () -> listener.onVoiceState("transcribing")); }
                public void onError(int e) { running = false; androidRec = null; sr.destroy(); log.add("android recognizer error " + e);
                    post(sid, () -> { if (e == android.speech.SpeechRecognizer.ERROR_NO_MATCH || e == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { listener.onVoiceError("Didn't catch that"); listener.onVoiceFinal("", true); }
                        else listener.onVoiceError("Android recognizer error " + e); listener.onVoiceState("idle"); }); }
                public void onResults(Bundle b) { ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    String t = r == null || r.isEmpty() ? "" : r.get(0); running = false; androidRec = null; sr.destroy(); log.add("android result: [" + t + "]");
                    post(sid, () -> { listener.onVoiceFinal(VoiceText.clean(t), true); listener.onVoiceState("idle"); }); }
                public void onPartialResults(Bundle b) { ArrayList<String> r = b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    if (r != null && !r.isEmpty() && !r.get(0).isEmpty()) { String t = r.get(0); post(sid, () -> listener.onVoicePartial(t)); } }
                public void onEvent(int t, Bundle b) {}
            });
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            if (android.os.Build.VERSION.SDK_INT >= 33) i.putExtra(android.speech.RecognizerIntent.EXTRA_ENABLE_FORMATTING, android.speech.RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
            sr.startListening(i); log.add("android recognizer started");
            post(sid, () -> listener.onVoiceState("listening"));
            return true;
        } catch (Throwable t) { Log.e(TAG, "android recognizer", t); log.add("android recognizer failed: " + t); androidRec = null; return false; }
    }
}
