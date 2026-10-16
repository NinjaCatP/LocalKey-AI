package com.jason.aikeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.jason.aikeyboard.core.KeyboardLayout;
import com.jason.aikeyboard.core.KeyboardLayout.Key;

/** Canvas-drawn QWERTY with long-press alternates, backspace repeat and haptics. Dark theme. */
public final class KeyboardView extends View {
    public interface Listener { void onKey(int code); void onText(String text); void onSettings();
        /** v5: a finished swipe gesture over the letter keys (pixel coordinates). */ default void onSwipe(float[] xs, float[] ys, int n) {}
        /** v6: swipe still in progress (throttled ~70 ms); path so far. */ default void onSwipeProgress(float[] xs, float[] ys, int n) {} }
    public enum Shift { OFF, ON, CAPS }

    static final int BG = 0xFF121316, KEY = 0xFF2B2D33, KEY_MOD = 0xFF1E2025, KEY_PRESSED = 0xFF4A4E58, TEXT = 0xFFECEDEF, HINT = 0xFF8E929B, ACCENT = 0xFF7C9CFF;

    private KeyboardLayout layout = new KeyboardLayout(KeyboardLayout.Layer.LETTERS);
    private Listener listener; private Shift shift = Shift.OFF; private boolean haptics = true;
    private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG), textPaint = new Paint(Paint.ANTI_ALIAS_FLAG), hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Key pressed; private boolean longPressed;
    // alternates popup
    private Key popupKey; private String popupChars; private float popupX, popupY, popupCellW, popupCellH; private int popupSel = -1;
    // v5: swipe typing
    private boolean swipeEnabled = true, showTrail = true, swiping; private float downX, downY;
    private float[] sx = new float[512], sy = new float[512]; private long lastProgress; private long[] st = new long[512]; private int sn; private long trailEnd;
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public boolean isSwipingForLive() { return swiping; }
    public void setSwipe(boolean enabled, boolean trail) { swipeEnabled = enabled; showTrail = trail; }
    public boolean isSwiping() { return swiping; }
    private int enterAction = 0; private String enterLabel = "⏎";

    public KeyboardView(Context c) {
        super(c);
        textPaint.setColor(TEXT); textPaint.setTextAlign(Paint.Align.CENTER); textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hintPaint.setColor(HINT); hintPaint.setTextAlign(Paint.Align.RIGHT);
        setBackgroundColor(BG); setHapticFeedbackEnabled(true);
    }
    public void setListener(Listener l) { listener = l; }
    public void setHaptics(boolean h) { haptics = h; }
    public KeyboardLayout.Layer layer() { return layout.layer; }
    public void setLayer(KeyboardLayout.Layer l) { if (layout.layer != l) { layout = new KeyboardLayout(l); requestLayout(); relayout(); invalidate(); } }
    public Shift shift() { return shift; }
    public void setShift(Shift s) { if (shift != s) { shift = s; invalidate(); } }
    public void setEnterLabel(String label) { enterLabel = label; invalidate(); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int rowH = (int) dp(land ? 42 : 56);
        setMeasuredDimension(w, rowH * layout.rows.size() + (int) dp(6));
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { relayout(); }
    private void relayout() {
        if (getWidth() == 0) return;
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float side = land ? getWidth() * 0.08f : dp(2); // keep keys reachable on the wide landscape screen
        layout.layout(getWidth() - 2 * side, getHeight() - dp(6), dp(3));
        for (java.util.List<Key> r : layout.rows) for (Key k : r) { k.x += side; k.y += dp(3); }
    }

    private String labelFor(Key k) {
        if (k.code == KeyboardLayout.ENTER) return enterLabel;
        if (k.code == KeyboardLayout.SHIFT) return shift == Shift.CAPS ? "⇪" : "⇧";
        if (k.code > 0 && shift != Shift.OFF && layout.layer == KeyboardLayout.Layer.LETTERS) return k.label.toUpperCase();
        return k.label;
    }

    @Override protected void onDraw(Canvas c) {
        float r = dp(7);
        for (java.util.List<Key> row : layout.rows) for (Key k : row) {
            if (k.code == 0) continue;
            boolean mod = k.code < 0 || k.code == KeyboardLayout.ENTER;
            int col = k == pressed ? KEY_PRESSED : mod ? KEY_MOD : KEY;
            if (k.code == KeyboardLayout.ENTER) col = k == pressed ? 0xFF9BB3FF : ACCENT;
            if (k.code == KeyboardLayout.SHIFT && shift != Shift.OFF) col = 0xFF3D4A6E;
            keyPaint.setColor(col); rect.set(k.x, k.y, k.x + k.w, k.y + k.h); c.drawRoundRect(rect, r, r, keyPaint);
            String label = labelFor(k);
            float ts = label.length() > 2 ? dp(15) : (k.code == KeyboardLayout.SPACE ? dp(13) : dp(22));
            textPaint.setTextSize(ts); textPaint.setColor(k.code == KeyboardLayout.ENTER ? 0xFF0B0D12 : TEXT);
            if (k.code == KeyboardLayout.SPACE) { textPaint.setColor(HINT); label = "LocalKey AI"; }
            c.drawText(label, k.x + k.w / 2, k.y + k.h / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint);
            if (k.alternates != null && !k.alternates.isEmpty() && layout.layer == KeyboardLayout.Layer.LETTERS && k.code > 0) {
                hintPaint.setTextSize(dp(10)); String h = k.alternates.substring(0, 1);
                c.drawText(h, k.x + k.w - dp(4), k.y + dp(12), hintPaint);
            }
        }
        if (popupKey != null) drawPopup(c);
        drawTrail(c);
    }
    private static final long TRAIL_MS = 260;
    private void drawTrail(Canvas c) {
        if (!showTrail || sn < 2) return;
        long now = android.os.SystemClock.uptimeMillis(), ref = swiping ? now : trailEnd;
        float fade = swiping ? 1f : Math.max(0f, 1f - (now - trailEnd) / 220f);
        if (fade <= 0) { sn = 0; return; }
        trailPaint.setStyle(Paint.Style.STROKE); trailPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 1; i < sn; i++) {
            float age = Math.min(1f, (ref - st[i]) / (float) TRAIL_MS); // older segments fade and thin out
            float a = (1f - age) * fade; if (a <= 0.02f) continue;
            trailPaint.setColor(ACCENT); trailPaint.setAlpha((int) (230 * a)); trailPaint.setStrokeWidth(dp(2.5f) + dp(4f) * a);
            c.drawLine(sx[i - 1], sy[i - 1], sx[i], sy[i], trailPaint);
        }
        if (swiping || fade > 0) postInvalidateOnAnimation();
    }
    private void addPoint(float x, float y, long t) {
        if (sn == sx.length) { sx = java.util.Arrays.copyOf(sx, sn * 2); sy = java.util.Arrays.copyOf(sy, sn * 2); st = java.util.Arrays.copyOf(st, sn * 2); }
        sx[sn] = x; sy[sn] = y; st[sn] = t; sn++;
    }
    /** For tests/screenshots: feed a gesture through the real touch path. Returns true if it was treated as a swipe. */
    public boolean swipeForTest(float[] xs, float[] ys, boolean release) {
        long t = android.os.SystemClock.uptimeMillis();
        onTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, xs[0], ys[0], 0));
        for (int i = 1; i < xs.length; i++) onTouchEvent(MotionEvent.obtain(t, t + i * 8, MotionEvent.ACTION_MOVE, xs[i], ys[i], 0));
        boolean sw = swiping;
        if (release) onTouchEvent(MotionEvent.obtain(t, t + xs.length * 8, MotionEvent.ACTION_UP, xs[xs.length - 1], ys[ys.length - 1], 0));
        return sw;
    }
    /** v5: letter key centres + pitch for the swipe decoder (current geometry). */
    public java.util.Map<Character, float[]> letterCentres() { return layout.letterCentres(); }
    public float[] letterPitch() { return layout.letterPitch(); }
    private void drawPopup(Canvas c) {
        float r = dp(8);
        keyPaint.setColor(0xFF3A3D45); rect.set(popupX - dp(4), popupY - dp(4), popupX + popupCellW * popupChars.length() + dp(4), popupY + popupCellH + dp(4));
        c.drawRoundRect(rect, r, r, keyPaint);
        for (int i = 0; i < popupChars.length(); i++) {
            float x = popupX + i * popupCellW;
            if (i == popupSel) { keyPaint.setColor(ACCENT); rect.set(x, popupY, x + popupCellW, popupY + popupCellH); c.drawRoundRect(rect, r, r, keyPaint); }
            textPaint.setTextSize(dp(20)); textPaint.setColor(i == popupSel ? 0xFF0B0D12 : TEXT);
            String s = popupString(i);
            c.drawText(s, x + popupCellW / 2, popupY + popupCellH / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint);
        }
    }
    private String popupString(int i) {
        String s = String.valueOf(popupChars.charAt(i));
        return shift != Shift.OFF && layout.layer == KeyboardLayout.Layer.LETTERS ? s.toUpperCase() : s;
    }

    private void buzz(int kind) { if (haptics) performHapticFeedback(kind, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING); }

    private final Runnable longPress = new Runnable() { @Override public void run() {
        if (pressed == null) return;
        longPressed = true;
        if (pressed.code == KeyboardLayout.DELETE) { buzz(HapticFeedbackConstants.KEYBOARD_TAP); if (listener != null) listener.onKey(KeyboardLayout.DELETE); handler.postDelayed(this, 50); return; }
        if (pressed.code == KeyboardLayout.SPACE) return;
        if (pressed.code == ',' ) { buzz(HapticFeedbackConstants.LONG_PRESS); if (listener != null) listener.onSettings(); pressed = null; invalidate(); return; }
        if (pressed.alternates != null && !pressed.alternates.isEmpty()) {
            buzz(HapticFeedbackConstants.LONG_PRESS);
            popupKey = pressed; popupChars = pressed.label + pressed.alternates;
            popupCellW = Math.max(dp(34), pressed.w); popupCellH = pressed.h;
            float total = popupCellW * popupChars.length();
            popupX = Math.max(dp(2), Math.min(getWidth() - total - dp(2), pressed.x + pressed.w / 2 - popupCellW / 2));
            popupY = Math.max(0, pressed.y - popupCellH - dp(6)); popupSel = popupChars.length() > 1 ? 1 : 0;
            invalidate();
        }
    }};

    @Override public boolean onTouchEvent(MotionEvent e) {
        final int act = e.getActionMasked();
        {
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) {
                if (pressed != null && e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) commitPressed(); // fast two-thumb typing
                int i = e.getActionIndex();
                pressed = layout.keyAt(e.getX(i), e.getY(i)); longPressed = false; popupKey = null;
                if (act == MotionEvent.ACTION_DOWN) { swiping = false; sn = 0; downX = e.getX(); downY = e.getY(); addPoint(downX, downY, e.getEventTime()); }
                else if (swiping) { pressed = null; return true; } // second finger during a swipe: ignore
                if (pressed != null) {
                    buzz(HapticFeedbackConstants.KEYBOARD_TAP);
                    handler.removeCallbacks(longPress);
                    handler.postDelayed(longPress, pressed.code == KeyboardLayout.DELETE ? 400 : 350);
                }
                invalidate(); return true;
            }
            if (act == MotionEvent.ACTION_MOVE) {
                if (swipeEnabled && popupKey == null && e.getPointerCount() == 1 && layout.layer == KeyboardLayout.Layer.LETTERS
                        && (swiping || pressed != null && pressed.code >= 'a' && pressed.code <= 'z' && !longPressed)) {
                    for (int h = 0; h < e.getHistorySize(); h++) addPoint(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h));
                    addPoint(e.getX(), e.getY(), e.getEventTime());
                    if (!swiping) {
                        float[] pitch = layout.letterPitch(); float th = Math.max(dp(14), pitch[0] * 0.6f); // tap vs swipe: moved > ~0.6 key
                        if (Math.hypot(e.getX() - downX, e.getY() - downY) > th) { swiping = true; handler.removeCallbacks(longPress); pressed = null; }
                    }
                    if (swiping) {
                        if (listener != null && sn >= 4 && e.getEventTime() - lastProgress >= 70) { lastProgress = e.getEventTime(); listener.onSwipeProgress(java.util.Arrays.copyOf(sx, sn), java.util.Arrays.copyOf(sy, sn), sn); }
                        invalidate(); return true;
                    }
                }
                if (popupKey != null) {
                    int idx = (int) Math.floor((e.getX() - popupX) / popupCellW);
                    idx = Math.max(0, Math.min(popupChars.length() - 1, idx));
                    if (idx != popupSel) { popupSel = idx; invalidate(); }
                }
                return true;
            }
            if (swiping && (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP)) {
                if (act == MotionEvent.ACTION_POINTER_UP) return true;
                addPoint(e.getX(), e.getY(), e.getEventTime());
                swiping = false; trailEnd = android.os.SystemClock.uptimeMillis(); pressed = null;
                if (listener != null) listener.onSwipe(java.util.Arrays.copyOf(sx, sn), java.util.Arrays.copyOf(sy, sn), sn);
                invalidate(); return true;
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP) {
                if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && pressed != null && layout.keyAt(e.getX(e.getActionIndex()), e.getY(e.getActionIndex())) != pressed) return true;
                commitPressed(); return true;
            }
            if (act == MotionEvent.ACTION_CANCEL) {
                handler.removeCallbacks(longPress); pressed = null; popupKey = null; swiping = false; sn = 0; invalidate(); return true;
            }
        }
        return super.onTouchEvent(e);
    }
    private void commitPressed() {
        handler.removeCallbacks(longPress);
        Key k = pressed; pressed = null;
        if (k != null && listener != null) {
            if (popupKey != null) { if (popupSel >= 0) listener.onText(popupString(popupSel)); }
            else if (!(longPressed && (k.code == KeyboardLayout.DELETE || k.code == ','))) {
                listener.onKey(k.code);
            }
        }
        popupKey = null; longPressed = false; invalidate();
    }
    /** For tests/screenshots: simulate the long-press popup on a key label. */
    public void showAlternatesFor(char c) {
        for (java.util.List<Key> r : layout.rows) for (Key k : r) if (k.code == c) { pressed = k; longPress.run(); return; }
    }
    public KeyboardLayout layoutForTest() { return layout; }
}
