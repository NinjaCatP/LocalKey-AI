package com.jason.aikeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * v3 emoji panel that replaces the key area: category tabs, scrollable grid, recents (stored in app prefs),
 * skin tones on long-press, ABC and backspace. Data is the bundled assets/emoji.txt (no network).
 */
public final class EmojiPanel extends View {
    public interface Listener { void onEmoji(String e); void onEmojiDelete(); void onEmojiAbc(); }
    public static final String[] CATS = {"recent", "smileys", "people", "animals", "food", "activities", "travel", "objects", "symbols", "flags"};
    static final String[] TAB_ICON = {"🕘", "😀", "👋", "🐻", "🍔", "⚽", "🚗", "💡", "❤️", "🏁"};
    static final int MAX_RECENT = 32;
    private static final HashMap<String, List<String>> data = new HashMap<>();
    private static final HashMap<String, String[]> tones = new HashMap<>();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final SharedPreferences prefs;
    private Listener listener; private int cat = 1; private float scroll, downY, downX, lastY; private boolean dragging;
    private int pressed = -1; private String[] toneRow; private int toneSel = -1; private float toneX, toneY; private int forcedHeight;
    private final List<String> recent = new ArrayList<>();

    public EmojiPanel(Context c) {
        super(c); setBackgroundColor(KeyboardView.BG); p.setTextAlign(Paint.Align.CENTER);
        prefs = c.getSharedPreferences("emoji", Context.MODE_PRIVATE);
        loadData(c);
        for (String e : prefs.getString("recent", "").split("\t")) if (!e.isEmpty()) recent.add(e);
        cat = recent.isEmpty() ? 1 : 0;
    }
    static synchronized void loadData(Context c) {
        if (!data.isEmpty()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getAssets().open("emoji.txt"), StandardCharsets.UTF_8))) {
            String line; List<String> cur = null;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                if (line.startsWith("G\t")) { cur = new ArrayList<>(); data.put(line.substring(2), cur); continue; }
                int t = line.indexOf('\t'); String e = t < 0 ? line : line.substring(0, t);
                if (cur != null) cur.add(e);
                if (t > 0) tones.put(e, line.substring(t + 1).split(" "));
            }
        } catch (Exception ignored) {}
    }
    public void setListener(Listener l) { listener = l; }
    /** Match the letter keyboard's height so switching doesn't jump. */
    public void setForcedHeight(int h) { if (h != forcedHeight) { forcedHeight = h; requestLayout(); } }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), forcedHeight > 0 ? forcedHeight : (int) dp(262)); }

    public int category() { return cat; }
    public void setCategory(int c) { cat = c; scroll = 0; invalidate(); }
    public List<String> items() { return cat == 0 ? recent : data.getOrDefault(CATS[cat], new ArrayList<>()); }
    public static int count(String c) { List<String> l = data.get(c); return l == null ? 0 : l.size(); }
    public static String[] tonesFor(String e) { return tones.get(e); }
    public List<String> recents() { return new ArrayList<>(recent); }

    private float tabH() { return dp(40); }
    private float bottomH() { return dp(46); }
    private int cols() { return Math.max(6, (int) (getWidth() / dp(46))); }
    private float cell() { return getWidth() / (float) cols(); }
    private float gridTop() { return tabH(); }
    private float gridH() { return getHeight() - tabH() - bottomH(); }
    private float maxScroll() { int rows = (items().size() + cols() - 1) / cols(); return Math.max(0, rows * cell() - gridH()); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), tw = w / CATS.length, th = tabH();
        for (int i = 0; i < CATS.length; i++) {
            if (i == cat) { bg.setColor(0xFF2B2D33); r.set(i * tw + dp(2), dp(4), (i + 1) * tw - dp(2), th - dp(4)); c.drawRoundRect(r, dp(10), dp(10), bg); }
            p.setTextSize(dp(18)); p.setAlpha(i == cat ? 255 : 150);
            c.drawText(TAB_ICON[i], i * tw + tw / 2, th / 2 - (p.descent() + p.ascent()) / 2, p);
        }
        p.setAlpha(255);
        bg.setColor(0xFF7C9CFF); c.drawRect(cat * tw + tw * 0.25f, th - dp(3), cat * tw + tw * 0.75f, th - dp(1), bg);
        // grid
        c.save(); c.clipRect(0, gridTop(), w, gridTop() + gridH());
        List<String> it = items(); int cols = cols(); float cs = cell(); p.setTextSize(cs * 0.6f);
        if (it.isEmpty()) { p.setTextSize(dp(14)); p.setColor(KeyboardView.HINT); c.drawText("Emoji you use will show up here", w / 2, gridTop() + gridH() / 2, p); p.setColor(0xFFFFFFFF); }
        int first = (int) (scroll / cs) * cols;
        for (int i = Math.max(0, first); i < it.size(); i++) {
            int row = i / cols, col = i % cols; float y = gridTop() + row * cs - scroll; if (y > gridTop() + gridH()) break;
            if (i == pressed) { bg.setColor(0xFF2B2D33); r.set(col * cs + dp(2), y + dp(2), (col + 1) * cs - dp(2), y + cs - dp(2)); c.drawRoundRect(r, dp(8), dp(8), bg); }
            c.drawText(it.get(i), col * cs + cs / 2, y + cs / 2 - (p.descent() + p.ascent()) / 2, p);
            if (cat != 0 && tones.containsKey(it.get(i))) { bg.setColor(0xFF4A4E58); c.drawCircle((col + 1) * cs - dp(6), y + cs - dp(6), dp(2), bg); }
        }
        c.restore();
        // bottom row: ABC | (space for hint) | ⌫
        float by = getHeight() - bottomH();
        bg.setColor(KeyboardView.KEY_MOD);
        r.set(dp(6), by + dp(5), dp(86), getHeight() - dp(5)); c.drawRoundRect(r, dp(8), dp(8), bg);
        r.set(w - dp(86), by + dp(5), w - dp(6), getHeight() - dp(5)); c.drawRoundRect(r, dp(8), dp(8), bg);
        bg.setColor(KeyboardView.KEY); r.set(dp(94), by + dp(5), w - dp(94), getHeight() - dp(5)); c.drawRoundRect(r, dp(8), dp(8), bg);
        p.setColor(KeyboardView.TEXT); p.setTextSize(dp(16));
        float ty = by + bottomH() / 2 - (p.descent() + p.ascent()) / 2;
        c.drawText("ABC", dp(46), ty, p); c.drawText("⌫", w - dp(46), ty, p);
        p.setTextSize(dp(13)); p.setColor(KeyboardView.HINT); c.drawText("space", w / 2, ty, p); p.setColor(0xFFFFFFFF);
        // skin tone popup
        if (toneRow != null) {
            float ts = cs * 1.05f, pw = ts * toneRow.length, px = Math.max(dp(4), Math.min(w - pw - dp(4), toneX - pw / 2)), py = Math.max(dp(2), toneY - ts - dp(8));
            bg.setColor(0xFF3A3D46); r.set(px - dp(4), py - dp(4), px + pw + dp(4), py + ts + dp(4)); c.drawRoundRect(r, dp(12), dp(12), bg);
            p.setTextSize(ts * 0.6f);
            for (int i = 0; i < toneRow.length; i++) {
                if (i == toneSel) { bg.setColor(0xFF7C9CFF); r.set(px + i * ts + dp(2), py + dp(2), px + (i + 1) * ts - dp(2), py + ts - dp(2)); c.drawRoundRect(r, dp(8), dp(8), bg); }
                c.drawText(toneRow[i], px + i * ts + ts / 2, py + ts / 2 - (p.descent() + p.ascent()) / 2, p);
            }
            tonePx = px; toneTs = ts;
        }
    }
    private float tonePx, toneTs;

    private int cellAt(float x, float y) {
        if (y < gridTop() || y > gridTop() + gridH()) return -1;
        int col = (int) (x / cell()), row = (int) ((y - gridTop() + scroll) / cell());
        int i = row * cols() + col; return i >= 0 && i < items().size() && col < cols() ? i : -1;
    }
    private final Runnable longPress = new Runnable() { @Override public void run() {
        if (pressed < 0 || dragging) return;
        String e = items().get(pressed); String[] t = tones.get(e); if (t == null) return;
        toneRow = new String[t.length + 1]; toneRow[0] = e; System.arraycopy(t, 0, toneRow, 1, t.length); toneSel = 0;
        int cols = cols(); toneX = (pressed % cols) * cell() + cell() / 2; toneY = gridTop() + (pressed / cols) * cell() - scroll;
        invalidate();
    } };
    /** Tests: open the skin-tone popup for an item index. */
    void longPressForTest(int index) { pressed = index; longPress.run(); }
    String[] toneRowForTest() { return toneRow; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        int act = e.getActionMasked();
        {
            if (act == MotionEvent.ACTION_DOWN) {
                downX = x; downY = lastY = y; dragging = false; pressed = cellAt(x, y);
                if (pressed >= 0) postDelayed(longPress, 400);
                invalidate(); return true; }
            if (act == MotionEvent.ACTION_MOVE) {
                if (toneRow != null) { toneSel = Math.max(0, Math.min(toneRow.length - 1, (int) ((x - tonePx) / toneTs))); invalidate(); return true; }
                if (!dragging && Math.abs(y - downY) > dp(8)) { dragging = true; removeCallbacks(longPress); pressed = -1; }
                if (dragging) { scroll = Math.max(0, Math.min(maxScroll(), scroll - (y - lastY))); lastY = y; invalidate(); }
                return true; }
            if (act == MotionEvent.ACTION_UP) {
                removeCallbacks(longPress);
                if (toneRow != null) { String pick = toneSel >= 0 ? toneRow[toneSel] : null; toneRow = null; pressed = -1; invalidate(); if (pick != null) emit(pick); return true; }
                if (dragging) { dragging = false; invalidate(); return true; }
                float w = getWidth(), th = tabH(), by = getHeight() - bottomH();
                if (y < th) { setCategory(Math.min(CATS.length - 1, (int) (x / (w / CATS.length)))); return true; }
                if (y >= by) {
                    if (x < dp(90)) { if (listener != null) listener.onEmojiAbc(); }
                    else if (x > w - dp(90)) { if (listener != null) listener.onEmojiDelete(); }
                    else if (listener != null) listener.onEmoji(" ");
                    return true;
                }
                int i = cellAt(x, y); int was = pressed; pressed = -1; invalidate();
                if (i >= 0 && i == was) emit(items().get(i));
                return true;
            }
            if (act == MotionEvent.ACTION_CANCEL) { removeCallbacks(longPress); toneRow = null; pressed = -1; dragging = false; invalidate(); return true; }
        }
        return true;
    }
    void emit(String e) {
        if (!e.equals(" ")) addRecent(e);
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        if (listener != null) listener.onEmoji(e);
    }
    void addRecent(String e) {
        recent.remove(e); recent.add(0, e); while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        prefs.edit().putString("recent", String.join("\t", recent)).apply();
        if (cat == 0) invalidate();
    }
}
