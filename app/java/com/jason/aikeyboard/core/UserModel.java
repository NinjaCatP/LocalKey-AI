package com.jason.aikeyboard.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * v3 personal learning model. Everything lives in the app's private storage (or a file the user exports on purpose).
 *  - unigrams / bigrams / trigrams with recency+frequency decay and size caps
 *  - autocorrect reverts ("don't correct this word") and accept/ignore feedback on suggestions
 *  - per-user key-slip (typo) model used by the spatial autocorrect
 *  - small capped buffer of recent phrases used as style context for the on-device AI
 * Pure Java (no Android), so it is unit-testable on the JVM.
 */
public final class UserModel implements Dictionary.SubCost {
    /** Decay half-life measured in learned words (not wall time) so idle periods don't erase learning. */
    public static final double HALF_LIFE = 20000;
    public static final int MAX_UNI = 20000, MAX_BI = 60000, MAX_TRI = 60000, MAX_PHRASES = 60, MAX_PHRASE_CHARS = 140;
    public static final int REVERTS_TO_STOP = 2;

    static final class Cnt { double c; long last; Cnt(double c, long last) { this.c = c; this.last = last; } }

    private final HashMap<String, Cnt> uni = new HashMap<>();
    private final HashMap<String, HashMap<String, Cnt>> bi = new HashMap<>();
    private final HashMap<String, HashMap<String, Cnt>> tri = new HashMap<>(); // key "w1 w2"
    private int biSize, triSize;
    private final HashMap<String, String> displayForm = new HashMap<>();
    private final HashMap<String, Integer> reverts = new HashMap<>();
    private final HashMap<String, int[]> feedback = new HashMap<>(); // word -> {accepted, ignored}
    private final HashMap<String, Cnt> slips = new HashMap<>();     // "typed>intended" -> count
    private final ArrayDeque<String> phrases = new ArrayDeque<>();
    private final StringBuilder curPhrase = new StringBuilder();
    private long tick;
    private boolean dirty;

    private double w(Cnt x) { return x == null ? 0 : x.c * Math.pow(0.5, (tick - x.last) / HALF_LIFE); }
    private void bump(Cnt x, double by) { x.c = w(x) + by; x.last = tick; }
    private static String n(String s) { return s == null ? null : Dictionary.norm(s); }

    // ---------------------------------------------------------------- learning
    public synchronized void learn(String prev, String word) { learn(null, prev, word); }
    public synchronized void learn(String prev2, String prev, String word) {
        if (word == null || word.isEmpty() || word.length() > 32) return;
        String k = n(word); if (k.isEmpty()) return;
        tick++;
        bump(uni.computeIfAbsent(k, x -> new Cnt(0, tick)), 1);
        if (!word.toLowerCase(Locale.ROOT).equals(word) || word.contains("'")) displayForm.put(k, word); else displayForm.putIfAbsent(k, word);
        if (prev != null && !prev.isEmpty()) {
            String p = n(prev);
            HashMap<String, Cnt> m = bi.computeIfAbsent(p, x -> new HashMap<>());
            Cnt c = m.get(k); if (c == null) { m.put(k, c = new Cnt(0, tick)); biSize++; } bump(c, 1);
            if (prev2 != null && !prev2.isEmpty()) {
                HashMap<String, Cnt> t = tri.computeIfAbsent(n(prev2) + " " + p, x -> new HashMap<>());
                Cnt d = t.get(k); if (d == null) { t.put(k, d = new Cnt(0, tick)); triSize++; } bump(d, 1);
            }
        }
        if (curPhrase.length() < MAX_PHRASE_CHARS) { if (curPhrase.length() > 0) curPhrase.append(' '); curPhrase.append(word); }
        dirty = true;
        if (uni.size() > MAX_UNI || biSize > MAX_BI || triSize > MAX_TRI) prune();
    }
    /** v6: undo one learn(prev2, prev, word) (e.g. a swiped word that was replaced or deleted right away). */
    public synchronized void unlearn(String prev2, String prev, String word) {
        if (word == null || word.isEmpty()) return;
        String k = n(word); if (k.isEmpty()) return;
        Cnt u = uni.get(k); if (u != null) { u.c = w(u) - 1; u.last = tick; if (u.c <= 0.01) { uni.remove(k); displayForm.remove(k); } }
        if (prev != null && !prev.isEmpty()) {
            String p = n(prev); HashMap<String, Cnt> m = bi.get(p);
            if (m != null) { Cnt c = m.get(k); if (c != null) { c.c = w(c) - 1; c.last = tick; if (c.c <= 0.01) { m.remove(k); biSize--; if (m.isEmpty()) bi.remove(p); } } }
            if (prev2 != null && !prev2.isEmpty()) {
                String tk = n(prev2) + " " + p; HashMap<String, Cnt> t = tri.get(tk);
                if (t != null) { Cnt d = t.get(k); if (d != null) { d.c = w(d) - 1; d.last = tick; if (d.c <= 0.01) { t.remove(k); triSize--; if (t.isEmpty()) tri.remove(tk); } } }
            }
        }
        String cp = curPhrase.toString();
        if (cp.equals(word)) curPhrase.setLength(0); else if (cp.endsWith(" " + word)) curPhrase.setLength(cp.length() - word.length() - 1);
        dirty = true;
    }
    /** Sentence finished (. ! ? enter, or field closed): keep it as a recent phrase if it has a few words. */
    public synchronized void endPhrase() {
        String p = curPhrase.toString().trim(); curPhrase.setLength(0);
        if (p.split(" ").length < 3) return;
        phrases.remove(p); phrases.addLast(p);
        while (phrases.size() > MAX_PHRASES) phrases.removeFirst();
        dirty = true;
    }
    /** Drop the in-progress phrase without saving it (e.g. learning got switched off mid-sentence). */
    public synchronized void discardPhrase() { curPhrase.setLength(0); }

    private void prune() {
        pruneMap(uni, MAX_UNI * 8 / 10);
        biSize = pruneNested(bi, MAX_BI * 8 / 10);
        triSize = pruneNested(tri, MAX_TRI * 8 / 10);
    }
    private void pruneMap(HashMap<String, Cnt> m, int keep) {
        if (m.size() <= keep) return;
        List<Map.Entry<String, Cnt>> es = new ArrayList<>(m.entrySet());
        es.sort(Comparator.comparingDouble(e -> w(e.getValue())));
        for (int i = 0; i < es.size() - keep; i++) { m.remove(es.get(i).getKey()); displayForm.remove(es.get(i).getKey()); }
    }
    private int pruneNested(HashMap<String, HashMap<String, Cnt>> m, int keep) {
        List<double[]> all = new ArrayList<>(); int total = 0;
        for (HashMap<String, Cnt> v : m.values()) for (Cnt c : v.values()) { all.add(new double[]{w(c)}); total++; }
        if (total <= keep) return total;
        double[] ws = new double[total]; for (int i = 0; i < total; i++) ws[i] = all.get(i)[0]; Arrays.sort(ws);
        double cut = ws[total - keep];
        int left = 0;
        for (Iterator<HashMap<String, Cnt>> it = m.values().iterator(); it.hasNext(); ) {
            HashMap<String, Cnt> v = it.next();
            v.values().removeIf(c -> w(c) < cut); left += v.size();
            if (v.isEmpty()) it.remove();
        }
        return left;
    }

    // ---------------------------------------------------------------- autocorrect adaptation
    /** The user undid (backspace) or rejected an autocorrect typed -> corrected. */
    public synchronized void autocorrectReverted(String typed, String corrected) {
        String k = n(typed); if (k == null || k.isEmpty()) return;
        reverts.merge(k, 1, Integer::sum);
        // the slip we recorded when the correction was applied was wrong
        if (corrected != null) recordSlips(typed, corrected, -1);
        tick++; bump(uni.computeIfAbsent(k, x -> new Cnt(0, tick)), 1); // into the personal dictionary
        displayForm.putIfAbsent(k, typed);
        dirty = true;
    }
    /** An autocorrect / correction suggestion was kept: learn which keys this user slips on. */
    public synchronized void correctionAccepted(String typed, String corrected) { recordSlips(typed, corrected, 1); }
    public synchronized boolean neverCorrect(String typed) { Integer r = reverts.get(n(typed)); return r != null && r >= REVERTS_TO_STOP; }
    public synchronized int revertCount(String typed) { Integer r = reverts.get(n(typed)); return r == null ? 0 : r; }

    private void recordSlips(String typed, String corrected, int sign) {
        String a = n(typed), b = n(corrected); if (a == null || b == null || a.length() != b.length()) return;
        List<String> diff = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) if (a.charAt(i) != b.charAt(i)) diff.add(a.charAt(i) + ">" + b.charAt(i));
        if (diff.isEmpty() || diff.size() > 2) return;
        for (String d : diff) {
            Cnt c = slips.computeIfAbsent(d, x -> new Cnt(0, tick)); bump(c, sign);
            if (c.c <= 0) slips.remove(d);
        }
        dirty = true;
    }
    public synchronized double slipCount(char typed, char intended) { return w(slips.get(typed + ">" + intended)); }
    /** Dictionary.SubCost: lower the substitution cost for key pairs this user often confuses (never below 0.25). */
    @Override public synchronized double subCost(char typed, char intended, double base) {
        Cnt c = slips.get(typed + ">" + intended); if (c == null) return base;
        double k = w(c); if (k < 1) return base;
        return Math.max(0.25, base - 0.12 * Math.min(k, 5));
    }

    // ---------------------------------------------------------------- suggestion feedback
    /** A strip of suggestions was shown and then `chosen` was entered (null = none); tapped = picked from the strip. */
    public synchronized void feedback(List<String> shown, String chosen, boolean tapped) {
        String ck = n(chosen);
        if (shown != null) for (String s : shown) {
            String k = n(s); if (k.isEmpty()) continue;
            if (k.equals(ck)) { if (tapped) feedback.computeIfAbsent(k, x -> new int[2])[0]++; }
            else { int[] f = feedback.computeIfAbsent(k, x -> new int[2]); if (f[1] < 1000) f[1]++; }
        }
        dirty = true;
    }
    /** Score adjustment for ranking: boost tapped words, gently demote words repeatedly shown and passed over. */
    public synchronized double feedbackBoost(String word) {
        int[] f = feedback.get(n(word)); if (f == null) return 0;
        double boost = 3.0 * Math.log1p(f[0]);
        double ignoredBeyond = f[1] - 4.0 * f[0];
        double demote = ignoredBeyond > 3 ? Math.min(6, 1.2 * Math.log1p(ignoredBeyond - 3)) : 0;
        return boost - demote;
    }

    // ---------------------------------------------------------------- queries
    public synchronized int count(String w) { return (int) Math.round(w(uni.get(n(w)))); }
    public synchronized double score(String w) { return w(uni.get(n(w))); }
    public synchronized String display(String key) { return displayForm.get(key); }

    /** Personal next-word distribution: blended trigram/bigram relative frequencies (only words seen >= 2 times in context). */
    public synchronized LinkedHashMap<String, Double> nextScores(String prev2, String prev, int k) {
        HashMap<String, Double> p = new HashMap<>();
        if (prev == null) return new LinkedHashMap<>();
        HashMap<String, Cnt> b = bi.get(n(prev));
        HashMap<String, Cnt> t = prev2 == null ? null : tri.get(n(prev2) + " " + n(prev));
        double tw = 0; if (t != null) for (Cnt c : t.values()) tw += w(c);
        double bw = 0; if (b != null) for (Cnt c : b.values()) bw += w(c);
        double lt = t != null && tw >= 2 ? 0.65 : 0;
        if (t != null && tw > 0) for (Map.Entry<String, Cnt> e : t.entrySet()) { double c = w(e.getValue()); if (c >= 1.5) p.merge(e.getKey(), lt * c / tw, Double::sum); }
        if (b != null && bw > 0) for (Map.Entry<String, Cnt> e : b.entrySet()) { double c = w(e.getValue()); if (c >= 1.5) p.merge(e.getKey(), (1 - lt) * c / bw, Double::sum); }
        List<Map.Entry<String, Double>> es = new ArrayList<>(p.entrySet());
        es.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        LinkedHashMap<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : es) { out.put(e.getKey(), e.getValue()); if (out.size() >= k) break; }
        return out;
    }
    /** v5: words you've used at least `min` times (decayed), for swipe decoding of personal words. */
    public synchronized List<String> learnedWords(double min) { List<String> o = new ArrayList<>(); for (Map.Entry<String, Cnt> e : uni.entrySet()) if (w(e.getValue()) >= min) o.add(e.getKey()); return o; }
    public synchronized List<String> next(String prev, int k) { return new ArrayList<>(nextScores(null, prev, k).keySet()); }
    public synchronized List<String> complete(String prefix, int k) {
        String p = n(prefix); List<Map.Entry<String, Cnt>> es = new ArrayList<>();
        for (Map.Entry<String, Cnt> e : uni.entrySet()) if (e.getKey().startsWith(p) && w(e.getValue()) >= 1.5) es.add(e);
        es.sort((a, b) -> Double.compare(w(b.getValue()), w(a.getValue())));
        List<String> out = new ArrayList<>(); for (int i = 0; i < es.size() && i < k; i++) out.add(es.get(i).getKey()); return out;
    }

    /**
     * Short personal style context for the LLM: up to `max` recent phrases that share words with the text being typed
     * (most recent first among ties), capped at maxChars. Returns "" when nothing relevant.
     */
    public synchronized String promptContext(String before, int max, int maxChars) {
        if (phrases.isEmpty()) return "";
        Set<String> ctx = new HashSet<>();
        String[] toks = before.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+");
        for (int i = Math.max(0, toks.length - 6); i < toks.length; i++) if (toks[i].length() >= 2) ctx.add(n(toks[i]));
        List<String> ph = new ArrayList<>(phrases); Collections.reverse(ph); // newest first
        List<Object[]> scored = new ArrayList<>(); int idx = 0;
        for (String s : ph) {
            int ov = 0; for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}']+")) if (ctx.contains(n(t))) ov++;
            if (ov > 0) scored.add(new Object[]{s, ov * 10 - idx * 0.1});
            idx++;
        }
        scored.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
        StringBuilder sb = new StringBuilder();
        for (Object[] o : scored) {
            String s = (String) o[0];
            if (sb.length() + s.length() + 1 > maxChars) continue;
            sb.append(s).append('\n'); if (--max <= 0) break;
        }
        return sb.toString();
    }
    public synchronized List<String> phrases() { return new ArrayList<>(phrases); }

    // ---------------------------------------------------------------- stats / clear
    public synchronized int size() { return uni.size(); }
    public synchronized int pairCount() { return biSize + triSize; }
    public synchronized int phraseCount() { return phrases.size(); }
    public synchronized int noCorrectCount() { int c = 0; for (int v : reverts.values()) if (v >= REVERTS_TO_STOP) c++; return c; }
    public synchronized int slipPatterns() { int c = 0; for (Cnt x : slips.values()) if (w(x) >= 1) c++; return c; }
    /** Words learned that aren't in the bundled dictionary (seen at least twice). */
    public synchronized int newWords(Dictionary d) { int c = 0; for (Map.Entry<String, Cnt> e : uni.entrySet()) if (w(e.getValue()) >= 1.5 && (d == null || !d.contains(e.getKey()))) c++; return c; }
    public synchronized void clear() {
        uni.clear(); bi.clear(); tri.clear(); biSize = triSize = 0; displayForm.clear(); reverts.clear(); feedback.clear(); slips.clear();
        phrases.clear(); curPhrase.setLength(0); tick = 0; dirty = true;
    }
    public synchronized boolean isDirty() { return dirty; }

    // ---------------------------------------------------------------- persistence
    /** Phrase-file transform (the app uses Android Keystore AES-GCM; tests use identity). */
    public interface Codec { byte[] encode(byte[] plain) throws Exception; byte[] decode(byte[] stored) throws Exception; }
    public static final Codec PLAIN = new Codec() { public byte[] encode(byte[] p) { return p; } public byte[] decode(byte[] s) { return s; } };

    /** Atomic save of the n-gram/feedback file and (separately, via codec) the phrase file. No-op when unchanged. */
    public synchronized void save(File f) throws IOException { save(f, null, PLAIN); }
    public synchronized void save(File f, File phraseFile, Codec codec) throws IOException {
        if (!dirty) return;
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8), 1 << 16)) { writeModel(w); }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        if (phraseFile != null) {
            StringBuilder sb = new StringBuilder(); for (String p : phrases) sb.append(p.replace('\n', ' ')).append('\n');
            try {
                byte[] enc = codec.encode(sb.toString().getBytes(StandardCharsets.UTF_8));
                File pt = new File(phraseFile.getPath() + ".tmp");
                try (OutputStream o = new FileOutputStream(pt)) { o.write(enc); }
                if (!pt.renameTo(phraseFile)) throw new IOException("rename failed");
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("phrase encode failed", e); }
        }
        dirty = false;
    }
    private void writeModel(Writer w) throws IOException {
        w.write("V\t3\t" + tick + "\n");
        for (Map.Entry<String, Cnt> e : uni.entrySet()) w.write("U\t" + e.getKey() + "\t" + fmt(e.getValue().c) + "\t" + displayForm.getOrDefault(e.getKey(), e.getKey()) + "\t" + e.getValue().last + "\n");
        for (Map.Entry<String, HashMap<String, Cnt>> e : bi.entrySet()) for (Map.Entry<String, Cnt> e2 : e.getValue().entrySet())
            w.write("B\t" + e.getKey() + "\t" + e2.getKey() + "\t" + fmt(e2.getValue().c) + "\t" + e2.getValue().last + "\n");
        for (Map.Entry<String, HashMap<String, Cnt>> e : tri.entrySet()) for (Map.Entry<String, Cnt> e2 : e.getValue().entrySet())
            w.write("T\t" + e.getKey() + "\t" + e2.getKey() + "\t" + fmt(e2.getValue().c) + "\t" + e2.getValue().last + "\n");
        for (Map.Entry<String, Integer> e : reverts.entrySet()) w.write("R\t" + e.getKey() + "\t" + e.getValue() + "\n");
        for (Map.Entry<String, int[]> e : feedback.entrySet()) w.write("F\t" + e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
        for (Map.Entry<String, Cnt> e : slips.entrySet()) w.write("K\t" + e.getKey() + "\t" + fmt(e.getValue().c) + "\t" + e.getValue().last + "\n");
    }
    private static String fmt(double d) { return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.3f", d); }

    public synchronized void load(File f) { load(f, null, PLAIN); }
    public synchronized void load(File f, File phraseFile, Codec codec) {
        if (f.exists()) try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) { read(r, false); } catch (IOException ignored) {}
        if (phraseFile != null && phraseFile.exists()) {
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(phraseFile.toPath());
                String s = new String(codec.decode(raw), StandardCharsets.UTF_8);
                for (String p : s.split("\n")) if (!p.trim().isEmpty()) phrases.addLast(p.trim());
                while (phrases.size() > MAX_PHRASES) phrases.removeFirst();
            } catch (Exception ignored) { /* key lost (e.g. restored device): start fresh */ }
        }
        dirty = false;
    }

    /** Export everything (incl. phrases, in plain text) to a user-chosen file. */
    public synchronized void exportTo(Writer w) throws IOException {
        w.write("# AI Keyboard learned data v3. Contains words and phrases you typed; keep it private.\n");
        writeModel(w);
        for (String p : phrases) w.write("P\t" + p.replace('\t', ' ').replace('\n', ' ') + "\n");
        w.flush();
    }
    /** Import (merge) a previously exported file. Returns number of lines applied. */
    public synchronized int importFrom(Reader r) throws IOException { int n = read(r, true); dirty = true; if (uni.size() > MAX_UNI || biSize > MAX_BI || triSize > MAX_TRI) prune(); return n; }

    private int read(Reader rd, boolean merge) throws IOException {
        BufferedReader r = new BufferedReader(rd); String line; int applied = 0; long baseTick = tick;
        // imported/older timestamps are re-based so they decay relative to this model's clock
        long fileTick = 0;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t", -1);
            try {
                switch (p[0]) {
                    case "V": fileTick = Long.parseLong(p[2]); if (!merge) tick = fileTick; break;
                    case "U": if (p.length >= 3) {
                        long last = p.length > 4 ? rebase(Long.parseLong(p[4]), fileTick, merge) : tick;
                        put(uni, p[1], Double.parseDouble(p[2]), last, merge);
                        if (p.length > 3 && !p[3].isEmpty()) displayForm.put(p[1], p[3]); applied++; } break;
                    case "B": if (p.length >= 4) {
                        long last = p.length > 4 ? rebase(Long.parseLong(p[4]), fileTick, merge) : tick;
                        HashMap<String, Cnt> m = bi.computeIfAbsent(p[1], x -> new HashMap<>()); if (!m.containsKey(p[2])) biSize++;
                        put(m, p[2], Double.parseDouble(p[3]), last, merge); applied++; } break;
                    case "T": if (p.length >= 5) {
                        HashMap<String, Cnt> m = tri.computeIfAbsent(p[1], x -> new HashMap<>()); if (!m.containsKey(p[2])) triSize++;
                        put(m, p[2], Double.parseDouble(p[3]), rebase(Long.parseLong(p[4]), fileTick, merge), merge); applied++; } break;
                    case "R": if (merge) reverts.merge(p[1], Integer.parseInt(p[2]), Math::max); else reverts.put(p[1], Integer.parseInt(p[2])); applied++; break;
                    case "F": { int[] f = feedback.computeIfAbsent(p[1], x -> new int[2]); f[0] += Integer.parseInt(p[2]); f[1] += Integer.parseInt(p[3]); applied++; break; }
                    case "K": put(slips, p[1], Double.parseDouble(p[2]), rebase(Long.parseLong(p[3]), fileTick, merge), merge); applied++; break;
                    case "P": if (p.length > 1 && !p[1].trim().isEmpty()) { phrases.remove(p[1]); phrases.addLast(p[1]); while (phrases.size() > MAX_PHRASES) phrases.removeFirst(); applied++; } break;
                    default: break;
                }
            } catch (RuntimeException ignored) {}
        }
        return applied;
    }
    private long rebase(long last, long fileTick, boolean merge) { return merge ? tick - Math.max(0, fileTick - last) : last; }
    private void put(HashMap<String, Cnt> m, String k, double c, long last, boolean merge) {
        Cnt o = m.get(k);
        if (o == null || !merge) m.put(k, new Cnt(c, last));
        else { double nc = w(o) + c * Math.pow(0.5, (tick - last) / HALF_LIFE); o.c = nc; o.last = tick; }
    }
}
