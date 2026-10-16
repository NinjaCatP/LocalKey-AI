package com.jason.aikeyboard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** v7: turns raw speech-recognizer output into text to insert at the cursor (cleanup, spacing, capitalization). Pure Java. */
public final class VoiceText {
    private VoiceText() {}
    private static final Pattern NOISE = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*|♪+|<\\|[^|]*\\|>");
    /** Removes whisper non-speech tags ([BLANK_AUDIO], (music), *laughs*, ♪), collapses whitespace. */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = NOISE.matcher(raw).replaceAll(" ").replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (s.matches("[\\p{Punct}\\s]*")) return ""; // only punctuation left (e.g. "...")
        return s;
    }
    public interface LowerOk { boolean ok(String lowerWord); }
    /**
     * Text to commit after {@code before}: a leading space unless at start / after whitespace or an opening bracket/quote;
     * first letter capitalized at a sentence start, lower-cased mid-sentence when the word is a normal lower-case word
     * (lowerOk, null = always) and not "I"/"I'm"/an acronym; always ends with a single space.
     */
    public static String format(String before, String text, boolean autoCap, LowerOk lowerOk) {
        String t = clean(text); if (t.isEmpty()) return "";
        if (before == null) before = "";
        boolean sentenceStart = SuggestionEngine.sentenceStart(before);
        int sp = t.indexOf(' '); String first = sp < 0 ? t : t.substring(0, sp), rest = sp < 0 ? "" : t.substring(sp);
        String core = first.replaceAll("[^\\p{L}']", "");
        if (sentenceStart && autoCap) first = cap(first);
        else if (!sentenceStart && !core.isEmpty() && Character.isUpperCase(core.charAt(0)) && !isAlwaysCap(core)
            && (lowerOk == null || lowerOk.ok(core.toLowerCase(java.util.Locale.ROOT)))) {
            int i = first.indexOf(core.charAt(0)); first = first.substring(0, i) + Character.toLowerCase(core.charAt(0)) + first.substring(i + 1);
        }
        t = first + rest;
        char last = before.isEmpty() ? ' ' : before.charAt(before.length() - 1);
        boolean noSpace = before.isEmpty() || Character.isWhitespace(last) || last == '(' || last == '[' || last == '"' || last == '\u201C' || last == '/' || last == '@' || last == '#';
        if (!noSpace && t.length() > 0 && ",.!?;:".indexOf(t.charAt(0)) >= 0) noSpace = true;
        return (noSpace ? "" : " ") + t + " ";
    }
    private static boolean isAlwaysCap(String w) { return w.equals("I") || w.startsWith("I'") || w.length() > 1 && w.equals(w.toUpperCase(java.util.Locale.ROOT)); }
    private static String cap(String w) {
        for (int i = 0; i < w.length(); i++) if (Character.isLetter(w.charAt(i))) return w.substring(0, i) + Character.toUpperCase(w.charAt(i)) + w.substring(i + 1);
        return w;
    }
    /** Words (lower-case, apostrophes kept) for personal learning; null marks a sentence end. */
    public static List<String> words(String text) {
        List<String> o = new ArrayList<>();
        for (String tok : clean(text).split("\\s+")) {
            String w = tok.replaceAll("^[^\\p{L}\\p{N}']+|[^\\p{L}\\p{N}']+$", "");
            if (!w.isEmpty()) o.add(w);
            if (tok.matches(".*[.!?]$")) o.add(null);
        }
        return o;
    }
    /** Word error rate (%) between reference and hypothesis after lower-casing and stripping punctuation. */
    public static double wer(List<String> refs, List<String> hyps) {
        int err = 0, n = 0;
        for (int k = 0; k < refs.size(); k++) {
            String[] r = norm(refs.get(k)), h = norm(hyps.get(k)); n += r.length;
            int[][] d = new int[r.length + 1][h.length + 1];
            for (int i = 0; i <= r.length; i++) d[i][0] = i;
            for (int j = 0; j <= h.length; j++) d[0][j] = j;
            for (int i = 1; i <= r.length; i++) for (int j = 1; j <= h.length; j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (r[i - 1].equals(h[j - 1]) ? 0 : 1));
            err += d[r.length][h.length];
        }
        return n == 0 ? 0 : 100.0 * err / n;
    }
    private static String[] norm(String s) { s = s.toLowerCase(java.util.Locale.ROOT).replace('\u2019', '\'').replaceAll("[^a-z0-9' ]", " ").trim(); return s.isEmpty() ? new String[0] : s.split("\\s+"); }
}
