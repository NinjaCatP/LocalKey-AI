package com.jason.aikeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

/** v7: replaces the keys while dictating: state line, animated level meter, live partial text, Cancel / Stop. */
public final class VoicePanel extends View {
    public interface Listener { void onVoiceStop(); void onVoiceCancel(); }
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), stopR = new RectF(), cancelR = new RectF();
    private final float[] hist = new float[48]; private int histPos;
    private String state = "loading", partial = "", engineLabel = "Local Whisper"; private Listener listener; private int forcedH = -1, pressed;
    public VoicePanel(Context c) { super(c); setBackgroundColor(KeyboardView.BG); }
    public void setListener(Listener l) { listener = l; }
    public void setForcedHeight(int h) { if (h != forcedH) { forcedH = h; requestLayout(); } }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), forcedH > 0 ? forcedH : (int) dp(250)); }
    public void setState(String s) { state = s; invalidate(); }
    public String state() { return state; }
    public void setEngineLabel(String s) { engineLabel = s; invalidate(); }
    private String error;
    /** v8: errors are shown in the panel (red) instead of failing silently; the panel stays open until closed. */
    public void setError(String e) { error = e; invalidate(); }
    public String error() { return error; }
    public void setPartial(String s) { partial = s == null ? "" : s; invalidate(); }
    public String partial() { return partial; }
    public void setLevel(float l) { hist[histPos++ % hist.length] = l; invalidate(); }
    public void clear() { java.util.Arrays.fill(hist, 0); partial = ""; error = null; }
    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        p.setTextAlign(Paint.Align.CENTER); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(dp(16));
        boolean listening = "listening".equals(state);
        p.setColor(listening ? 0xFFFF6B7A : 0xFF9BB3FF);
        String title = error != null && "idle".equals(state) ? "Voice typing problem" : listening ? "Listening… speak now" : "transcribing".equals(state) ? "Transcribing…" : "loading".equals(state) ? "Loading speech model…" : "Voice typing";
        c.drawText(title, w / 2, dp(28), p);
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(dp(11)); p.setColor(0xFF8E929B);
        c.drawText(engineLabel + " · on this phone, nothing is sent online", w / 2, dp(46), p);
        // level meter: rolling bars, newest on the right, mirrored around the centre line
        float mTop = dp(58), mH = dp(56), cy = mTop + mH / 2, bw = (w - dp(48)) / hist.length;
        for (int i = 0; i < hist.length; i++) {
            float v = hist[(histPos + i) % hist.length]; float bh = Math.max(dp(2), v * mH);
            p.setColor(listening ? (0xFF000000 | (Math.min(255, 120 + (int) (v * 135)) << 16) | 0x5A6E) : 0xFF3D4A6E);
            r.set(dp(24) + i * bw + bw * 0.2f, cy - bh / 2, dp(24) + (i + 1) * bw - bw * 0.2f, cy + bh / 2); c.drawRoundRect(r, dp(2), dp(2), p);
        }
        // partial text (last lines)
        float btnH = dp(52), tTop = mTop + mH + dp(8), tBottom = h - btnH - dp(16);
        tp.setColor(partial.isEmpty() ? 0xFF6B6F78 : 0xFFECEDEF); tp.setTextSize(dp(16));
        String shown = partial.isEmpty() ? (listening ? "Your words will appear here" : "") : partial;
        if (error != null && partial.isEmpty()) { shown = error; tp.setColor(0xFFFF6B7A); }
        StaticLayout sl = StaticLayout.Builder.obtain(shown, 0, shown.length(), tp, (int) (w - dp(40))).setAlignment(Layout.Alignment.ALIGN_CENTER).build();
        c.save(); c.clipRect(0, tTop, w, tBottom);
        float th = sl.getHeight(), y = th > tBottom - tTop ? tBottom - th : tTop + (tBottom - tTop - th) / 2;
        c.translate(dp(20), y); sl.draw(c); c.restore();
        // buttons
        float by = h - btnH - dp(10);
        cancelR.set(dp(16), by, w / 2 - dp(8), by + btnH); stopR.set(w / 2 + dp(8), by, w - dp(16), by + btnH);
        p.setColor(pressed == 1 ? 0xFF4A4E58 : 0xFF2B2D33); c.drawRoundRect(cancelR, dp(14), dp(14), p);
        p.setColor(pressed == 2 ? 0xFFD04A5A : 0xFFB03A48); c.drawRoundRect(stopR, dp(14), dp(14), p);
        p.setTextSize(dp(16)); p.setColor(0xFFECEDEF); float ty = by + btnH / 2 - (p.descent() + p.ascent()) / 2;
        c.drawText("Cancel", cancelR.centerX(), ty, p);
        p.setTypeface(Typeface.DEFAULT_BOLD); c.drawText(listening || "loading".equals(state) ? "■  Stop & insert" : "idle".equals(state) ? "Close" : "Inserting…", stopR.centerX(), ty, p); p.setTypeface(Typeface.DEFAULT);
        if (listening) postInvalidateDelayed(50);
    }
    public RectF stopRectForTest() { return stopR; }
    public RectF cancelRectForTest() { return cancelR; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked(); float x = e.getX(), y = e.getY();
        int hit = stopR.contains(x, y) ? 2 : cancelR.contains(x, y) ? 1 : 0;
        if (a == MotionEvent.ACTION_DOWN) { pressed = hit; invalidate(); }
        else if (a == MotionEvent.ACTION_UP) { int was = pressed; pressed = 0; invalidate();
            if (was == hit && listener != null) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); if (hit == 2) listener.onVoiceStop(); else if (hit == 1) listener.onVoiceCancel(); } }
        else if (a == MotionEvent.ACTION_CANCEL) { pressed = 0; invalidate(); }
        return true;
    }
}
