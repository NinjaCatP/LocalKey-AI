package com.jason.aikeyboard.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Frequency word list with prefix search, display forms (contractions, "I") and typo correction. Pure Java. */
public final class Dictionary {
    private String[] sorted = new String[0];          // normalized keys sorted
    private long[] sortedFreq = new long[0];
    private final HashMap<String, Long> freq = new HashMap<>();
    private final HashMap<String, Integer> rank = new HashMap<>();
    private static final Map<String, String> DISPLAY = new HashMap<>();
    /** Apostrophe-less forms that are not words themselves and can be autocorrected safely. */
    private static final java.util.Set<String> SAFE_DISPLAY = new java.util.HashSet<>();
    static {
        String[] safe = {"dont:don't","im:I'm","i:I","ive:I've","youre:you're","theyre:they're","didnt:didn't","doesnt:doesn't",
            "cant:can't","wont:won't","isnt:isn't","arent:aren't","wasnt:wasn't","werent:weren't","havent:haven't","hasnt:hasn't",
            "hadnt:hadn't","couldnt:couldn't","shouldnt:shouldn't","wouldnt:wouldn't","thats:that's","whats:what's","theres:there's",
            "heres:here's","lets:let's","youve:you've","youll:you'll","youd:you'd","theyve:they've","theyll:they'll","weve:we've",
            "itll:it'll","shes:she's","hes:he's","wheres:where's","whos:who's","hows:how's","aint:ain't","ya'll:y'all","yall:y'all",
            "mustnt:mustn't","neednt:needn't","itd:it'd","thatll:that'll","whatre:what're","idk:idk","ok:ok"};
        for (String s : safe) { String[] p = s.split(":"); DISPLAY.put(p[0], p[1]); SAFE_DISPLAY.add(p[0]); }
        // ambiguous: real words too, only offered as suggestions
        String[] amb = {"ill:I'll","id:I'd","its:it's","well:we'll","were:we're","wed:we'd","hell:he'll","shell:she'll","whatll:what'll"};
        for (String s : amb) { String[] p = s.split(":"); DISPLAY.put(p[0] + "#", p[1]); }
    }

    public void load(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> ws = new ArrayList<>(); String line; int i = 0;
        while ((line = r.readLine()) != null) {
            int t = line.indexOf('\t'); if (t <= 0) continue;
            String w = line.substring(0, t); long f = Long.parseLong(line.substring(t + 1).trim());
            if (!freq.containsKey(w)) { freq.put(w, f); rank.put(w, i++); ws.add(w); }
        }
        String[] a = ws.toArray(new String[0]); Arrays.sort(a);
        sorted = a; sortedFreq = new long[a.length];
        for (int k = 0; k < a.length; k++) sortedFreq[k] = freq.get(a[k]);
        // SymSpell-style index: single-deletion variant -> words (finds edit distance <= 2 quickly)
        deletes.clear();
        for (String w : a) { if (w.length() < 3) continue; for (int k = 0; k < w.length(); k++) { String d = w.substring(0, k) + w.substring(k + 1); deletes.computeIfAbsent(d, x -> new ArrayList<>(2)).add(w); } }
    }
    private final HashMap<String, List<String>> deletes = new HashMap<>();

    public int size() { return sorted.length; }
    /** v5: normalized words (sorted) and their frequencies, for the swipe decoder. Do not modify. */
    public String[] wordsArray() { return sorted; }
    public long[] freqArray() { return sortedFreq; }
    public int rankOf(String w) { Integer r = rank.get(norm(w)); return r == null ? Integer.MAX_VALUE : r; }
    public static String norm(String w) { return w.toLowerCase(java.util.Locale.ROOT).replace("'", "").replace("\u2019", ""); }
    public boolean contains(String w) { return freq.containsKey(norm(w)); }
    public long freq(String w) { Long f = freq.get(norm(w)); return f == null ? 0 : f; }
    /** True if the word is fine as typed (known and doesn't need a safe display fix). */
    public boolean isValidAsTyped(String w) {
        String n = norm(w);
        if (!freq.containsKey(n)) return false;
        if (SAFE_DISPLAY.contains(n) && !w.contains("'") && !w.contains("\u2019") && !n.equals("idk") && !n.equals("ok")) {
            return w.equals(DISPLAY.get(n)); // "I" is valid, "i" is not
        }
        return true;
    }
    /** Preferred spelling for a normalized key ("dont" -> "don't", "i" -> "I"). */
    public static String display(String key) { String d = DISPLAY.get(key); return d == null ? key : d; }
    public static String ambiguousDisplay(String key) { return DISPLAY.get(key + "#"); }

    /** Most frequent words starting with prefix (normalized), best first. */
    public List<String> complete(String prefix, int k) {
        String p = norm(prefix); List<String> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        int lo = Arrays.binarySearch(sorted, p); if (lo < 0) lo = -lo - 1;
        // collect all with prefix, keep top k by freq
        List<int[]> idx = new ArrayList<>();
        for (int i = lo; i < sorted.length && sorted[i].startsWith(p); i++) idx.add(new int[]{i});
        idx.sort((x, y) -> Long.compare(sortedFreq[y[0]], sortedFreq[x[0]]));
        for (int i = 0; i < idx.size() && out.size() < k; i++) out.add(sorted[idx.get(i)[0]]);
        return out;
    }

    // QWERTY neighbours for substitution cost
    private static final String[] ROWS = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    static boolean adjacent(char a, char b) {
        int[] pa = pos(a), pb = pos(b); if (pa == null || pb == null) return false;
        return Math.abs(pa[0] - pb[0]) <= 1 && Math.abs(pa[1] - pb[1]) <= 1;
    }
    private static int[] pos(char c) { for (int r = 0; r < 3; r++) { int i = ROWS[r].indexOf(c); if (i >= 0) return new int[]{r, i + (r == 0 ? 0 : r == 1 ? 0 : 1)}; } return null; }

    /** v3: per-user substitution cost (learned key slips). typed = key the user hit, intended = letter of the candidate. */
    public interface SubCost { double subCost(char typed, char intended, double base); }
    public static final class Correction { public final String word; public final double score; Correction(String w, double s) { word = w; score = s; } public String toString() { return word + ":" + score; } }

    /** Ranked spelling candidates for a typed word (edit distance <=2, keyboard aware). */
    public List<Correction> corrections(String typed, int k) { return corrections(typed, k, null); }
    public List<Correction> corrections(String typed, int k, SubCost sc) {
        String t = norm(typed); List<Correction> out = new ArrayList<>();
        if (t.length() < 2 || t.length() > 24) return out;
        HashMap<String, Double> best = new HashMap<>();
        // candidate generation by edits1 then edits of edits1 (length-limited)
        List<String> e1 = edits(t);
        for (String c : e1) consider(best, t, c, sc);
        if (t.length() >= 3) {
            List<String> mine = new ArrayList<>(); mine.add(t);
            for (int q = 0; q < t.length(); q++) mine.add(t.substring(0, q) + t.substring(q + 1));
            for (String m : mine) { List<String> ws = deletes.get(m); if (ws != null) for (String w : ws) consider(best, t, w, sc); }
        }
        for (Map.Entry<String, Double> e : best.entrySet()) out.add(new Correction(e.getKey(), e.getValue()));
        out.sort((a, b) -> Double.compare(b.score, a.score));
        return out.size() > k ? out.subList(0, k) : out;
    }
    private void consider(HashMap<String, Double> best, String typed, String c, SubCost sc) {
        Long f = freq.get(c); if (f == null || c.equals(typed)) return;
        double cost = weightedDistance(typed, c, sc);
        if (cost > 2.0) return;
        double s = Math.log(f + 1) - 4.0 * cost;
        Double o = best.get(c); if (o == null || s > o) best.put(c, s);
    }
    private static List<String> edits(String w) {
        List<String> r = new ArrayList<>(); String L = "abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i <= w.length(); i++) {
            String a = w.substring(0, i), b = w.substring(i);
            if (!b.isEmpty()) r.add(a + b.substring(1));
            if (b.length() > 1) r.add(a + b.charAt(1) + b.charAt(0) + b.substring(2));
            for (int j = 0; j < 26; j++) { char ch = L.charAt(j); if (!b.isEmpty() && b.charAt(0) != ch) r.add(a + ch + b.substring(1)); r.add(a + ch + b); }
        }
        return r;
    }
    /** Damerau-Levenshtein with cheaper adjacent-key substitutions / doubled letters. */
    static double weightedDistance(String s, String t) { return weightedDistance(s, t, null); }
    static double weightedDistance(String s, String t, SubCost sc) {
        int n = s.length(), m = t.length(); double[][] d = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i; for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) for (int j = 1; j <= m; j++) {
            char a = s.charAt(i - 1), b = t.charAt(j - 1);
            double sub = a == b ? 0 : (adjacent(a, b) ? 0.6 : 1.0);
            if (a != b && sc != null) sub = sc.subCost(a, b, sub);
            double del = (i >= 2 && s.charAt(i - 2) == a) ? 0.5 : 1.0;   // typed doubled letter
            double ins = (j >= 2 && t.charAt(j - 2) == b) ? 0.5 : 1.0;   // missed doubled letter
            double v = Math.min(Math.min(d[i - 1][j] + del, d[i][j - 1] + ins), d[i - 1][j - 1] + sub);
            if (i > 1 && j > 1 && a == t.charAt(j - 2) && s.charAt(i - 2) == b) v = Math.min(v, d[i - 2][j - 2] + 0.7);
            d[i][j] = v;
        }
        return d[n][m];
    }
}
