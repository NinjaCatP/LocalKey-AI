package com.jason.aikeyboard;

import static org.junit.Assert.*;

import com.jason.aikeyboard.ai.LlamaBridge;
import com.jason.aikeyboard.core.Dictionary;
import com.jason.aikeyboard.core.SuggestionEngine;
import com.jason.aikeyboard.core.UserModel;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.util.*;

/** v3 personal learning: measured accuracy before/after simulated typing sessions, adaptation, caps, persistence. */
public class PersonalTest {
    static Dictionary d;
    @BeforeClass public static void load() throws Exception { d = new Dictionary(); d.load(new FileInputStream("/workspace/aikeyboard/app/assets/words.txt")); }

    static void train(UserModel u, List<String> msgs) {
        for (String m : msgs) { String[] w = m.split(" "); for (int i = 0; i < w.length; i++) u.learn(i >= 2 ? w[i - 2] : null, i >= 1 ? w[i - 1] : null, w[i]); u.endPhrase(); }
    }
    /** {top1 hits, top3 hits, total} for next-word prediction at every position after the first word. */
    static int[] nextWordAcc(SuggestionEngine e, List<String> msgs) {
        int h1 = 0, h3 = 0, n = 0;
        for (String m : msgs) { String[] w = m.split(" ");
            StringBuilder before = new StringBuilder(w[0]).append(' ');
            for (int i = 1; i < w.length; i++) {
                List<String> s = e.suggest(before.toString(), "", null).words; n++;
                if (!s.isEmpty() && Dictionary.norm(s.get(0)).equals(Dictionary.norm(w[i]))) h1++;
                for (String x : s) if (Dictionary.norm(x).equals(Dictionary.norm(w[i]))) { h3++; break; }
                before.append(w[i]).append(' ');
            } }
        return new int[]{h1, h3, n};
    }
    /** {hits, total}: word (>= 4 letters) offered on the strip after typing its first 2 letters. */
    static int[] completionAcc(SuggestionEngine e, List<String> msgs) {
        int h = 0, n = 0;
        for (String m : msgs) { String[] w = m.split(" "); StringBuilder before = new StringBuilder();
            for (String x : w) {
                if (x.length() >= 4) { n++; for (String s : e.suggest(before.toString(), x.substring(0, 2), null).words) if (Dictionary.norm(s).equals(Dictionary.norm(x))) { h++; break; } }
                before.append(x).append(' ');
            } }
        return new int[]{h, n};
    }
    static String pct(int a, int b) { return String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * a / b, a, b); }

    @Test public void nextWordAndCompletionAccuracyImproveWithUse() {
        List<String> test = Corpus.messages(999, 150);
        UserModel u = new UserModel(); SuggestionEngine e = new SuggestionEngine(d, u);
        int[] b = nextWordAcc(e, test), bc = completionAcc(e, test);
        System.out.println("[accuracy] before learning: next-word top-1 " + pct(b[0], b[2]) + ", top-3 " + pct(b[1], b[2]) + "; completion after 2 letters " + pct(bc[0], bc[1]));
        int[] sessions = {25, 75, 200, 400}; int done = 0; int[] last = b;
        for (int s : sessions) {
            train(u, Corpus.messages(1000 + s, s - done)); done = s;
            int[] a = nextWordAcc(e, test), ac = completionAcc(e, test);
            System.out.println("[accuracy] after " + s + " messages: next-word top-1 " + pct(a[0], a[2]) + ", top-3 " + pct(a[1], a[2]) + "; completion after 2 letters " + pct(ac[0], ac[1]));
            last = a; bc = ac;
        }
        assertTrue("top-1 should improve a lot", last[0] > b[0] * 3 && last[0] > last[2] / 3);
    }
    @Test public void nonPersonalTextDoesNotGetWorse() {
        // generic sentences unlike the training corpus: personal model must not wreck them (dictionary completions stay)
        List<String> generic = Arrays.asList("the weather is really nice today", "please remember to bring your umbrella", "the meeting starts after lunch on monday", "she bought a beautiful painting yesterday");
        SuggestionEngine e0 = new SuggestionEngine(d, new UserModel());
        UserModel u = new UserModel(); train(u, Corpus.messages(5, 400)); SuggestionEngine e1 = new SuggestionEngine(d, u);
        int[] a = completionAcc(e0, generic), b = completionAcc(e1, generic);
        System.out.println("[accuracy] generic text completion: before " + pct(a[0], a[1]) + ", after personal training " + pct(b[0], b[1]));
        assertTrue(b[0] >= a[0] - 1);
    }
    @Test public void revertedAutocorrectStops() {
        UserModel u = new UserModel(); SuggestionEngine e = new SuggestionEngine(d, u);
        SuggestionEngine.Result r = e.suggest("let's play ", "galcon", null);
        assertEquals("falcon", r.words.get(r.autoCorrectIndex)); // before: corrected
        u.autocorrectReverted("galcon", "falcon");
        r = e.suggest("let's play ", "galcon", null); System.out.println("[revert] after 1 undo: " + r);
        u.autocorrectReverted("galcon", "falcon");
        r = e.suggest("let's play ", "galcon", null); System.out.println("[revert] after 2 undos: " + r);
        assertEquals(-1, r.autoCorrectIndex); assertTrue(u.neverCorrect("galcon")); assertTrue(u.count("galcon") >= 2);
        assertTrue(r.words.contains("galcon"));
        // a real typo is still corrected
        assertEquals("tomorrow", e.suggest("see you ", "tomorow", null).words.get(1));
    }
    @Test public void repeatedUnknownWordsLearned() {
        UserModel u = new UserModel(); SuggestionEngine e = new SuggestionEngine(d, u);
        assertTrue(e.suggest("open ", "dynarmic", null).autoCorrectIndex >= 0);
        u.learn("the", "dynarmic"); u.learn("the", "dynarmic");
        SuggestionEngine.Result r = e.suggest("open ", "dynarmic", null);
        assertEquals(-1, r.autoCorrectIndex);
        assertTrue(e.suggest("the ", "dyn", null).words.toString(), e.suggest("the ", "dyn", null).words.contains("dynarmic"));
    }
    @Test public void tappedSuggestionsBoostedIgnoredDemoted() {
        UserModel u = new UserModel(); SuggestionEngine e = new SuggestionEngine(d, u);
        List<String> s0 = e.suggest("I went to ", "", null).words; System.out.println("[feedback] before: " + s0);
        String ignored = s0.get(0);
        for (int i = 0; i < 12; i++) u.feedback(Collections.singletonList(ignored), "bed", false); // shown at the front, user typed something else
        List<String> s1 = e.suggest("I went to ", "", null).words; System.out.println("[feedback] after ignoring '" + ignored + "' 12x: " + s1);
        assertNotEquals(ignored, s1.get(0));
        // tapping a completion repeatedly promotes it to the front
        List<String> c0 = e.suggest("we ", "pla", null).words; String tapped = c0.get(c0.size() - 1);
        for (int i = 0; i < 4; i++) u.feedback(e.suggest("we ", "pla", null).words, tapped, true);
        List<String> c1 = e.suggest("we ", "pla", null).words; System.out.println("[feedback] completion 'pla' before " + c0 + " after tapping '" + tapped + "' 4x: " + c1);
        assertTrue(c1.indexOf(tapped) < c0.indexOf(tapped) || c1.indexOf(tapped) <= 1);
    }
    @Test public void keySlipModelWeightsAutocorrect() {
        UserModel u = new UserModel(); SuggestionEngine e = new SuggestionEngine(d, u);
        String before = d.corrections("bame", 1, u).get(0).word; assertEquals("name", before);
        // this user often hits b when they mean g (accepted corrections)
        u.correctionAccepted("bood", "good"); u.correctionAccepted("bive", "give"); u.correctionAccepted("bet", "get");
        String after = d.corrections("bame", 1, u).get(0).word;
        System.out.println("[slips] 'bame' corrected to '" + before + "' before, '" + after + "' after learning b->g slips (" + u.slipPatterns() + " pattern)");
        assertEquals("game", after);
        assertEquals("game", e.suggest("play the ", "bame", null).words.get(1));
        // a reverted correction un-learns the slip
        u.autocorrectReverted("bet", "get"); u.autocorrectReverted("bive", "give"); u.autocorrectReverted("bood", "good");
        assertEquals("name", d.corrections("bame", 1, u).get(0).word);
    }
    @Test public void personalPromptContextShortAndRelevant() {
        UserModel u = new UserModel(); train(u, Corpus.messages(7, 60));
        String pc = u.promptContext("can you add unlimited ", 2, 160);
        System.out.println("[prompt] personal context: " + pc.replace("\n", " | "));
        assertFalse(pc.isEmpty()); assertTrue(pc.length() <= 160); assertTrue(pc.split("\n").length <= 2);
        assertEquals("", new UserModel().promptContext("hello there ", 2, 160));
        assertTrue(u.phraseCount() <= UserModel.MAX_PHRASES);
    }
    @Test public void decayFavoursRecentHabits() {
        UserModel u = new UserModel();
        for (int i = 0; i < 30; i++) u.learn("see", "you");
        for (int i = 0; i < 40000; i++) u.learn("w" + (i % 500), "x" + (i % 300)); // ~2 half-lives of other typing
        for (int i = 0; i < 12; i++) u.learn("see", "ya");
        assertEquals("ya", u.next("see", 2).get(0));
    }
    @Test public void sizeCapsAndFastPersistence() throws Exception {
        UserModel u = new UserModel(); Random r = new Random(1);
        long t0 = System.nanoTime();
        for (int i = 0; i < 300000; i++) u.learn("a" + r.nextInt(30000), "b" + r.nextInt(30000), "c" + r.nextInt(40000));
        long learnMs = (System.nanoTime() - t0) / 1000000;
        assertTrue(u.size() <= UserModel.MAX_UNI); assertTrue(u.pairCount() <= UserModel.MAX_BI + UserModel.MAX_TRI);
        File f = File.createTempFile("umodel", ".tsv"); t0 = System.nanoTime(); u.save(f); long saveMs = (System.nanoTime() - t0) / 1000000;
        UserModel v = new UserModel(); t0 = System.nanoTime(); v.load(f); long loadMs = (System.nanoTime() - t0) / 1000000;
        System.out.println("[persist] capped model: " + u.size() + " words, " + u.pairCount() + " sequences, file " + f.length() / 1024 + " KB, save " + saveMs + " ms, load " + loadMs + " ms (host JVM), 300k learns " + learnMs + " ms");
        assertEquals(u.size(), v.size()); assertEquals(u.pairCount(), v.pairCount());
    }
    @Test public void persistsAllKindsAndExportImport() throws Exception {
        UserModel u = new UserModel(); train(u, Corpus.messages(3, 50));
        u.autocorrectReverted("galcon", "falcon"); u.autocorrectReverted("galcon", "falcon");
        u.correctionAccepted("bood", "good"); u.feedback(Arrays.asList("the", "a", "to"), "to", true);
        File f = File.createTempFile("umodel", ".tsv"), pf = File.createTempFile("phrases", ".bin");
        UserModel.Codec xor = new UserModel.Codec() { public byte[] encode(byte[] p) { byte[] o = p.clone(); for (int i = 0; i < o.length; i++) o[i] ^= 0x5A; return o; } public byte[] decode(byte[] s) { return encode(s); } };
        u.save(f, pf, xor);
        String raw = new String(java.nio.file.Files.readAllBytes(pf.toPath()), "UTF-8");
        assertFalse("phrases must not be stored in plain text", raw.contains("games folder"));
        UserModel v = new UserModel(); v.load(f, pf, xor);
        assertEquals(u.size(), v.size()); assertEquals(u.pairCount(), v.pairCount()); assertEquals(u.phraseCount(), v.phraseCount());
        assertTrue(v.neverCorrect("galcon")); assertEquals(1, v.slipPatterns()); assertTrue(v.feedbackBoost("to") > 0);
        assertEquals(u.next("new", 3), v.next("new", 3));
        StringWriter sw = new StringWriter(); u.exportTo(sw);
        UserModel w = new UserModel(); int n = w.importFrom(new StringReader(sw.toString()));
        assertTrue(n > 100); assertEquals(u.size(), w.size()); assertEquals(u.phraseCount(), w.phraseCount()); assertTrue(w.neverCorrect("galcon"));
        assertEquals(u.next("new", 3), w.next("new", 3));
        u.clear(); assertEquals(0, u.size()); assertEquals(0, u.phraseCount()); assertFalse(u.neverCorrect("galcon"));
    }
    @Test public void v2FileStillLoads() throws Exception {
        File f = File.createTempFile("v2model", ".tsv");
        try (Writer w = new FileWriter(f)) { w.write("U\tgalcon\t3\tGalcon\nB\tplay\tgalcon\t3\n"); }
        UserModel u = new UserModel(); u.load(f); assertEquals(3, u.count("galcon")); assertEquals("galcon", u.next("play", 1).get(0));
    }

    static boolean eqn(List<String> s, String w) { return !s.isEmpty() && Dictionary.norm(s.get(0)).equals(Dictionary.norm(w)); }
}
