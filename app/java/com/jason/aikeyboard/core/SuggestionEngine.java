package com.jason.aikeyboard.core;

import java.util.*;

/** Combines dictionary, learned words and (optional) LLM predictions into 3 strip suggestions. Pure Java. */
public final class SuggestionEngine {
    public static final class Result {
        public final List<String> words = new ArrayList<>();
        /** index into words of the autocorrection to apply on space, or -1 */
        public int autoCorrectIndex = -1;
        /** the literal typed word is shown (in quotes) at this index, or -1 */
        public int typedIndex = -1; public boolean typedValid;
        public String typed = "";
        public String toString() { return words + " ac=" + autoCorrectIndex + " typed=" + typedIndex; }
    }
    private static final String[] DEFAULT_START = {"I", "The", "Thanks"};
    private static final String[] DEFAULT_NEXT = {"the", "to", "and", "I", "a", "you", "of", "is", "in", "it"};

    private final Dictionary dict; private final UserModel user;
    public boolean autocorrect = true;
    /** v3: bias LLM candidates with the personal n-gram model ("Personalize AI"). */
    public boolean personalRerank = true;
    /** v5: how many strip candidates to produce (the bar scrolls). */
    public int maxWords = 3;
    public SuggestionEngine(Dictionary d, UserModel u) { dict = d; user = u; }

    /** Last whole word before the current one in `before` (text before the partial word). */
    public static String previousWord(String before) {
        int e = before.length(); while (e > 0 && !isWordChar(before.charAt(e - 1))) { char c = before.charAt(e - 1); if (c == '.' || c == '!' || c == '?' || c == '\n') return null; e--; }
        int s = e; while (s > 0 && isWordChar(before.charAt(s - 1))) s--;
        return s == e ? null : before.substring(s, e);
    }
    /** Word before previousWord(before) in the same sentence, or null. */
    public static String previousWord2(String before) {
        String p = previousWord(before); if (p == null) return null;
        int e = before.length(); while (e > 0 && !isWordChar(before.charAt(e - 1))) e--;
        int s = e; while (s > 0 && isWordChar(before.charAt(s - 1))) s--;
        return previousWord(before.substring(0, s));
    }
    public static boolean isWordChar(char c) { return Character.isLetterOrDigit(c) || c == '\'' || c == '\u2019'; }
    /** Whether the next word should start with a capital letter. */
    public static boolean sentenceStart(String before) {
        int i = before.length() - 1; while (i >= 0 && before.charAt(i) == ' ') i--;
        if (i < 0) return true; char c = before.charAt(i); return c == '.' || c == '!' || c == '?' || c == '\n';
    }
    static String applyCase(String word, String typed, boolean capitalize) {
        if (!typed.isEmpty() && typed.length() > 1 && typed.equals(typed.toUpperCase(Locale.ROOT)) && !typed.equals(typed.toLowerCase(Locale.ROOT))) return word.toUpperCase(Locale.ROOT);
        boolean cap = capitalize || (!typed.isEmpty() && Character.isUpperCase(typed.charAt(0)));
        if (cap && !word.isEmpty()) return Character.toUpperCase(word.charAt(0)) + word.substring(1);
        return word;
    }
    private String disp(String key) { String u = user.display(key); if (u != null && (u.contains("'") || !u.equals(u.toLowerCase(Locale.ROOT)))) return u; return Dictionary.display(key); }

    /**
     * @param before text before the partial word (may end with space)
     * @param typed the partial word being composed ("" when predicting the next word)
     * @param llm words predicted by the model, may be null (not loaded / slow)
     */
    public Result suggest(String before, String typed, List<String> llm) {
        Result r = new Result(); r.typed = typed;
        boolean cap = sentenceStart(before);
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        if (typed.isEmpty()) {
            String prev = previousWord(before), prev2 = previousWord2(before);
            LinkedHashMap<String, Double> pers = user.nextScores(prev2, prev, Math.max(6, maxWords));
            int i = 0;
            if (llm != null) for (String w : llm) {
                double sc = 100 - i++ * 3;
                if (personalRerank) { Double pp = pers.get(Dictionary.norm(w)); if (pp != null) sc += 20 * pp; sc += user.feedbackBoost(w); }
                add(scores, w, sc);
            }
            // personal n-grams: a strong personal habit (p -> 1) outranks the model's top guess
            for (Map.Entry<String, Double> e : pers.entrySet()) add(scores, disp(e.getKey()), 85 + 20 * e.getValue() + user.feedbackBoost(e.getKey()));
            if (scores.size() < maxWords) { String[] d = prev == null ? DEFAULT_START : DEFAULT_NEXT; i = 0; for (String w : d) add(scores, w, 10 - i++ + user.feedbackBoost(w)); }
            for (String w : top(scores, maxWords)) r.words.add(applyCase(w, "", cap && prev == null && !w.equals("I")));
            return r;
        }
        String n = Dictionary.norm(typed);
        boolean valid = dict.isValidAsTyped(typed) || user.count(typed) >= 2 || user.neverCorrect(typed);
        String cprev = previousWord(before);
        LinkedHashMap<String, Double> ctxNext = user.nextScores(previousWord2(before), cprev, 12);
        // completions
        for (String w : dict.complete(n, Math.max(8, maxWords + 4))) { double s = Math.log(dict.freq(w) + 1) + (w.equals(n) ? 6 : 0) - 0.15 * (w.length() - n.length()); Double cp = ctxNext.get(w); if (cp != null) s += 10 * cp; add(scores, disp(w), s + user.feedbackBoost(w)); }
        for (String w : user.complete(n, Math.max(4, maxWords / 3))) { Double cp = ctxNext.get(w); add(scores, disp(w), 12 + 3 * Math.log1p(user.score(w)) + (cp == null ? 0 : 10 * cp) + user.feedbackBoost(w)); }
        for (Map.Entry<String, Double> e : ctxNext.entrySet()) if (e.getKey().startsWith(n) && !e.getKey().equals(n)) add(scores, disp(e.getKey()), 14 + 10 * e.getValue() + user.feedbackBoost(e.getKey()));
        if (llm != null) { int i = 0; for (String w : llm) { String k = Dictionary.norm(w); if (k.startsWith(n) && (dict.contains(k) || user.count(k) > 0)) add(scores, w, 20 - i++); } }
        String amb = Dictionary.ambiguousDisplay(n); if (amb != null) add(scores, amb, 16);
        // corrections
        List<Dictionary.Correction> corr = valid ? Collections.emptyList() : dict.corrections(n, 3, user);
        for (Dictionary.Correction c : corr) add(scores, disp(c.word), c.score + 2);
        List<String> ranked = top(scores, maxWords + 3);
        String typedDisp = typed;
        String acWord = null;
        if (autocorrect && !valid && !corr.isEmpty() && n.length() >= 2) {
            Dictionary.Correction c = corr.get(0);
            boolean confident = corr.size() == 1 || c.score - corr.get(1).score > 0.8 || n.length() <= 3 && c.score > 8;
            if (confident) acWord = disp(c.word);
        }
        if (autocorrect && !valid && acWord == null && Dictionary.display(n) != null && !Dictionary.display(n).equals(n) && dict.contains(n)) acWord = Dictionary.display(n);
        if (autocorrect && dict.contains(n) && !dict.isValidAsTyped(typed)) acWord = Dictionary.display(n); // i -> I, dont -> don't
        // layout: [typed] [best] [second]  (like Gboard: middle is the primary)
        List<String> words = new ArrayList<>();
        if (acWord != null) {
            words.add(typedDisp); r.typedIndex = 0;
            words.add(acWord); r.autoCorrectIndex = 1;
            for (String w : ranked) if (words.size() < maxWords && !eq(w, acWord) && !eq(w, typedDisp)) words.add(w);
        } else {
            for (String w : ranked) if (words.size() < maxWords && !eq(w, typedDisp)) words.add(w);
            if (!valid) { words.add(0, typedDisp); r.typedIndex = 0; while (words.size() > maxWords) words.remove(words.size() - 1); }
            else { // v6: the exact typed word is always first (shown in quotes) so it can be kept as typed
                words.add(0, typedDisp); r.typedIndex = 0; r.typedValid = true; while (words.size() > maxWords) words.remove(words.size() - 1);
            }
        }
        for (int i = 0; i < words.size(); i++) r.words.add(i == r.typedIndex ? words.get(i) : applyCase(words.get(i), typed, cap));
        return r;
    }
    private static boolean eq(String a, String b) { return a.equalsIgnoreCase(b); }
    private static void add(Map<String, Double> m, String w, double s) {
        if (w == null || w.isEmpty()) return;
        for (Map.Entry<String, Double> e : m.entrySet()) if (e.getKey().equalsIgnoreCase(w)) { if (s > e.getValue()) e.setValue(s); return; }
        m.put(w, s);
    }
    private static List<String> top(Map<String, Double> m, int k) {
        List<Map.Entry<String, Double>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>(); for (int i = 0; i < es.size() && i < k; i++) out.add(es.get(i).getKey()); return out;
    }
}
