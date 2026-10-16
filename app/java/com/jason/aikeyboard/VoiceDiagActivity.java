package com.jason.aikeyboard;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jason.aikeyboard.ai.AudioUtil;
import com.jason.aikeyboard.ai.VoiceEngine;
import com.jason.aikeyboard.ai.WhisperBridge;
import com.jason.aikeyboard.core.Vad;

/** v8: Settings > Voice diagnostics. Records 3 s, checks library / model / mic / decode, saves Downloads/AIKeyboard-voice-log.txt. */
public final class VoiceDiagActivity extends Activity {
    public static final String LOG_NAME = "AIKeyboard-voice-log.txt";
    TextView report; Button run; volatile String lastReport = ""; volatile String savedTo = "";
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(0xFF121316);
        ScrollView sv = new ScrollView(this); sv.setFitsSystemWindows(true); sv.setBackgroundColor(0xFF121316);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); int p = dp(18); l.setPadding(p, p, p, p); sv.addView(l);
        TextView t = new TextView(this); t.setText("Voice diagnostics"); t.setTextSize(24); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFFECEDEF); l.addView(t);
        TextView h = new TextView(this); h.setText("Tap Run, then say a short sentence. It records 3 seconds, checks the speech engine, and saves a report to Downloads/" + LOG_NAME + " that you can send."); h.setTextColor(0xFFB0B4BC); h.setTextSize(14); l.addView(h);
        run = new Button(this); run.setText("Run 3-second test"); run.setAllCaps(false); run.setOnClickListener(v -> start()); l.addView(run);
        report = new TextView(this); report.setTextColor(0xFFECEDEF); report.setTextSize(12.5f); report.setTypeface(Typeface.MONOSPACE); report.setTextIsSelectable(true); l.addView(report);
        setContentView(sv);
    }
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }
    static volatile Boolean micGrantedOverride;
    void start() {
        boolean granted = micGrantedOverride != null ? micGrantedOverride : checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!granted) { requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7); return; }
        run.setEnabled(false); report.setText("Recording… speak now");
        new Thread(() -> { String r = runDiag(); main.post(() -> { report.setText(r); run.setEnabled(true); }); }, "voice-diag").start();
    }
    @Override public void onRequestPermissionsResult(int c, String[] p, int[] res) { if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) start(); else report.setText("Microphone permission denied."); }

    /** Runs every check; returns the report (also saved to Downloads). Safe to call off the main thread. */
    String runDiag() {
        StringBuilder r = new StringBuilder(); VoiceEngine ve = VoiceEngine.get(this);
        r.append("LocalKey AI voice diagnostics\n").append(new java.util.Date()).append('\n');
        try { r.append("app ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName).append('\n'); } catch (Throwable ignored) {}
        r.append("device ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append(", Android ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append("), ABIs ").append(String.join(",", android.os.Build.SUPPORTED_ABIS)).append('\n');
        r.append("cores ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        boolean lib = WhisperBridge.loadLibrary();
        r.append("\n[library] libaikvoice.so: ").append(lib ? "loaded OK" : "FAILED " + WhisperBridge.libError()).append('\n');
        if (lib) try { r.append("cpu features: ").append(WhisperBridge.nativeSystemInfo()).append('\n'); } catch (Throwable t) { r.append("system info failed: ").append(t).append('\n'); }
        try { android.content.res.AssetFileDescriptor afd = getAssets().openFd(VoiceEngine.ASSET); r.append("model asset: offset ").append(afd.getStartOffset()).append(", length ").append(afd.getLength()).append(" (stored, readable in place)\n"); afd.close(); }
        catch (Throwable t) { r.append("model asset: ").append(VoiceEngine.modelPathOverride != null ? "test file " + VoiceEngine.modelPathOverride : "openFd FAILED " + t).append('\n'); }
        // record
        r.append("\n[microphone]\n");
        float[] pcm16 = new float[16000 * 4]; int n16 = 0; Vad vad = new Vad(); int speech = 0; long frames = 0; double sumsq = 0; long samples = 0; int rate = 0;
        VoiceEngine.PcmSource src = null;
        try {
            src = VoiceEngine.sourceOverride != null ? VoiceEngine.sourceOverride.get() : VoiceEngine.openMic(ve.log);
            rate = src.rate(); vad.rate = rate; r.append("source ").append(src.describe()).append('\n');
            AudioUtil.Stream rs = new AudioUtil.Stream(rate); short[] fr = new short[rate / 50]; long end = 3L * rate, got = 0; int zero = 0;
            while (got < end) { int k = src.read(fr, fr.length); if (k < 0) break; if (k == 0) { if (++zero > 300) break; continue; } got += k; frames++;
                if (vad.feed(fr, k)) speech++; for (int i = 0; i < k; i++) { double v = fr[i] / 32768.0; sumsq += v * v; } samples += k; n16 = rs.push(fr, k, pcm16, n16); }
            r.append(String.format(java.util.Locale.ROOT, "sample rate %d Hz, %d frames, %.2f s, peak %.1f dBFS, rms %.1f dBFS, speech frames %d (%s)%n",
                rate, frames, samples / (double) Math.max(1, rate), vad.peakDb(), 10 * Math.log10(sumsq / Math.max(1, samples) + 1e-12), speech, vad.heardSpeech() ? "speech detected" : "NO speech detected"));
            if (vad.peakDb() < -70) r.append("WARNING: input is almost silent; another app may hold the mic, or the mic is blocked\n");
        } catch (Throwable t) { r.append("FAILED: ").append(t.getMessage()).append('\n'); }
        finally { if (src != null) src.close(); }
        // decode
        r.append("\n[whisper]\n");
        try {
            long t0 = System.currentTimeMillis(); String text = n16 > 0 ? ve.transcribeNow(java.util.Arrays.copyOf(pcm16, n16), n16) : "(no audio)";
            r.append("model: ").append(ve.modelReady() ? "loaded in " + ve.loadMs + " ms" : "NOT loaded: " + ve.loadError()).append('\n');
            r.append("decode ").append(System.currentTimeMillis() - t0).append(" ms (incl. load): \"").append(text).append("\"\n");
        } catch (Throwable t) { r.append("decode FAILED: ").append(t).append('\n'); }
        r.append("\n[android on-device recognizer] ").append(VoiceEngine.androidAvailable(this) ? "available" : "not available").append('\n');
        r.append("\n[recent voice log]\n").append(ve.log.text());
        String out = r.toString(); lastReport = out;
        savedTo = save(out); out = out + "\nSaved to " + savedTo + "\n"; lastReport = out;
        return out;
    }
    /** Writes Downloads/AIKeyboard-voice-log.txt (MediaStore, no storage permission needed); falls back to app storage. */
    String save(String text) {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            android.content.ContentResolver cr = getContentResolver(); android.net.Uri col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            try { cr.delete(col, android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{LOG_NAME}); } catch (Throwable ignored) {} // our previous copy
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, LOG_NAME); cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            android.net.Uri u = cr.insert(col, cv);
            if (u != null) { try (java.io.OutputStream o = cr.openOutputStream(u)) { o.write(data); } return "Downloads/" + LOG_NAME; }
        } catch (Throwable ignored) {}
        try { java.io.File f = new java.io.File(getExternalFilesDir(null) != null ? getExternalFilesDir(null) : getFilesDir(), LOG_NAME); try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(data); } return f.getPath(); }
        catch (Throwable t) { return "(could not save: " + t + ")"; }
    }
}
