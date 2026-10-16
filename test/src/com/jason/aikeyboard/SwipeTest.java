package com.jason.aikeyboard;

import static org.junit.Assert.*;
import com.jason.aikeyboard.core.*;
import java.io.FileInputStream;
import java.util.*;
import com.jason.aikeyboard.core.Dictionary;
import org.junit.BeforeClass;
import org.junit.Test;

/** v5: swipe decoder accuracy on synthetic gesture paths (S24 Ultra portrait geometry, 1440 px wide @ 4x density). */
public class SwipeTest {
    static Dictionary d; static SwipeDecoder dec; static Map<Character, float[]> C; static float PW, PH;
    @BeforeClass public static void load() throws Exception {
        d = new Dictionary(); d.load(new FileInputStream("/workspace/aikeyboard/app/assets/words.txt"));
        KeyboardLayout L = new KeyboardLayout(KeyboardLayout.Layer.LETTERS);
        float dens = 4; L.layout(1440 - 2 * 2 * dens, 4 * 56 * dens, 3 * dens);
        C = L.letterCentres(); float[] p = L.letterPitch(); PW = p[0]; PH = p[1];
        dec = new SwipeDecoder(d, new UserModel());
        long t = System.nanoTime(); dec.setKeys(C, PW, PH); System.out.println("swipe templates built in " + (System.nanoTime() - t) / 1000000 + " ms");
    }
    /** Simulated finger: per-key aim error (sigma in key pitches), corner rounding, sensor jitter, ~8px sampling. */
    static float[][] path(String w, double sigma, Random r) {
        List<float[]> pts = new ArrayList<>(); char last = 0;
        for (char c : w.toCharArray()) { if (c == last) continue; last = c; float[] k = C.get(c); pts.add(new float[]{(float) (k[0] + r.nextGaussian() * sigma * PW), (float) (k[1] + r.nextGaussian() * sigma * PH)}); }
        if (pts.size() == 1) { float[] a = pts.get(0); pts.add(new float[]{a[0] + 3, a[1] + 2}); }
        List<float[]> dense = new ArrayList<>();
        for (int i = 1; i < pts.size(); i++) { float[] a = pts.get(i - 1), b = pts.get(i); int steps = Math.max(2, (int) (Math.hypot(b[0] - a[0], b[1] - a[1]) / 8));
            for (int s = 0; s < steps; s++) { float t = s / (float) steps; dense.add(new float[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])}); } }
        dense.add(pts.get(pts.size() - 1));
        int n = dense.size(); float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) { // corner rounding: moving average over +-3 samples (endpoints kept)
            float sx = 0, sy = 0; int c = 0; for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) { sx += dense.get(j)[0]; sy += dense.get(j)[1]; c++; }
            x[i] = (i == 0 || i == n - 1 ? dense.get(i)[0] : sx / c) + (float) r.nextGaussian() * 3; y[i] = (i == 0 || i == n - 1 ? dense.get(i)[1] : sy / c) + (float) r.nextGaussian() * 3;
        }
        return new float[][]{x, y};
    }
    static List<String> commonWords(int k) {
        String[] ws = d.wordsArray().clone(); Arrays.sort(ws, Comparator.comparingInt(d::rankOf));
        List<String> o = new ArrayList<>(); for (String w : ws) { if (w.length() >= 2 && w.matches("[a-z]+")) o.add(w); if (o.size() >= k) break; } return o;
    }
    static double[] eval(List<String> words, double sigma, long seed) {
        Random r = new Random(seed); int t1 = 0, t3 = 0, n = 0; long[] times = new long[words.size()];
        for (String w : words) {
            float[][] p = path(w, sigma, r);
            List<SwipeDecoder.Candidate> c = dec.decode(p[0], p[1], p[0].length, null, null, 5);
            times[n] = dec.lastDecodeMicros; n++;
            if (!c.isEmpty() && c.get(0).word.equals(w)) t1++;
            for (int i = 0; i < Math.min(3, c.size()); i++) if (c.get(i).word.equals(w)) { t3++; break; }
        }
        Arrays.sort(times); long sum = 0; for (long t : times) sum += t;
        return new double[]{100.0 * t1 / n, 100.0 * t3 / n, sum / (double) n / 1000.0, times[(int) (n * 0.95)] / 1000.0};
    }
    @Test public void accuracyOnCommonWords() {
        List<String> words = commonWords(500);
        for (int i = 0; i < 2; i++) eval(words.subList(0, 100), 0.2, 99); // JIT warm-up
        double[] clean = eval(words, 0.20, 1), noisy = eval(words, 0.35, 2);
        System.out.printf(Locale.ROOT, "SWIPE 500 common words, sigma 0.20 key: top1 %.1f%% top3 %.1f%% | mean %.2f ms p95 %.2f ms%n", clean[0], clean[1], clean[2], clean[3]);
        System.out.printf(Locale.ROOT, "SWIPE 500 common words, sigma 0.35 key: top1 %.1f%% top3 %.1f%% | mean %.2f ms p95 %.2f ms%n", noisy[0], noisy[1], noisy[2], noisy[3]);
        List<String> mid = commonWords(3000).subList(2000, 3000);
        double[] m = eval(mid, 0.20, 3);
        System.out.printf(Locale.ROOT, "SWIPE words ranked 2000-3000, sigma 0.20 key: top1 %.1f%% top3 %.1f%% | mean %.2f ms p95 %.2f ms%n", m[0], m[1], m[2], m[3]);
        assertTrue("top3 " + clean[1], clean[1] >= 85);
        assertTrue("p95 ms " + clean[3], clean[3] < 50);
    }
    @Test public void exactPathsDecode() {
        for (String w : new String[]{"hello", "the", "you", "thanks", "tomorrow", "keyboard", "because", "what", "good", "people"}) {
            float[][] p = path(w, 0, new Random(5));
            List<SwipeDecoder.Candidate> c = dec.decode(p[0], p[1], p[0].length, null, null, 3);
            boolean in = false; for (SwipeDecoder.Candidate x : c) in |= x.word.equals(w);
            assertTrue(w + " -> " + c, in);
        }
    }
    @Test public void personalLearningChangesRanking() {
        UserModel u = new UserModel(); SwipeDecoder sd = new SwipeDecoder(d, u); sd.setKeys(C, PW, PH);
        // a personal word the dictionary lacks becomes swipeable after using it
        String w = "grokbot"; assertFalse(d.contains(w));
        for (int i = 0; i < 4; i++) u.learn(null, w);
        float[][] p = path(w, 0.1, new Random(7));
        List<SwipeDecoder.Candidate> c = sd.decode(p[0], p[1], p[0].length, null, null, 3);
        assertEquals(w, c.get(0).word);
        // personal next-word habit boosts an ambiguous path
        for (int i = 0; i < 6; i++) u.learn("see", "you");
        float[][] q = path("you", 0.15, new Random(8));
        assertEquals("you", sd.decode(q[0], q[1], q[0].length, null, "see", 3).get(0).word);
    }
}
