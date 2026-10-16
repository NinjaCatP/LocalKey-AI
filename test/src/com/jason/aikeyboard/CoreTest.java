package com.jason.aikeyboard;

import static org.junit.Assert.*;
import com.jason.aikeyboard.core.*;
import java.io.FileInputStream;
import java.util.*;
import com.jason.aikeyboard.core.Dictionary;
import org.junit.BeforeClass;
import org.junit.Test;

/** Plain JVM tests of dictionary, autocorrect, suggestions, learning and layout. */
public class CoreTest {
    static Dictionary d;
    @BeforeClass public static void load() throws Exception { d = new Dictionary(); d.load(new FileInputStream("/workspace/aikeyboard/app/assets/words.txt")); }

    SuggestionEngine eng() { return new SuggestionEngine(d, new UserModel()); }

    @Test public void dictionaryLoaded() { assertEquals(40000, d.size()); assertTrue(d.contains("hello")); assertTrue(d.contains("don't")); }
    @Test public void completion() { List<String> c = d.complete("tomo", 3); assertEquals("tomorrow", c.get(0)); }
    @Test public void corrections() {
        String[][] cases = {{"teh","the"},{"recieve","receive"},{"tomorow","tomorrow"},{"becuase","because"},{"freind","friend"},{"thsi","this"},{"definately","definitely"},{"wierd","weird"},{"realy","really"},{"untill","until"}};
        for (String[] c : cases) { List<Dictionary.Correction> r = d.corrections(c[0], 3); assertFalse(c[0], r.isEmpty()); assertEquals(c[0] + " " + r, c[1], r.get(0).word); }
    }
    @Test public void autocorrectPicksAndKeepsTyped() {
        SuggestionEngine.Result r = eng().suggest("I will see you ", "tomorow", null);
        assertEquals(1, r.autoCorrectIndex); assertEquals("tomorrow", r.words.get(1)); assertEquals(0, r.typedIndex); assertEquals("tomorow", r.words.get(0));
    }
    @Test public void validWordNotCorrected() { SuggestionEngine.Result r = eng().suggest("I ", "like", null); assertEquals(-1, r.autoCorrectIndex); assertTrue(r.words.contains("like")); }
    @Test public void contractionsAndI() {
        SuggestionEngine.Result r = eng().suggest("", "dont", null); assertEquals("Don't", r.words.get(r.autoCorrectIndex));
        r = eng().suggest("so ", "i", null); assertEquals("I", r.words.get(r.autoCorrectIndex));
        r = eng().suggest("so ", "im", null); assertEquals("I'm", r.words.get(r.autoCorrectIndex));
        r = eng().suggest("so ", "ill", null); assertEquals(-1, r.autoCorrectIndex); assertTrue(r.words.toString(), r.words.contains("I'll"));
    }
    @Test public void capitalizationAtSentenceStart() { SuggestionEngine.Result r = eng().suggest("Hi. ", "hel", null); assertTrue(r.words.toString(), r.words.contains("Hello")); }
    @Test public void llmPredictionsUsedForNextWord() {
        SuggestionEngine.Result r = eng().suggest("Thank you so ", "", Arrays.asList("much", "very", "so"));
        assertEquals(Arrays.asList("much", "very", "so"), r.words);
    }
    @Test public void fallbackNextWordWithoutModel() { SuggestionEngine.Result r = eng().suggest("I went to ", "", null); assertEquals(3, r.words.size()); }
    @Test public void learnsUserWordsAndBigrams() {
        UserModel u = new UserModel(); SuggestionEngine e = new SuggestionEngine(d, u);
        for (int i = 0; i < 3; i++) { u.learn("play", "Galcon"); u.learn("love", "Galcon"); }
        SuggestionEngine.Result r = e.suggest("let's play ", "gal", null);
        assertTrue(r.words.toString(), r.words.contains("Galcon")); assertEquals(-1, r.autoCorrectIndex);
        r = e.suggest("we play ", "", null); assertEquals("Galcon", r.words.get(0));
    }
    @Test public void userModelPersists() throws Exception {
        UserModel u = new UserModel(); u.learn("hi", "Borghese"); u.learn("hi", "Borghese");
        java.io.File f = java.io.File.createTempFile("umodel", ".tsv"); u.save(f);
        UserModel v = new UserModel(); v.load(f); assertEquals(2, v.count("borghese")); assertEquals("Borghese", v.display("borghese"));
    }
    @Test public void previousWordAndSentence() {
        assertEquals("so", SuggestionEngine.previousWord("thank you so "));
        assertNull(SuggestionEngine.previousWord("Done. "));
        assertTrue(SuggestionEngine.sentenceStart("Done. ")); assertFalse(SuggestionEngine.sentenceStart("Done "));
    }
    @Test public void layoutHitTesting() {
        KeyboardLayout l = new KeyboardLayout(KeyboardLayout.Layer.LETTERS); l.layout(1000, 400, 3);
        assertEquals(10, l.rows.get(0).size());
        KeyboardLayout.Key q = l.rows.get(0).get(0); assertEquals('q', l.keyAt(q.x + 5, q.y + 5).code);
        assertEquals('q', l.keyAt(0, 0).code); // gap/edge still hits nearest key
        KeyboardLayout.Key space = null; for (KeyboardLayout.Key k : l.rows.get(3)) if (k.code == ' ') space = k;
        assertEquals(500f, space.x + space.w / 2, 60f);
        for (KeyboardLayout.Layer ly : KeyboardLayout.Layer.values()) { KeyboardLayout x = new KeyboardLayout(ly); x.layout(1080, 900, 3); assertEquals(4, x.rows.size()); }
    }
    @Test public void composeRules() { assertTrue(ComposeLogic.doubleSpacePeriod("word ")); assertFalse(ComposeLogic.doubleSpacePeriod("word. ")); assertTrue(ComposeLogic.swapsSpace('.')); }
    @Test public void speed() {
        long t = System.nanoTime(); for (int i = 0; i < 200; i++) eng().suggest("hello there ", "definately", null);
        double ms = (System.nanoTime() - t) / 1e6 / 200; System.out.println("dictionary suggest+autocorrect avg ms: " + ms); assertTrue(ms < 30);
    }
}
