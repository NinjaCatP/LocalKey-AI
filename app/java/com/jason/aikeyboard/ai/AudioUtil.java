package com.jason.aikeyboard.ai;

/** v8: resampling to 16 kHz and level helpers (pure Java). */
public final class AudioUtil {
    private AudioUtil() {}
    /** Resample mono PCM16 from {@code rate} to 16 kHz floats: box low-pass (width = ratio) + linear interpolation. */
    public static float[] to16k(short[] in, int n, int rate) {
        if (rate == 16000) { float[] o = new float[n]; for (int i = 0; i < n; i++) o[i] = in[i] / 32768f; return o; }
        double ratio = rate / 16000.0; int outN = (int) Math.floor(n / ratio);
        float[] o = new float[Math.max(0, outN)]; int w = Math.max(1, (int) Math.round(ratio));
        for (int i = 0; i < outN; i++) {
            double pos = i * ratio; int a = (int) pos; double fr = pos - a;
            float s0 = box(in, n, a, w), s1 = box(in, n, Math.min(n - 1, a + 1), w);
            o[i] = (float) (s0 + (s1 - s0) * fr);
        }
        return o;
    }
    private static float box(short[] in, int n, int c, int w) {
        if (w == 1) return in[c] / 32768f;
        int lo = Math.max(0, c - w / 2), hi = Math.min(n, lo + w); double s = 0; for (int i = lo; i < hi; i++) s += in[i]; return (float) (s / Math.max(1, hi - lo) / 32768.0);
    }
    /** Streaming resampler state for live audio (keeps fractional position between blocks). */
    public static final class Stream {
        final int rate; double pos; short last; final double ratio;
        public Stream(int rate) { this.rate = rate; ratio = rate / 16000.0; }
        /** Appends resampled samples of in[0..n) to out starting at outPos; returns new outPos. */
        public int push(short[] in, int n, float[] out, int outPos) {
            if (rate == 16000) { for (int i = 0; i < n && outPos < out.length; i++) out[outPos++] = in[i] / 32768f; return outPos; }
            int w = Math.max(1, (int) Math.round(ratio));
            while (pos < n && outPos < out.length) {
                int a = (int) pos; double fr = pos - a;
                float s0 = box(in, n, a, w), s1 = a + 1 < n ? box(in, n, a + 1, w) : s0;
                out[outPos++] = (float) (s0 + (s1 - s0) * fr); pos += ratio;
            }
            pos -= n; return outPos;
        }
    }
    public static float peak(float[] x, int n) { float p = 0; for (int i = 0; i < n; i++) p = Math.max(p, Math.abs(x[i])); return p; }
    public static double dbfs(double v) { return 20 * Math.log10(Math.max(1e-6, v)); }
    /** Scales quiet recordings up so whisper sees a normal level (peak -> ~0.7, at most 20x). Returns gain used. */
    public static float normalize(float[] x, int n) {
        float p = peak(x, n); if (p <= 0 || p >= 0.25f) return 1f;
        float g = Math.min(20f, 0.7f / p); for (int i = 0; i < n; i++) x[i] *= g; return g;
    }
}
