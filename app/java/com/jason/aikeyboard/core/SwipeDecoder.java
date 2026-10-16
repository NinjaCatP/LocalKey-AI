package com.jason.aikeyboard.core;

import java.util.*;

/**
 * v5: gesture ("glide") typing decoder, SHARK2-style. Pure Java so it is unit tested on the JVM.
 * Each dictionary word gets a template = polyline through its key centres, resampled to N points.
 * A gesture is resampled the same way and compared with candidates whose first/last keys are near the
 * gesture start/end, using a location channel (mean point distance in key widths, plus a penalty for word
 * keys the finger never came near) and a shape channel (scale/translation-normalised distance).
 * The geometric likelihood is combined with a frequency prior, your personal word counts and your
 * personal next-word model (UserModel).
 */
public final class SwipeDecoder {
    public static final int N = 32;
    public static final class Candidate {
        public final String word; public final double score; public final double geo;
        Candidate(String w, double s, double g) { word = w; score = s; geo = g; }
        public String toString() { return word + String.format(Locale.ROOT, ":%.2f", score); }
    }
    private final Dictionary dict; private final UserModel user;
    private final float[] kx = new float[26], ky = new float[26]; private float keyW = 1, keyH = 1; private boolean hasKeys;
    // templates for dictionary words, bucketed by (first,last) letter
    private String[] words; private double[] logPrior; private float[][] tx, ty; private int[][] bucket = new int[26 * 26][];
    private final HashMap<String, float[][]> extra = new HashMap<>(); // personal words not in dictionary
    private long geomSig = -1;
    private final double[] dpA = new double[64], dpB = new double[64];
    public long lastDecodeMicros; public static boolean DEBUG;

    public SwipeDecoder(Dictionary d, UserModel u) { dict = d; user = u; }

    /** Set key centres (pixels) for 'a'..'z' and the key size. Rebuilds templates only when the geometry changes. */
    public synchronized void setKeys(Map<Character, float[]> centres, float keyWidth, float keyHeight) {
        long sig = 17; for (char c = 'a'; c <= 'z'; c++) { float[] p = centres.get(c); if (p == null) { hasKeys = false; return; } sig = sig * 31 + Float.floatToIntBits(p[0]) * 7 + Float.floatToIntBits(p[1]); }
        sig = sig * 31 + Float.floatToIntBits(keyWidth);
        if (sig == geomSig && words != null) return;
        for (char c = 'a'; c <= 'z'; c++) { float[] p = centres.get(c); kx[c - 'a'] = p[0]; ky[c - 'a'] = p[1]; }
        keyW = keyWidth; keyH = keyHeight; hasKeys = true; geomSig = sig; build(); extra.clear();
    }
    public boolean ready() { return hasKeys && words != null; }

    private static boolean letters(String w) { if (w.length() < 2) return false; for (int i = 0; i < w.length(); i++) { char c = w.charAt(i); if (c < 'a' || c > 'z') return false; } return true; }
    private void build() {
        String[] all = dict.wordsArray(); long[] fq = dict.freqArray();
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < all.length; i++) if (letters(all[i])) keep.add(i);
        int n = keep.size(); words = new String[n]; logPrior = new double[n]; tx = new float[n][]; ty = new float[n][];
        int[] counts = new int[26 * 26];
        for (int j = 0; j < n; j++) {
            int i = keep.get(j); String w = all[i]; words[j] = w; logPrior[j] = Math.log(fq[i] + 1.0);
            float[][] t = template(w); tx[j] = t[0]; ty[j] = t[1];
            counts[(w.charAt(0) - 'a') * 26 + (w.charAt(w.length() - 1) - 'a')]++;
        }
        bucket = new int[26 * 26][]; int[] fill = new int[26 * 26];
        for (int b = 0; b < bucket.length; b++) bucket[b] = new int[counts[b]];
        for (int j = 0; j < n; j++) { String w = words[j]; int b = (w.charAt(0) - 'a') * 26 + (w.charAt(w.length() - 1) - 'a'); bucket[b][fill[b]++] = j; }
    }
    /** Ideal path through the word's key centres (repeated letters collapse), resampled to N points. */
    float[][] template(String w) {
        float[] px = new float[w.length()], py = new float[w.length()]; int m = 0; char last = 0;
        for (int i = 0; i < w.length(); i++) { char c = w.charAt(i); if (c == last) continue; last = c; px[m] = kx[c - 'a']; py[m] = ky[c - 'a']; m++; }
        return resample(px, py, m, N);
    }
    public static float[][] resample(float[] xs, float[] ys, int n, int out) {
        float[] rx = new float[out], ry = new float[out];
        if (n <= 0) return new float[][]{rx, ry};
        double total = 0; for (int i = 1; i < n; i++) total += Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        if (total < 1e-6) { Arrays.fill(rx, xs[0]); Arrays.fill(ry, ys[0]); return new float[][]{rx, ry}; }
        double step = total / (out - 1), acc = 0; int seg = 1; double segStartAcc = 0;
        rx[0] = xs[0]; ry[0] = ys[0];
        for (int k = 1; k < out - 1; k++) {
            double target = k * step;
            while (seg < n) {
                double L = Math.hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1]);
                if (segStartAcc + L >= target && L > 0) { double t = (target - segStartAcc) / L; rx[k] = (float) (xs[seg - 1] + t * (xs[seg] - xs[seg - 1])); ry[k] = (float) (ys[seg - 1] + t * (ys[seg] - ys[seg - 1])); break; }
                segStartAcc += L; seg++;
            }
            if (seg >= n) { rx[k] = xs[n - 1]; ry[k] = ys[n - 1]; }
        }
        rx[out - 1] = xs[n - 1]; ry[out - 1] = ys[n - 1];
        return new float[][]{rx, ry};
    }
    private static float[][] normShape(float[] x, float[] y) {
        float minx = Float.MAX_VALUE, maxx = -Float.MAX_VALUE, miny = Float.MAX_VALUE, maxy = -Float.MAX_VALUE, cx = 0, cy = 0;
        for (int i = 0; i < x.length; i++) { minx = Math.min(minx, x[i]); maxx = Math.max(maxx, x[i]); miny = Math.min(miny, y[i]); maxy = Math.max(maxy, y[i]); cx += x[i]; cy += y[i]; }
        cx /= x.length; cy /= y.length; float s = Math.max(maxx - minx, maxy - miny); if (s < 1e-3f) s = 1;
        float[] nx = new float[x.length], ny = new float[y.length];
        for (int i = 0; i < x.length; i++) { nx[i] = (x[i] - cx) / s; ny[i] = (y[i] - cy) / s; }
        return new float[][]{nx, ny};
    }

    /**
     * Decode a gesture (raw touch points, pixels) into up to k ranked words (normalized dictionary keys).
     * prev2/prev are the preceding words (for the personal next-word model), may be null.
     */
    public synchronized List<Candidate> decode(float[] xs, float[] ys, int n, String prev2, String prev, int k) {
        long t0 = System.nanoTime();
        List<Candidate> out = new ArrayList<>();
        if (!ready() || n < 2) return out;
        float[][] g = resample(xs, ys, n, N); float[] gx = g[0], gy = g[1];
        double glen = 0; for (int i = 1; i < N; i++) glen += Math.hypot(gx[i] - gx[i - 1], gy[i] - gy[i - 1]);
        float[][] gs = normShape(gx, gy);
        float[][] r64 = resample(xs, ys, n, 64); xs = r64[0]; ys = r64[1]; n = 64;
        // start/end key candidates: keys within ~1.1 key widths of the first/last point (vertical distance scaled to key width)
        List<Integer> starts = nearKeys(gx[0], gy[0]), ends = nearKeys(gx[N - 1], gy[N - 1]);
        LinkedHashMap<String, Double> pers = user == null ? new LinkedHashMap<>() : user.nextScores(prev2, prev, 30);
        // keep top candidates in a bounded heap by score
        PriorityQueue<Candidate> heap = new PriorityQueue<>(Comparator.comparingDouble(c -> c.score));
        int keepN = Math.max(k, 20);
        float sx = gx[0], sy = gy[0], ex = gx[N - 1], ey = gy[N - 1];
        for (int s : starts) for (int e : ends) {
            int[] b = bucket[s * 26 + e]; if (b == null) continue;
            for (int j : b) {
                double sc = scoreTemplate(tx[j], ty[j], gx, gy, gs, xs, ys, n, words[j], glen);
                if (Double.isNaN(sc)) continue;
                sc += prior(words[j], logPrior[j], pers);
                offer(heap, keepN, new Candidate(words[j], sc, 0));
            }
        }
        if (user != null) for (String w : user.learnedWords(1.5)) { // personal words the dictionary lacks
            if (!letters(w) || dict.contains(w)) continue;
            int s = w.charAt(0) - 'a', e = w.charAt(w.length() - 1) - 'a'; if (!starts.contains(s) || !ends.contains(e)) continue;
            float[][] t = extra.computeIfAbsent(w, this::template);
            double sc = scoreTemplate(t[0], t[1], gx, gy, gs, xs, ys, n, w, glen);
            if (Double.isNaN(sc)) continue;
            offer(heap, keepN, new Candidate(w, sc + prior(w, Math.log(50), pers), 0));
        }
        out.addAll(heap); out.sort((a, b2) -> Double.compare(b2.score, a.score));
        while (out.size() > k) out.remove(out.size() - 1);
        lastDecodeMicros = (System.nanoTime() - t0) / 1000;
        return out;
    }
    private static void offer(PriorityQueue<Candidate> h, int cap, Candidate c) { if (h.size() < cap) h.add(c); else if (c.score > h.peek().score) { h.poll(); h.add(c); } }
    private double prior(String w, double logFreq, Map<String, Double> pers) {
        double p = 0.55 * logFreq;
        if (user != null) { double u = user.score(w); if (u > 0) p += 1.2 * Math.log1p(u); Double ps = pers.get(w); if (ps != null) p += 4 * ps; p += 0.3 * user.feedbackBoost(w); }
        return p;
    }
    private List<Integer> nearKeys(float x, float y) {
        List<Integer> r = new ArrayList<>(); double best = Double.MAX_VALUE; int bi = 0;
        for (int i = 0; i < 26; i++) {
            double dx = (x - kx[i]) / keyW, dy = (y - ky[i]) / keyH, d = Math.hypot(dx, dy);
            if (d < best) { best = d; bi = i; }
            if (d < 1.15) r.add(i);
        }
        if (!r.contains(bi)) r.add(bi);
        return r;
    }
    /** Returns a log-likelihood-ish score (higher is better) or NaN when clearly impossible. */
    private double scoreTemplate(float[] tx, float[] ty, float[] gx, float[] gy, float[][] gs, float[] rawx, float[] rawy, int rn, String w, double glen) {
        // template length vs gesture length: cheap prune
        double tlen = 0; for (int i = 1; i < N; i++) tlen += Math.hypot(tx[i] - tx[i - 1], ty[i] - ty[i - 1]);
        double lr = (tlen + keyW) / (glen + keyW); if (lr > 2.2 || lr < 0.4) return Double.NaN;
        // location channel (cheap early exit on a coarse pass)
        double loc = 0;
        for (int i = 0; i < N; i += 4) loc += Math.hypot((gx[i] - tx[i]) / keyW, (gy[i] - ty[i]) / keyH);
        if (loc / 8 > 2.2) return Double.NaN;
        loc = 0; for (int i = 0; i < N; i++) loc += Math.hypot((gx[i] - tx[i]) / keyW, (gy[i] - ty[i]) / keyH);
        loc /= N;
        // shape channel
        float[][] ts = normShape(tx, ty); double shape = 0;
        for (int i = 0; i < N; i++) shape += Math.hypot(gs[0][i] - ts[0][i], gs[1][i] - ts[1][i]);
        shape /= N;
        // every key of the word should be passed near (in order): penalty for keys the path never approached
        // monotone alignment (DP) of the word's keys to path points: penalty for keys the path never came near, in order
        double[] prev = dpA, cur = dpB; Arrays.fill(prev, 0, rn, 0); char last = 0; boolean first = true;
        for (int c = 0; c < w.length(); c++) {
            char ch = w.charAt(c); if (ch == last) continue; last = ch;
            float cx = kx[ch - 'a'], cy = ky[ch - 'a']; double run = Double.MAX_VALUE;
            for (int j = 0; j < rn; j++) {
                double dd = Math.hypot((rawx[j] - cx) / keyW, (rawy[j] - cy) / keyH), pen = dd > 0.6 ? (dd - 0.6) * (dd - 0.6) * 4 : 0;
                run = Math.min(run, prev[j]); cur[j] = run + pen;
            }
            double[] t = prev; prev = cur; cur = t; first = false;
        }
        double miss = Double.MAX_VALUE; for (int j = 0; j < rn; j++) miss = Math.min(miss, prev[j]);
        if (DEBUG) System.out.println(w + " loc=" + loc + " shape=" + shape + " miss=" + miss + " lr=" + lr);
        return -(loc * loc) / (2 * 0.22) - (shape * shape) / (2 * 0.004) - miss * 3 - 0.08 * Math.abs(Math.log(lr)) * 10;
    }
}
