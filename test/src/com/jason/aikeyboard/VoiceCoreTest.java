package com.jason.aikeyboard;

import static org.junit.Assert.*;

import com.jason.aikeyboard.ai.WhisperBridge;
import com.jason.aikeyboard.core.Vad;
import com.jason.aikeyboard.core.VoiceText;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** v7: voice text formatting, VAD, and real whisper.cpp (host x86 build) on TTS WAVs: WER + real-time factor. */
public class VoiceCoreTest {
    static final String VDIR = "/workspace/aikeyboard/test/voice";
    static final String MODEL = System.getProperty("aik.whisper", "/workspace/aikeyboard/models/ggml-base.en-q5_1.bin");
    static short[] wav(String f) throws Exception {
        byte[] b = Files.readAllBytes(new File(f).toPath()); short[] s = new short[(b.length - 44) / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[44 + 2 * i] & 0xff) | (b[45 + 2 * i] << 8)); return s;
    }
    static float[] toF(short[] s) { float[] f = new float[s.length]; for (int i = 0; i < s.length; i++) f[i] = s[i] / 32768f; return f; }

    static long[] apkEntry(String apk, String name) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk); java.io.RandomAccessFile f = new java.io.RandomAccessFile(apk, "r")) {
            // find the local header offset by scanning the central directory with java.util.zip is not exposed; parse manually
            byte[] all = new byte[(int) Math.min(f.length(), 1 << 22)]; long cdEnd = f.length() - 22; f.seek(cdEnd); byte[] eocd = new byte[22]; f.readFully(eocd);
            java.nio.ByteBuffer e = java.nio.ByteBuffer.wrap(eocd).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int cdSize = e.getInt(12), cdOff = e.getInt(16); byte[] cd = new byte[cdSize]; f.seek(cdOff); f.readFully(cd);
            java.nio.ByteBuffer c = java.nio.ByteBuffer.wrap(cd).order(java.nio.ByteOrder.LITTLE_ENDIAN); int p = 0;
            while (p < cdSize) { int nl = c.getShort(p + 28) & 0xffff, el = c.getShort(p + 30) & 0xffff, cl = c.getShort(p + 32) & 0xffff; long size = c.getInt(p + 24) & 0xffffffffL, lho = c.getInt(p + 42) & 0xffffffffL;
                String nm = new String(cd, p + 46, nl, "UTF-8");
                if (nm.equals(name)) { f.seek(lho + 26); int lnl = Short.reverseBytes(f.readShort()) & 0xffff, lel = Short.reverseBytes(f.readShort()) & 0xffff; return new long[]{lho + 30 + lnl + lel, size}; }
                p += 46 + nl + el + cl; }
        }
        throw new IllegalStateException(name + " not in " + apk);
    }
    static final String APK = "/workspace/aikeyboard/output/AIKeyboard-v8-arm64-signed.apk";
    /** v8 regression: the v7 phone bug. Model read in place from the APK must stop at the entry end (length), not at the APK end. */
    @Test public void loadsModelFromInsideApk() throws Exception {
        assertTrue(WhisperBridge.loadLibrary());
        WhisperBridge.nativeUnload(); long[] e = apkEntry(APK, "assets/whisper.bin");
        assertFalse("v7 behaviour (no length) should fail: it reads zip data as tensors", WhisperBridge.nativeLoad(APK, e[0], -1, 4));
        System.out.println("no-length load error (expected): " + WhisperBridge.nativeError());
        assertTrue(WhisperBridge.nativeLoad(APK, e[0], e[1], 4));
        short[] s = wav(VDIR + "/wav/piper_01.wav");
        String t = VoiceText.clean(WhisperBridge.nativeTranscribe(toF(s), s.length, null, false));
        System.out.println("from APK: " + t); assertEquals("Hey, are we still meeting for lunch tomorrow at noon?", t);
        WhisperBridge.nativeUnload();
    }
    @Test public void resampled48kAndQuietAudio() throws Exception {
        if (!WhisperBridge.loadLibrary()) fail(); assertTrue(WhisperBridge.nativeLoad(MODEL, 0, -1, 4));
        List<String> ref = Files.readAllLines(new File(VDIR + "/sentences.txt").toPath());
        List<String> R = new ArrayList<>(), H = new ArrayList<>(), Q = new ArrayList<>();
        for (int i : new int[]{1, 5, 9}) {
            short[] s48 = wav(String.format("%s/wav48/piper_%02d.wav", VDIR, i));
            float[] x = com.jason.aikeyboard.ai.AudioUtil.to16k(s48, s48.length, 48000);
            assertEquals(s48.length / 3, x.length, 2);
            // streaming resampler in 20 ms blocks must match the batch length
            com.jason.aikeyboard.ai.AudioUtil.Stream st = new com.jason.aikeyboard.ai.AudioUtil.Stream(48000); float[] y = new float[x.length + 10]; int yn = 0; short[] blk = new short[960];
            for (int o = 0; o < s48.length; o += 960) { int k = Math.min(960, s48.length - o); System.arraycopy(s48, o, blk, 0, k); yn = st.push(blk, k, y, yn); }
            assertEquals(x.length, yn, 2);
            R.add(ref.get(i - 1)); H.add(VoiceText.clean(WhisperBridge.nativeTranscribe(y, yn, null, false)));
            // very quiet (-30 dB) 16 kHz copy: normalize() brings it back
            short[] s = wav(String.format("%s/wav/piper_%02d.wav", VDIR, i)); float[] q = toF(s); for (int k = 0; k < q.length; k++) q[k] *= 0.03f;
            float g = com.jason.aikeyboard.ai.AudioUtil.normalize(q, q.length); assertTrue(g > 5);
            Q.add(VoiceText.clean(WhisperBridge.nativeTranscribe(q, q.length, null, false)));
        }
        double w48 = VoiceText.wer(R, H), wq = VoiceText.wer(R, Q);
        System.out.println("48 kHz resampled WER " + w48 + " " + H + "\nquiet+normalized WER " + wq + " " + Q);
        assertTrue(w48 < 8); assertTrue(wq < 8);
        // VAD hears quiet speech (Samsung VOICE_RECOGNITION is often low): -30 dB copy at 48 kHz
        Vad v = new Vad(); v.rate = 48000; short[] s48 = wav(VDIR + "/wav48/piper_01.wav"); short[] fr = new short[960];
        for (int o = 0; o + 960 <= s48.length; o += 960) { for (int k = 0; k < 960; k++) fr[k] = (short) (s48[o + k] * 0.03); v.feed(fr, 960); }
        assertTrue("quiet speech detected, peak " + v.peakDb(), v.heardSpeech());
        WhisperBridge.nativeUnload();
    }
    @Test public void cleanRemovesWhisperTags() {
        assertEquals("Hello there.", VoiceText.clean(" [BLANK_AUDIO] Hello  there. (music)"));
        assertEquals("", VoiceText.clean(" [BLANK_AUDIO]"));
        assertEquals("", VoiceText.clean(" ..."));
        assertEquals("", VoiceText.clean("♪♪"));
    }
    @Test public void formatSpacingAndCapitals() {
        VoiceText.LowerOk all = w -> true;
        assertEquals("Hello world. ", VoiceText.format("", " hello world.", true, all));
        assertEquals(" see you soon. ", VoiceText.format("I will", " See you soon.", true, all));        // mid-sentence: lower-case, leading space
        assertEquals("See you soon. ", VoiceText.format("Done. ", " see you soon.", true, all));          // after sentence end: capital, no extra space
        assertEquals(" I think so. ", VoiceText.format("well", " I think so.", true, all));               // "I" stays capital
        assertEquals(" NASA called. ", VoiceText.format("so", " NASA called.", true, all));               // acronym kept
        assertEquals(" Jason said hi. ", VoiceText.format("so", " Jason said hi.", true, w -> !w.equals("jason"))); // proper noun kept
        assertEquals("hello ", VoiceText.format("", "hello", false, all));                                 // auto-cap off
        assertEquals(", and then. ", VoiceText.format("ok", ", and then.", true, all));                    // no space before punctuation
        assertEquals("", VoiceText.format("x", "[BLANK_AUDIO]", true, all));
    }
    @Test public void wordsForLearning() {
        assertEquals(Arrays.asList("Hey", "are", "we", "still", "on", null, "I'm", "here", null), VoiceText.words("Hey, are we still on? I'm here."));
    }
    @Test public void vadDetectsSpeechAndSilenceStop() throws Exception {
        short[] s = wav(VDIR + "/wav/piper_01.wav"); Vad v = new Vad(); short[] fr = new short[320];
        int firstSpeech = -1;
        for (int i = 0; i + 320 <= s.length; i += 320) { System.arraycopy(s, i, fr, 0, 320); if (v.feed(fr, 320) && firstSpeech < 0) firstSpeech = i; }
        assertTrue(v.heardSpeech()); assertFalse("should not stop mid-clip", v.shouldStop() && v.silentMs() < 1000);
        short[] z = new short[320]; java.util.Random r = new java.util.Random(1);
        int ms = 0; while (!v.shouldStop() && ms < 5000) { for (int k = 0; k < 320; k++) z[k] = (short) (r.nextGaussian() * 30); v.feed(z, 320); ms += 20; }
        System.out.println("VAD: first speech at " + firstSpeech / 16 + " ms, auto-stop after " + ms + " ms of trailing silence");
        assertTrue(v.shouldStop()); assertTrue("stop after " + ms, ms >= 1000 && ms <= 2200);
        Vad q = new Vad(); ms = 0; while (!q.shouldStop() && ms < 10000) { for (int k = 0; k < 320; k++) z[k] = (short) (r.nextGaussian() * 30); q.feed(z, 320); ms += 20; }
        assertFalse(q.heardSpeech()); assertEquals(7000, ms); // nothing said: gives up after 7 s, no transcription
    }
    @Test public void whisperWerAndRealTimeFactor() throws Exception {
        assertTrue(WhisperBridge.loadLibrary());
        long t0 = System.nanoTime(); assertTrue(WhisperBridge.nativeLoad(MODEL, 0, -1, 4)); long loadMs = (System.nanoTime() - t0) / 1_000_000;
        List<String> ref = new ArrayList<>(); for (String l : Files.readAllLines(new File(VDIR + "/sentences.txt").toPath())) if (!l.trim().isEmpty()) ref.add(l.trim());
        StringBuilder rep = new StringBuilder("whisper base.en q5_1 load " + loadMs + " ms (x86 host, 4 threads)\n");
        for (boolean fast : new boolean[]{false, true}) for (String set : new String[]{"piper", "noisy", "espeak"}) {
            List<String> hyp = new ArrayList<>(); double audio = 0, ms = 0;
            for (int i = 1; i <= ref.size(); i++) {
                short[] s = wav(String.format("%s/wav/%s_%02d.wav", VDIR, set, i)); audio += s.length / 16000.0;
                long t = System.nanoTime(); String h = VoiceText.clean(WhisperBridge.nativeTranscribe(toF(s), s.length, null, fast)); ms += (System.nanoTime() - t) / 1e6;
                hyp.add(h); if (System.getProperty("aik.vdebug") != null) System.out.println(set + fast + " " + i + ": " + h);
                if (set.equals("piper") && !fast && i <= 3) rep.append("  ").append(ref.get(i - 1)).append("  ->  ").append(h).append('\n');
            }
            double wer = VoiceText.wer(ref.subList(0, hyp.size()), hyp);
            rep.append(String.format("  %-6s %-6s WER %5.1f%%  RTF %.3f  (%d clips, %.0f s audio, %.0f ms/clip)%n", fast ? "fast" : "final", set, wer, ms / 1000 / audio, hyp.size(), audio, ms / hyp.size()));
            if (!set.equals("espeak")) assertTrue(set + " WER " + wer, wer < 8);
            else assertTrue("espeak WER " + wer, wer < 20); // robotic formant voice, hardest case
        }
        System.out.println(rep);
        Files.write(new File(VDIR + "/whisper_report.txt").toPath(), rep.toString().getBytes());
    }
}
