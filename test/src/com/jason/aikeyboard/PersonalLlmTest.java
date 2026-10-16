package com.jason.aikeyboard;

import static org.junit.Assert.*;
import static com.jason.aikeyboard.PersonalTest.*;

import com.jason.aikeyboard.ai.LlamaBridge;
import com.jason.aikeyboard.core.Dictionary;
import com.jason.aikeyboard.core.SuggestionEngine;
import com.jason.aikeyboard.core.UserModel;
import org.junit.Test;
import java.util.*;

/** Runs in its own JVM (a JNI library can only bind to one class loader; Robolectric uses its own). */
public class PersonalLlmTest {
    @Test public void setup() throws Exception { PersonalTest.load(); llmPersonalizationMeasured(); }
    /** With the real model (host build of the same native code): LLM alone vs LLM + personal context + personal reranking. */
    public void llmPersonalizationMeasured() {
        String model = System.getProperty("aik.model");
        org.junit.Assume.assumeTrue(model != null && LlamaBridge.loadLibrary());
        assertTrue(LlamaBridge.nativeLoad(model, -1, 4, 1024));
        UserModel u = new UserModel(); train(u, Corpus.messages(42, 300));
        SuggestionEngine plain = new SuggestionEngine(d, new UserModel()), pers = new SuggestionEngine(d, u);
        List<String> test = Corpus.messages(4242, 30);
        int n = 0, hLlm = 0, hPers = 0, hNoAi = 0; long ms = 0;
        for (String m : test) { String[] w = m.split(" "); StringBuilder b = new StringBuilder(w[0]).append(' ');
            for (int i = 1; i < w.length; i++) {
                String ctx = b.toString(); n++;
                List<String> l0 = Arrays.asList(LlamaBridge.nativePredict(ctx, "", 3));
                String pc = u.promptContext(ctx, 2, 160);
                long t0 = System.nanoTime(); List<String> l1 = Arrays.asList(LlamaBridge.nativePredict(pc + ctx, "", 3)); ms += (System.nanoTime() - t0) / 1000000;
                if (eqn(plain.suggest(ctx, "", l0).words, w[i])) hLlm++;
                if (eqn(pers.suggest(ctx, "", l1).words, w[i])) hPers++;
                if (eqn(pers.suggest(ctx, "", null).words, w[i])) hNoAi++;
                b.append(w[i]).append(' ');
            } }
        System.out.println("[accuracy+LLM] next-word top-1 on " + n + " positions: LLM only " + pct(hLlm, n) + ", personal n-grams only " + pct(hNoAi, n)
            + ", LLM + personal context + reranking " + pct(hPers, n) + "; avg personalized predict " + ms / n + " ms (host x86, 4 threads)");
        LlamaBridge.nativeUnload();
        assertTrue(hPers > hLlm);
    }
}
