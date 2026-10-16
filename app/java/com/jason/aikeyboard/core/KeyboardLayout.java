package com.jason.aikeyboard.core;

import java.util.ArrayList;
import java.util.List;

/** Key geometry and labels for each layer. Pure Java so it can be unit tested. */
public final class KeyboardLayout {
    public static final int SHIFT = -1, DELETE = -5, ENTER = -4, SYMBOLS = -2, ALPHA = -3, MORE_SYMBOLS = -6, SPACE = 32, EMOJI_SETTINGS = -7;
    public static final class Key {
        public final int code; public final String label; public final String alternates; public final float width; public float x, y, w, h; public int row;
        Key(int code, String label, String alternates, float width) { this.code = code; this.label = label; this.alternates = alternates; this.width = width; }
        public boolean isModifier() { return code < 0 || code == '\n'; }
    }
    public enum Layer { LETTERS, SYMBOLS, MORE }
    public final Layer layer; public final List<List<Key>> rows = new ArrayList<>();

    private static Key k(char c, String alt) { return new Key(c, String.valueOf(c), alt, 1f); }
    private static Key k(int code, String label, float w) { return new Key(code, label, null, w); }

    public KeyboardLayout(Layer layer) {
        this.layer = layer;
        if (layer == Layer.LETTERS) {
            String[] r1 = {"q1","w2","e3èéêëē€","r4","t5þ","y6ýÿ","u7ùúûüū","i8ìíîïī","o9òóôöõøœō","p0"};
            row(r1, 0f);
            row(new String[]{"aàáâäãåæā","sßś$","dð","f","g","h","j","k","lł"}, 0.5f);
            List<Key> r3 = new ArrayList<>(); r3.add(k(SHIFT, "⇧", 1.5f));
            for (String s : new String[]{"z","x","cçć","v","b","nñń","m"}) r3.add(k(s.charAt(0), s.substring(1).isEmpty() ? null : s.substring(1)));
            r3.add(k(DELETE, "⌫", 1.5f)); rows.add(r3);
        } else if (layer == Layer.SYMBOLS) {
            row(new String[]{"1¹½⅓¼⅛","2²⅔","3³¾⅜","4⁴","5⅝","6","7⅞","8","9","0ⁿ∅"}, 0f);
            row(new String[]{"@","#","$€£¥¢₹","_","&","-—–·","+±","(<{[",")>}]"}, 0f);
            List<Key> r3 = new ArrayList<>(); r3.add(k(MORE_SYMBOLS, "=\\<", 1.5f));
            for (String s : new String[]{"*†‡★","\"“”«»","'‘’‹›",":",";","!¡","?¿"}) r3.add(k(s.charAt(0), s.length() > 1 ? s.substring(1) : null));
            r3.add(k(DELETE, "⌫", 1.5f)); rows.add(r3);
        } else {
            row(new String[]{"~","`","|","•","√","π","÷","×","¶","∆"}, 0f);
            row(new String[]{"£","¢","€","¥","^","°","=","{","}"}, 0f);
            List<Key> r3 = new ArrayList<>(); r3.add(k(SYMBOLS, "?123", 1.5f));
            for (String s : new String[]{"\\","©","®","™","%‰","[","]"}) r3.add(k(s.charAt(0), s.length() > 1 ? s.substring(1) : null));
            r3.add(k(DELETE, "⌫", 1.5f)); rows.add(r3);
        }
        List<Key> r4 = new ArrayList<>();
        r4.add(k(layer == Layer.LETTERS ? SYMBOLS : ALPHA, layer == Layer.LETTERS ? "?123" : "ABC", 1.5f));
        r4.add(new Key(',', ",", "⚙", 1f));
        r4.add(k(SPACE, "", 5f));
        r4.add(new Key('.', ".", ",?!'\"-:;/@#", 1f));
        r4.add(k(ENTER, "⏎", 1.5f));
        rows.add(r4);
    }
    private void row(String[] spec, float indent) {
        List<Key> r = new ArrayList<>();
        if (indent > 0) r.add(new Key(0, "", null, indent));
        for (String s : spec) r.add(k(s.charAt(0), s.length() > 1 ? s.substring(1) : null));
        if (indent > 0) r.add(new Key(0, "", null, indent));
        rows.add(r);
    }
    /** Lays keys out in a w x h box (pixels). */
    public void layout(float width, float height, float pad) {
        float rh = height / rows.size();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> r = rows.get(ri); float units = 0; for (Key key : r) units += key.width;
            float unit = width / Math.max(10f, units), x = (width - unit * units) / 2f;
            for (Key key : r) { key.x = x + pad; key.y = ri * rh + pad; key.w = unit * key.width - 2 * pad; key.h = rh - 2 * pad; key.row = ri; x += unit * key.width; }
        }
    }
    /** Nearest real key to a touch point (touches in gaps still hit a key). */
    public Key keyAt(float x, float y) {
        Key best = null; float bd = Float.MAX_VALUE;
        for (List<Key> r : rows) for (Key key : r) {
            if (key.code == 0) continue;
            float dx = x < key.x ? key.x - x : x > key.x + key.w ? x - key.x - key.w : 0;
            float dy = y < key.y ? key.y - y : y > key.y + key.h ? y - key.y - key.h : 0;
            float d = dx * dx + dy * dy * 2f; if (d < bd) { bd = d; best = key; }
        }
        return best;
    }
    /** v5: centre of each letter key a..z (after layout), for swipe typing. Empty unless this is the LETTERS layer. */
    public java.util.Map<Character, float[]> letterCentres() {
        java.util.Map<Character, float[]> m = new java.util.HashMap<>();
        if (layer != Layer.LETTERS) return m;
        for (List<Key> r : rows) for (Key key : r) if (key.code >= 'a' && key.code <= 'z') m.put((char) key.code, new float[]{key.x + key.w / 2, key.y + key.h / 2});
        return m;
    }
    /** v5: pitch (centre-to-centre distance) of letter keys horizontally / vertically. */
    public float[] letterPitch() { Key q = null, w = null, a = null; for (List<Key> r : rows) for (Key k : r) { if (k.code == 'q') q = k; if (k.code == 'w') w = k; if (k.code == 'a') a = k; }
        if (q == null || w == null || a == null) return new float[]{1, 1}; return new float[]{w.x - q.x, a.y - q.y}; }
}
