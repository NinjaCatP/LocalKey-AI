package com.jason.aikeyboard.ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the local LLM on one background thread. Predictions are "latest wins": a newer request cancels
 * the one in flight and stale results are dropped. Nothing here touches the network.
 */
public final class AiEngine {
    public interface Callback<T> { void onResult(T value); }
    public enum State { IDLE, LOADING, READY, FAILED }
    private static final String TAG = "AIKeyboard";
    public static final String ASSET = "model.gguf";
    private static AiEngine instance;

    private final Context app;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ai-engine"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger predictGen = new AtomicInteger();
    private final AtomicInteger rewriteGen = new AtomicInteger();
    private volatile State state = State.IDLE;
    private volatile String error = "";
    public volatile long lastPredictMs, lastRewriteMs, loadMs;

    public static synchronized AiEngine get(Context c) { if (instance == null) instance = new AiEngine(c.getApplicationContext()); return instance; }
    private AiEngine(Context c) { app = c; }
    /** Tests only: Robolectric gives every test a fresh app/data dir. */
    public static synchronized void resetInstanceForTests() { instance = null; }
    public State state() { return state; }
    public String error() { return error; }

    static int threads() {
        // Snapdragon 8 Gen 3: 1+5 big/mid cores; 4 threads keeps the UI and other apps responsive.
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, n - 2));
    }

    /** Allow another load attempt after a failure (e.g. after importing a model file). */
    public void resetIfFailed() { if (state == State.FAILED) state = State.IDLE; }
    public void ensureLoaded() {
        if (state != State.IDLE) return;
        state = State.LOADING;
        exec.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                if (!LlamaBridge.loadLibrary()) throw new IllegalStateException("native library missing");
                String path; long off;
                File imported = new File(app.getFilesDir(), "model.gguf");
                if (imported.exists()) { path = imported.getPath(); off = -1; }
                else {
                    AssetFileDescriptor afd = app.getAssets().openFd(ASSET); // stored uncompressed in the APK
                    off = afd.getStartOffset(); afd.close();
                    path = app.getApplicationInfo().sourceDir;
                    if (off % 32 != 0) { copyAsset(imported); path = imported.getPath(); off = -1; }
                }
                boolean ok = LlamaBridge.nativeLoad(path, off, threads(), 1024);
                if (!ok) throw new IllegalStateException("model failed to load");
                loadMs = System.currentTimeMillis() - t0;
                state = State.READY;
                Log.i(TAG, "model ready in " + loadMs + " ms");
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage()); state = State.FAILED; Log.e(TAG, "load failed", t);
            }
        });
    }
    private void copyAsset(File dst) throws java.io.IOException {
        File tmp = new File(dst.getPath() + ".tmp");
        try (InputStream in = app.getAssets().open(ASSET); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[1 << 20]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        if (!tmp.renameTo(dst)) throw new java.io.IOException("rename failed");
    }

    /** Next-word / completion predictions. Callback runs on the main thread, only if still the latest request. */
    public void predict(String context, String prefix, int k, Callback<List<String>> cb) {
        if (state != State.READY) return;
        final int gen = predictGen.incrementAndGet();
        LlamaBridge.nativeCancel();
        exec.execute(() -> {
            if (gen != predictGen.get()) return; // superseded before it started
            long t0 = System.currentTimeMillis();
            String[] r = LlamaBridge.nativePredict(context, prefix, k);
            lastPredictMs = System.currentTimeMillis() - t0;
            if (gen != predictGen.get() || r == null) return;
            List<String> list = Arrays.asList(r);
            main.post(() -> { if (gen == predictGen.get()) cb.onResult(list); });
        });
    }
    public void cancelPredictions() { predictGen.incrementAndGet(); }

    public enum Action {
        FIX("Fix grammar", "You are a careful proofreader. Correct every grammar, spelling, capitalization and punctuation mistake in the user's text, including pronoun case, verb tense and subject-verb agreement. Keep the meaning and tone and change as little as possible. Reply with only the corrected text."),
        POLITE("More polite", "Rewrite the user's message to be more polite and friendly. Keep it about the same length. Reply with only the rewritten message."),
        SHORTER("Shorter", "Rewrite the user's message to be shorter and concise, keeping the key information. Reply with only the rewritten message."),
        CASUAL("Casual", "Rewrite the user's message in a casual, relaxed texting tone. Reply with only the rewritten message.");
        public final String label, system;
        Action(String l, String s) { label = l; system = s; }
    }

    public void rewrite(Action a, String text, Callback<String> cb) {
        final int gen = rewriteGen.incrementAndGet();
        predictGen.incrementAndGet();
        LlamaBridge.nativeCancel();
        exec.execute(() -> {
            if (gen != rewriteGen.get()) return;
            long t0 = System.currentTimeMillis();
            int maxTok = Math.min(400, Math.max(48, text.length() / 2 + 32));
            String r = state == State.READY ? LlamaBridge.nativeGenerate(a.system, text, maxTok) : null;
            lastRewriteMs = System.currentTimeMillis() - t0;
            String clean = cleanup(r);
            if (a == Action.FIX && clean != null && !clean.isEmpty() && Character.isLowerCase(clean.charAt(0))) clean = Character.toUpperCase(clean.charAt(0)) + clean.substring(1);
            final String res = clean;
            main.post(() -> { if (gen == rewriteGen.get()) cb.onResult(res); });
        });
    }
    public void cancelRewrite() { rewriteGen.incrementAndGet(); LlamaBridge.nativeCancel(); }

    static String cleanup(String r) {
        if (r == null) return null;
        String s = r.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") && s.indexOf('"', 1) == s.length() - 1) s = s.substring(1, s.length() - 1);
        return s;
    }
}
