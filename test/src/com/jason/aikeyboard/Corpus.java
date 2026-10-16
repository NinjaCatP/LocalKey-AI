package com.jason.aikeyboard;

import java.util.*;

/**
 * Synthetic "personal" message corpus for learning tests: one user's recurring phrasing (gaming / modding / family
 * texts) with randomized slots. Train and test sets come from different seeds, so test sentences are new slot
 * combinations, not copies. Real typing is less repetitive than this, so real-world gains will be smaller.
 */
final class Corpus {
    static final String[] T = {
        "can you add unlimited {cheat} to {game}",
        "the {game} build is still crashing when i {action}",
        "i will try the new {game} port {when}",
        "put it in the new games folder please",
        "did you put the {game} apk in the new games folder",
        "hey are you coming over {when}",
        "i am going to pick up the kids at {time}",
        "can you pick up some {food} on the way home",
        "let me know when you get home",
        "i will be home around {time} tonight",
        "the {game} sound is not working on my phone",
        "it works on my phone now thanks",
        "could you make {game} look sharper",
        "i need to merge the {game} files with termux",
        "what time is the game on {when}",
        "sounds good see you {when}",
        "love you too see you {when}",
        "can you check if the {cheat} cheat is done",
        "the main screen in {game} did not load",
        "thanks that fixed the {game} {thing}",
        "i am running late be there at {time}",
        "do you want to get {food} for dinner {when}",
        "my phone is almost dead i will call you {when}",
        "the {thing} button in {game} does not work",
        "make the {cheat} have no limit please",
    };
    static final Map<String, String[]> S = new HashMap<>();
    static {
        S.put("cheat", new String[]{"gold", "skill points", "combo", "kill points", "boost", "cash", "health"});
        S.put("game", new String[]{"galcon", "caligo", "dead space", "raging thunder", "shark or die"});
        S.put("action", new String[]{"change equipment", "open the menu", "start a race", "load my save"});
        S.put("when", new String[]{"tomorrow", "tonight", "later", "this weekend", "after work"});
        S.put("time", new String[]{"five", "six thirty", "seven", "noon"});
        S.put("food", new String[]{"pizza", "milk", "chicken", "tacos"});
        S.put("thing", new String[]{"back", "profile", "cheat", "sort", "reset"});
    }
    static List<String> messages(long seed, int n) {
        Random r = new Random(seed); List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String t = T[r.nextInt(T.length)];
            StringBuilder sb = new StringBuilder(); int p = 0;
            while (true) { int a = t.indexOf('{', p); if (a < 0) { sb.append(t.substring(p)); break; } int b = t.indexOf('}', a);
                sb.append(t, p, a); String[] opts = S.get(t.substring(a + 1, b)); sb.append(opts[r.nextInt(opts.length)]); p = b + 1; }
            out.add(sb.toString());
        }
        return out;
    }
}
