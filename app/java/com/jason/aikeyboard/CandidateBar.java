package com.jason.aikeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Top strip: ✨ AI button, paste, emoji (fixed) + a horizontally scrollable strip of suggestions (v5), or the AI action chips. */
public final class CandidateBar extends View {
    public interface Listener { void onSuggestion(int index, String word); void onAiButton(); void onAiAction(int index);
        /** v3: paste button next to ✨ */ void onPaste(); String clipboardPreview(); /** v3: emoji panel */ void onEmojiButton(); /** v7: voice typing */ default void onMicButton() {} }
    static final int PASTE = -3, EMOJI = -4, MIC = -5;
    private boolean micActive; private float micLevel;
    public void setMicActive(boolean a) { if (a != micActive) { micActive = a; invalidate(); } }
    public boolean micActive() { return micActive; }
    private String voiceText;
    /** v7: live dictation text shown in the strip (null = normal suggestions) */
    public void setVoiceText(String t) { voiceText = t; invalidate(); }
    public String voiceText() { return voiceText; }
    public void setMicLevel(float l) { micLevel = l; if (micActive) invalidate(); }
    private final android.os.Handler lp = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean emojiActive;
    public void setEmojiActive(boolean a) { if (a != emojiActive) { emojiActive = a; invalidate(); } }
    private boolean pasteEnabled; private String pastePreview;
    private final Runnable longPress = () -> { if (this.pressedSlot == PASTE && this.listener != null) { String t = this.listener.clipboardPreview(); this.pastePreview = t == null || t.isEmpty() ? "Clipboard is empty" : t.replace('\n', ' '); invalidate(); } };
    public void setPasteEnabled(boolean e) { if (e != pasteEnabled) { pasteEnabled = e; invalidate(); } }
    public boolean pasteEnabled() { return pasteEnabled; }
    public String pastePreviewShown() { return pastePreview; }
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(); private final Paint fade = new Paint();
    private List<String> words = new ArrayList<>(); private int primary = -1, typedIdx = -1;
    private String[] chips; private int pressedSlot = -2; private Listener listener; private String status;

    public CandidateBar(Context c) { super(c); setBackgroundColor(0xFF17181C); p.setTextAlign(Paint.Align.CENTER); scroller = new android.widget.OverScroller(c); slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop(); }
    public void setListener(Listener l) { listener = l; }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(44)); }

    public void setSuggestions(List<String> w, int primaryIdx, int typedIndex) { setSuggestions(w, primaryIdx, typedIndex, -1); }
    private int currentIdx = -1;
    public int currentIndex() { return currentIdx; }
    /** v6: currentIndex = the word already in the text (e.g. just swiped); drawn bold on an accent pill. */
    public void setSuggestions(List<String> w, int primaryIdx, int typedIndex, int currentIndex) { currentIdx = currentIndex; words = new ArrayList<>(w); primary = primaryIdx; typedIdx = typedIndex; chips = null; scrollPos = 0; scroller.forceFinished(true); slotX = null; invalidate(); }
    // ---------------- v5: scrollable suggestion strip ----------------
    private final android.widget.OverScroller scroller; private android.view.VelocityTracker vt;
    private float scrollPos, downX, lastX; private boolean dragging, stripTouch; private final int slop;
    private float[] slotX; private float contentW; private int slotW0 = -1;
    /** x offset (relative to strip start) of each slot, slot i spans slotX[i]..slotX[i+1]; first 3 keep the classic thirds. */
    private void layoutSlots() {
        float avail = getWidth() - leftW(); int w = getWidth();
        if (slotX != null && slotW0 == w) return;
        int n = Math.max(3, words.size()); slotX = new float[n + 1]; float minW = avail / 3f, x = 0;
        p.setTextSize(dp(16)); p.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i = 0; i < n; i++) {
            float tw = i < words.size() ? p.measureText(i == typedIdx ? "\u201C" + words.get(i) + "\u201D" : words.get(i)) + dp(24) : 0;
            slotX[i] = x; x += Math.max(minW, Math.min(tw, avail * 0.8f));
        }
        slotX[n] = x; contentW = x; slotW0 = w; p.setTypeface(Typeface.DEFAULT);
    }
    private float maxScroll() { layoutSlots(); return Math.max(0, contentW - (getWidth() - leftW())); }
    public float scrollPosForTest() { return scrollPos; }
    public float maxScrollForTest() { return maxScroll(); }
    public void scrollToForTest(float x) { scrollPos = Math.max(0, Math.min(maxScroll(), x)); invalidate(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { slotX = null; }
    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) { scrollPos = Math.max(0, Math.min(maxScroll(), scroller.getCurrX())); postInvalidateOnAnimation(); }
    }
    public void showChips(String[] c) { chips = c; invalidate(); }
    public boolean chipsShown() { return chips != null; }
    public void setStatus(String s) { status = s; invalidate(); }
    public List<String> words() { return words; }
    int typedIndexForTest() { return typedIdx; }

    private float aiW() { return dp(48); }
    private float pasteW() { return chips != null ? 0 : dp(42); }
    private float emojiW() { return chips != null ? 0 : dp(42); }
    private float micW() { return chips != null ? 0 : dp(42); }
    /** left edge of the suggestion / chip area */
    private float leftW() { return aiW() + pasteW() + emojiW() + micW(); }
    @Override protected void onDraw(Canvas c) {
        float h = getHeight(), aw = aiW();
        // AI button
        bg.setColor(pressedSlot == -1 ? 0xFF3D4A6E : 0xFF262A36); rect.set(dp(4), dp(5), aw - dp(2), h - dp(5)); c.drawRoundRect(rect, dp(16), dp(16), bg);
        p.setColor(0xFF9BB3FF); p.setTextSize(dp(chips != null ? 18 : 18)); p.setTypeface(Typeface.DEFAULT);
        c.drawText(chips != null ? "✕" : "✨", aw / 2 + dp(1), h / 2 - (p.descent() + p.ascent()) / 2, p);
        if (chips == null) {
            drawPaste(c, aw, h);
            float ex = aw + pasteW(), ew = emojiW();
            bg.setColor(pressedSlot == EMOJI || emojiActive ? 0xFF3D4A6E : 0xFF262A36); rect.set(ex + dp(2), dp(5), ex + ew - dp(2), h - dp(5)); c.drawRoundRect(rect, dp(16), dp(16), bg);
            p.setTextSize(dp(18)); p.setColor(0xFFFFFFFF); c.drawText("😊", ex + ew / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
            drawMic(c, ex + ew, h);
        }
        aw = leftW();
        if (pastePreview != null) { // long-press preview of the clipboard
            bg.setColor(0xFF2B2D33); rect.set(aw + dp(4), dp(5), getWidth() - dp(4), h - dp(5)); c.drawRoundRect(rect, dp(12), dp(12), bg);
            p.setColor(0xFFECEDEF); p.setTextSize(dp(14));
            CharSequence t = TextUtils.ellipsize(pastePreview, new android.text.TextPaint(p), getWidth() - aw - dp(20), TextUtils.TruncateAt.END);
            c.drawText(t.toString(), (getWidth() + aw) / 2, h / 2 - (p.descent() + p.ascent()) / 2, p); return;
        }
        if (voiceText != null && chips == null) {
            String vt = voiceText.isEmpty() ? "Listening…" : voiceText;
            p.setColor(voiceText.isEmpty() ? 0xFF8E929B : 0xFFFFFFFF); p.setTextSize(dp(15)); p.setTextAlign(Paint.Align.LEFT);
            CharSequence t = TextUtils.ellipsize(vt, new android.text.TextPaint(p), getWidth() - aw - dp(16), TextUtils.TruncateAt.START);
            c.drawText(t.toString(), aw + dp(8), h / 2 - (p.descent() + p.ascent()) / 2, p); p.setTextAlign(Paint.Align.CENTER); return;
        }
        if (status != null && chips == null && words.isEmpty()) { p.setColor(0xFF8E929B); p.setTextSize(dp(13)); c.drawText(status, (getWidth() + aw) / 2, h / 2 - (p.descent() + p.ascent()) / 2, p); return; }
        if (chips != null) {
            float cw = (getWidth() - aw - dp(4)) / chips.length;
            for (int i = 0; i < chips.length; i++) {
                float x = aw + dp(2) + i * cw;
                bg.setColor(pressedSlot == i ? 0xFF4A4E58 : 0xFF2B2D33); rect.set(x + dp(3), dp(6), x + cw - dp(3), h - dp(6)); c.drawRoundRect(rect, dp(14), dp(14), bg);
                p.setColor(0xFFECEDEF); p.setTextSize(dp(13));
                c.drawText(chips[i], x + cw / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
            }
            return;
        }
        layoutSlots();
        c.save(); c.clipRect(aw, 0, getWidth(), h);
        int n = slotX.length - 1;
        for (int i = 0; i < n; i++) {
            float x = aw + slotX[i] - scrollPos, cw = slotX[i + 1] - slotX[i];
            if (x + cw < aw || x > getWidth()) continue;
            if (i > 0) { bg.setColor(0xFF2B2D33); c.drawRect(x, h * 0.3f, x + dp(1), h * 0.7f, bg); }
            if (i >= words.size()) continue;
            if (i == currentIdx) { bg.setColor(0xFF2F4A7A); rect.set(x + dp(3), dp(5), x + cw - dp(3), h - dp(5)); c.drawRoundRect(rect, dp(9), dp(9), bg); }
            if (pressedSlot == i) { bg.setColor(0xFF2B2D33); rect.set(x + dp(2), dp(4), x + cw - dp(2), h - dp(4)); c.drawRoundRect(rect, dp(8), dp(8), bg); }
            String w = words.get(i); if (i == typedIdx) w = "\u201C" + w + "\u201D";
            p.setTextSize(dp(16)); p.setColor(i == primary || i == currentIdx ? 0xFFFFFFFF : 0xFFC9CCD3);
            p.setTypeface(i == primary || i == currentIdx ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            CharSequence t = TextUtils.ellipsize(w, new android.text.TextPaint(p), cw - dp(8), TextUtils.TruncateAt.END);
            c.drawText(t.toString(), x + cw / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
        }
        c.restore();
        // edge fades hint that the strip scrolls
        float fw = dp(22), ms = maxScroll();
        if (scrollPos > 1) { fade.setShader(new android.graphics.LinearGradient(aw, 0, aw + fw, 0, 0xFF17181C, 0x0017181C, android.graphics.Shader.TileMode.CLAMP)); c.drawRect(aw, 0, aw + fw, h, fade); }
        if (scrollPos < ms - 1) { fade.setShader(new android.graphics.LinearGradient(getWidth() - fw, 0, getWidth(), 0, 0x0017181C, 0xFF17181C, android.graphics.Shader.TileMode.CLAMP)); c.drawRect(getWidth() - fw, 0, getWidth(), h, fade);
            p.setColor(0xFF8E929B); p.setTextSize(dp(12)); p.setTypeface(Typeface.DEFAULT); c.drawText("›", getWidth() - dp(7), h / 2 - (p.descent() + p.ascent()) / 2, p); }
        p.setTypeface(Typeface.DEFAULT);
    }
    private void drawPaste(Canvas c, float x0, float h) {
        float w = pasteW();
        bg.setColor(pressedSlot == PASTE && pasteEnabled ? 0xFF3D4A6E : 0xFF262A36); rect.set(x0 + dp(2), dp(5), x0 + w - dp(2), h - dp(5)); c.drawRoundRect(rect, dp(16), dp(16), bg);
        // clipboard icon: board + clip
        int col = pasteEnabled ? 0xFF9BB3FF : 0xFF4A4E58;
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG); st.setStyle(Paint.Style.STROKE); st.setStrokeWidth(dp(1.8f)); st.setColor(col);
        float cx = x0 + w / 2, cy = h / 2, bw = dp(7), bh = dp(9);
        rect.set(cx - bw, cy - bh + dp(1), cx + bw, cy + bh); c.drawRoundRect(rect, dp(2), dp(2), st);
        st.setStyle(Paint.Style.FILL); rect.set(cx - dp(3.5f), cy - bh - dp(1.5f), cx + dp(3.5f), cy - bh + dp(3)); c.drawRoundRect(rect, dp(1.5f), dp(1.5f), st);
        st.setStrokeWidth(dp(1.4f)); for (int i = 0; i < 3; i++) { float y = cy - dp(1) + i * dp(3.5f); c.drawLine(cx - dp(4), y, cx + (i == 2 ? dp(1) : dp(4)), y, st); }
    }
    private void drawMic(Canvas c, float x0, float h) {
        float w = micW();
        bg.setColor(micActive ? 0xFFB03A48 : pressedSlot == MIC ? 0xFF3D4A6E : 0xFF262A36); rect.set(x0 + dp(2), dp(5), x0 + w - dp(2), h - dp(5)); c.drawRoundRect(rect, dp(16), dp(16), bg);
        if (micActive && micLevel > 0.02f) { bg.setColor(0x55FFFFFF); float r = dp(6) + micLevel * dp(10); c.drawCircle(x0 + w / 2, h / 2, r, bg); }
        p.setTextSize(dp(17)); p.setColor(0xFFFFFFFF); c.drawText("🎤", x0 + w / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
    }
    private int slotAt(float x) {
        if (x < aiW()) return -1;
        if (chips == null && x < aiW() + pasteW()) return PASTE;
        if (chips == null && x < aiW() + pasteW() + emojiW()) return EMOJI;
        if (chips == null && x < leftW()) return MIC;
        float aw = leftW();
        if (chips != null) { int n = chips.length; float cw = (getWidth() - aw) / n; return Math.max(0, Math.min(n - 1, (int) ((x - aw) / cw))); }
        layoutSlots(); float sx = x - aw + scrollPos; int n = slotX.length - 1;
        for (int i = 0; i < n; i++) if (sx < slotX[i + 1]) return i;
        return n - 1;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        // v5: horizontal drag / fling of the suggestion strip (fixed buttons on the left are not part of it)
        if (a == MotionEvent.ACTION_DOWN) { stripTouch = chips == null && pastePreview == null && voiceText == null && e.getX() >= leftW(); dragging = false; downX = lastX = e.getX();
            if (stripTouch) { scroller.forceFinished(true); if (vt != null) vt.recycle(); vt = android.view.VelocityTracker.obtain(); vt.addMovement(e); } }
        if (stripTouch && vt != null && a != MotionEvent.ACTION_DOWN) vt.addMovement(e);
        if (a == MotionEvent.ACTION_MOVE && stripTouch) {
            if (!dragging && Math.abs(e.getX() - downX) > slop && maxScroll() > 0) { dragging = true; pressedSlot = -2; getParent().requestDisallowInterceptTouchEvent(true); }
            if (dragging) { scrollPos = Math.max(0, Math.min(maxScroll(), scrollPos - (e.getX() - lastX))); invalidate(); }
            lastX = e.getX(); return true;
        }
        if (a == MotionEvent.ACTION_UP && dragging) {
            dragging = false; stripTouch = false;
            vt.computeCurrentVelocity(1000); float v = vt.getXVelocity(); vt.recycle(); vt = null;
            scroller.fling((int) scrollPos, 0, (int) -v, 0, 0, (int) maxScroll(), 0, 0); postInvalidateOnAnimation(); pressedSlot = -2; invalidate();
            return true;
        }
        if ((a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) && vt != null) { vt.recycle(); vt = null; dragging = false; }
        if (a == MotionEvent.ACTION_DOWN) { pressedSlot = slotAt(e.getX()); if (pressedSlot == PASTE) lp.postDelayed(longPress, 450); invalidate(); return true; }
        if (a == MotionEvent.ACTION_UP) {
            lp.removeCallbacks(longPress);
            boolean wasPreview = pastePreview != null; pastePreview = null;
            int s = slotAt(e.getX()); boolean same = s == pressedSlot; pressedSlot = -2; invalidate();
            if (!same || listener == null || wasPreview) return true;
            if (s == MIC) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); listener.onMicButton(); return true; }
            if (s == EMOJI) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); listener.onEmojiButton(); return true; }
            if (s == PASTE) { if (pasteEnabled) { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); listener.onPaste(); } return true; }
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            if (s == -1) listener.onAiButton();
            else if (chips != null) listener.onAiAction(s);
            else if (voiceText == null && s < words.size()) listener.onSuggestion(s, words.get(s));
            return true;
        }
        if (a == MotionEvent.ACTION_CANCEL) { lp.removeCallbacks(longPress); pastePreview = null; pressedSlot = -2; invalidate(); }
        return true;
    }
}
