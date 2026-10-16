package com.jason.aikeyboard;

import static org.junit.Assert.*;

import android.app.Activity;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import com.jason.aikeyboard.ai.VoiceEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

/** v7: mic button, permission flow, listening panel, and end-to-end dictation of a WAV through real whisper.cpp. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h915dp-port-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VoiceServiceTest {
    ServiceTest.TestService s; EditText et; View input; Activity act;
    @Before public void setUp() {
        Learned.resetForTests(); Learned.setCodecForTests(ServiceTest.XOR); VoiceEngine.resetInstanceForTests();
        // v8: load whisper from inside the real APK at the stored entry's offset/length, exactly like the phone does
        try { long[] e = VoiceCoreTest.apkEntry(VoiceCoreTest.APK, "assets/whisper.bin"); VoiceEngine.modelPathOverride = VoiceCoreTest.APK; VoiceEngine.modelOffsetOverride = e[0]; VoiceEngine.modelLengthOverride = e[1]; }
        catch (Exception ex) { throw new RuntimeException(ex); }
        VoiceEngine.androidAvailableOverride = false;
        act = Robolectric.buildActivity(Activity.class).setup().get();
        et = new EditText(act); et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        act.setContentView(et); et.requestFocus();
        s = Robolectric.buildService(ServiceTest.TestService.class).create().get();
        s.ei = new EditorInfo(); s.ei.inputType = et.getInputType(); s.ic = et.onCreateInputConnection(s.ei);
        input = s.onCreateInputView(); s.onStartInputView(s.ei, false); layout();
    }
    @After public void tearDown() { try { s.voiceEngine().cancel(); s.voiceEngine().awaitIdleForTest(); } catch (Throwable ignored) {} paceMs = 10; VoiceEngine.androidAvailableOverride = null; VoiceEngine.modelOffsetOverride = 0; VoiceEngine.modelLengthOverride = -1; VoiceDiagActivity.micGrantedOverride = null; AIKeyboardService.micGrantedOverride = null; VoiceEngine.sourceOverride = null; }
    void layout() { input.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); input.layout(0, 0, 1440, input.getMeasuredHeight()); }
    void tapBar(float xdp) { View bar = s.barForTest(); float d = bar.getResources().getDisplayMetrics().density, py = bar.getHeight() / 2f;
        bar.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, xdp * d, py, 0)); bar.dispatchTouchEvent(MotionEvent.obtain(0, 50, MotionEvent.ACTION_UP, xdp * d, py, 0)); ShadowLooper.idleMainLooper(); }
    static final float MIC_X = 48 + 42 + 42 + 21; // ✨ 48dp | paste 42 | 😊 42 | 🎤 42

    /** WAV played as a microphone (paced at 2x real time), followed by quiet noise so the VAD can auto-stop. */
    static volatile int paceMs = 10;
    static VoiceEngine.PcmSource wavSource(String... files) { return wavSource(16000, 1f, files); }
    /** rate: the WAV files' rate (48 kHz files simulate Samsung ignoring 16 kHz); gain scales the level. */
    static VoiceEngine.PcmSource wavSource(int rate, float gain, String... files) {
        java.util.List<short[]> parts = new java.util.ArrayList<>();
        try { for (String f : files) { parts.add(VoiceCoreTest.wav(f)); parts.add(new short[rate / 2]); } } catch (Exception e) { throw new RuntimeException(e); }
        int n = 0; for (short[] p : parts) n += p.length; short[] all = new short[n + rate * 4]; int o = 0; for (short[] p : parts) { System.arraycopy(p, 0, all, o, p.length); o += p.length; }
        java.util.Random r = new java.util.Random(3); for (int i = 0; i < all.length; i++) all[i] = (short) (all[i] * gain + r.nextGaussian() * 25 * gain);
        final short[] a = all; final int[] pos = {0};
        return new VoiceEngine.PcmSource() {
            public int read(short[] b, int k) { if (pos[0] >= a.length) return -1; int c = Math.min(k, a.length - pos[0]); System.arraycopy(a, pos[0], b, 0, c); pos[0] += c;
                try { Thread.sleep(paceMs * k / (rate / 50)); } catch (InterruptedException ignored) {} return c; }
            public void close() {}
            public int rate() { return rate; }
            public String describe() { return "WAV " + rate + " Hz"; }
        };
    }
    String text() { return et.getText().toString(); }
    /** Runs the main looper until the session ends; screenshots the listening panel once partial text shows. */
    void runSession(String shotName, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs; boolean shot = shotName == null;
        while (System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(); Thread.sleep(15);
            if (!shot && s.voiceForTest().partial().length() > 12 && "listening".equals(s.voiceForTest().state())) { ServiceTest.shot(input, 1440, shotName); shot = true; }
            if (!s.voiceShown() && !s.voiceEngine().isRunning()) break;
        }
        ShadowLooper.idleMainLooper();
        assertFalse("session did not finish", s.voiceShown());
        if (shotName != null) assertTrue("no partial text appeared while listening; partials=" + s.voiceEngine().partialCount + " text=" + text() + "\n" + s.voiceEngine().log.text(), shot);
    }

    @Test public void micButtonInBarAndScreenshot() throws Exception {
        View bar = s.barForTest();
        ((CandidateBar) bar).setSuggestions(java.util.Arrays.asList("Hey", "I", "The", "Thanks", "So", "What", "Ok"), 0, -1);
        ServiceTest.shot(input, 1440, "v7_mic_button");
        assertTrue(((CandidateBar) bar).maxScrollForTest() > 0); // suggestions strip still scrolls next to the fixed buttons
        AIKeyboardService.micGrantedOverride = false;
        tapBar(MIC_X);
        android.content.Intent i = s.lastPermIntent;
        assertNotNull("permission activity not launched", i);
        assertTrue(i.toString(), i.toString().contains("MicPermissionActivity"));
        assertTrue((i.getFlags() & android.content.Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertFalse(s.voiceShown());
    }
    @Test public void dictationEndToEndWithRealWhisper() throws Exception {
        AIKeyboardService.micGrantedOverride = true;
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_01.wav", VoiceCoreTest.VDIR + "/wav/piper_09.wav");
        paceMs = 20; // real time, like a microphone
        tapBar(MIC_X);
        assertTrue(s.voiceShown()); assertTrue(s.barForTest().micActive()); assertEquals(View.GONE, s.keyboardForTest().getVisibility());
        runSession("v7_listening", 60000);
        System.out.println("v7 dictated: [" + text() + "] partials=" + s.voiceEngine().partialCount + " last partial ms=" + s.voiceEngine().lastPartialMs + " final ms=" + s.voiceEngine().lastFinalMs + " audio ms=" + s.voiceEngine().lastAudioMs);
        String want = "Hey, are we still meeting for lunch tomorrow at noon? My phone battery is almost dead, so text me instead. ";
        double wer = com.jason.aikeyboard.core.VoiceText.wer(java.util.Collections.singletonList(want), java.util.Collections.singletonList(text()));
        assertTrue(text() + " WER " + wer, wer <= 10);
        assertTrue(text(), text().startsWith("Hey") && text().endsWith(" "));
        assertTrue(s.voiceEngine().partialCount > 0);
        assertEquals(View.VISIBLE, s.keyboardForTest().getVisibility()); assertFalse(s.barForTest().micActive()); assertNull(s.barForTest().voiceText());
        assertTrue("voice text learned", s.userForTest().count("lunch") > 0);
        // keep typing after dictation: suggestions + mid-sentence insertion with spacing / lower-case
        ServiceTest.TestService t = s; for (char c : "ok".toCharArray()) t.onKey(c); ShadowLooper.idleMainLooper();
        assertTrue(text(), text().endsWith("instead. Ok"));
        s.insertVoiceText(s.ic, " Thanks for waiting.");
        assertTrue(text(), text().endsWith("Ok thanks for waiting. "));
    }
    @Test public void cancelDiscardsAndVoiceLearningCanBeOff() throws Exception {
        AIKeyboardService.micGrantedOverride = true;
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_03.wav");
        tapBar(MIC_X); for (int i = 0; i < 20; i++) { ShadowLooper.idleMainLooper(); Thread.sleep(10); }
        s.onVoiceCancel(); ShadowLooper.idleMainLooper();
        assertFalse(s.voiceShown()); Thread.sleep(300); ShadowLooper.idleMainLooper(); assertEquals("", text());
        Settings.prefs(s).edit().putBoolean(Settings.VOICE_LEARN, false).apply(); s.onStartInputView(s.ei, false);
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_05.wav");
        tapBar(MIC_X); runSession(null, 60000);
        assertTrue(text(), text().toLowerCase().contains("milk and eggs"));
        assertEquals(0, s.userForTest().count("eggs"));
    }
    @Test public void tappingStopInsertsImmediately() throws Exception {
        AIKeyboardService.micGrantedOverride = true; Settings.prefs(s).edit().putBoolean(Settings.VOICE_AUTOSTOP, false).apply();
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_06.wav");
        for (char c : "sure".toCharArray()) s.onKey(c); s.onKey('.'); ShadowLooper.idleMainLooper();
        tapBar(MIC_X); long end = System.currentTimeMillis() + 3000; while (System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }
        // tap the Stop button on the panel
        layout(); VoicePanel vp = s.voiceForTest(); android.graphics.RectF r = vp.stopRectForTest();
        vp.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0)); vp.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0));
        runSession(null, 60000);
        assertTrue(text(), text().startsWith("Sure. I'll call you back"));
    }
    @Test public void emojiAndPanelsSwitchCleanly() {
        AIKeyboardService.micGrantedOverride = true;
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_03.wav");
        tapBar(MIC_X); assertTrue(s.voiceShown());
        s.onEmojiButton(); ShadowLooper.idleMainLooper();
        assertFalse(s.voiceShown()); assertEquals(View.VISIBLE, s.emojiForTest().getVisibility());
        s.onEmojiButton(); assertEquals(View.VISIBLE, s.keyboardForTest().getVisibility());
        s.ei.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD; s.onStartInputView(s.ei, false);
        tapBar(MIC_X); assertFalse("no voice in password fields", s.voiceShown());
    }

    @Test public void v8Mic48kHzQuietStillTranscribes() throws Exception {
        AIKeyboardService.micGrantedOverride = true;
        VoiceEngine.sourceOverride = () -> wavSource(48000, 0.08f, VoiceCoreTest.VDIR + "/wav48/piper_05.wav"); // 48 kHz and ~-22 dB quieter
        tapBar(MIC_X); runSession(null, 60000);
        System.out.println("v8 48k quiet: [" + text() + "] rate " + s.voiceEngine().lastRate + " peak " + s.voiceEngine().lastPeakDb + " gain " + s.voiceEngine().lastGain);
        assertEquals(48000, s.voiceEngine().lastRate);
        assertTrue(text(), text().toLowerCase().contains("milk and eggs on your way back"));
    }
    @Test public void v8ModelFailureIsVisibleNotSilent() throws Exception {
        com.jason.aikeyboard.ai.WhisperBridge.loadLibrary(); com.jason.aikeyboard.ai.WhisperBridge.nativeUnload();
        AIKeyboardService.micGrantedOverride = true; VoiceEngine.modelLengthOverride = -1; // the v7 bug: reads past the entry
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_03.wav");
        tapBar(MIC_X);
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end && (s.voiceForTest().error() == null || s.voiceEngine().isRunning())) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }
        ShadowLooper.idleMainLooper();
        String err = s.voiceForTest().error(); System.out.println("v8 visible error: " + err + "\n" + s.voiceEngine().log.text());
        assertNotNull("error must be shown in the panel", err); assertTrue(err, err.startsWith("Voice error") && err.contains("failed to load"));
        assertTrue("panel stays open so the error can be read", s.voiceShown());
        ServiceTest.shot(input, 1440, "v8_voice_error");
        s.onVoiceStop(); ShadowLooper.idleMainLooper(); assertFalse(s.voiceShown()); // Close
        assertEquals("", text());
    }
    @Test public void v8FallsBackToAndroidWhenWhisperFails() throws Exception {
        com.jason.aikeyboard.ai.WhisperBridge.loadLibrary(); com.jason.aikeyboard.ai.WhisperBridge.nativeUnload();
        AIKeyboardService.micGrantedOverride = true; VoiceEngine.modelLengthOverride = -1; VoiceEngine.androidAvailableOverride = true;
        VoiceEngine.sourceOverride = () -> wavSource(VoiceCoreTest.VDIR + "/wav/piper_03.wav");
        tapBar(MIC_X);
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end && s.voiceEngine().isRunning()) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }
        ShadowLooper.idleMainLooper();
        System.out.println("v8 fallback: " + s.lastVoiceError);
        assertTrue(s.lastVoiceError, s.lastVoiceError.contains("Using Android on-device recognizer"));
    }
    @Test public void v8DiagnosticsScreen() throws Exception {
        VoiceEngine.sourceOverride = () -> wavSource(48000, 0.3f, VoiceCoreTest.VDIR + "/wav48/piper_01.wav");
        VoiceDiagActivity.micGrantedOverride = true;
        VoiceDiagActivity a = Robolectric.buildActivity(VoiceDiagActivity.class).setup().get();
        String r = a.runDiag(); a.report.setText(r);
        System.out.println(r);
        assertTrue(r, r.contains("libaikvoice.so: loaded OK"));
        assertTrue(r, r.contains("sample rate 48000 Hz")); assertTrue(r, r.contains("speech detected"));
        assertTrue(r, r.contains("Hey, are we still meeting for lunch tomorrow at noon?"));
        assertTrue(r, r.contains("Saved to"));
        View root = a.getWindow().getDecorView().getRootView();
        ServiceTest.shot(((android.view.ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0), 1440, "v8_voice_diag");
    }
}
