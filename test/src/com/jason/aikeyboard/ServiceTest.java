package com.jason.aikeyboard;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jason.aikeyboard.ai.AiEngine;
import com.jason.aikeyboard.core.KeyboardLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h915dp-port-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ServiceTest {
    /** Service wired to a real EditText's InputConnection. */
    public static class TestService extends AIKeyboardService {
        EditText et; InputConnection ic; EditorInfo ei;
        @Override public InputConnection getCurrentInputConnection() { return ic; }
        @Override public EditorInfo getCurrentInputEditorInfo() { return ei; }
    }
    TestService s; EditText et; View input; Activity act;
    static final String OUT = System.getProperty("aik.out", "/workspace/aikeyboard/shots");

    static final com.jason.aikeyboard.core.UserModel.Codec XOR = new com.jason.aikeyboard.core.UserModel.Codec() {
        public byte[] encode(byte[] p) { byte[] o = p.clone(); for (int i = 0; i < o.length; i++) o[i] ^= 0x5A; return o; } public byte[] decode(byte[] x) { return encode(x); } };
    @Before public void setUp() {
        Learned.resetForTests(); Learned.setCodecForTests(XOR); // Robolectric has no AndroidKeyStore
        act = Robolectric.buildActivity(Activity.class).setup().get();
        et = new EditText(act); et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        act.setContentView(et); et.requestFocus();
        s = Robolectric.buildService(TestService.class).create().get();
        s.ei = new EditorInfo(); s.ei.inputType = et.getInputType(); s.ic = et.onCreateInputConnection(s.ei); s.ei.inputType = et.getInputType();
        input = s.onCreateInputView();
        s.onStartInputView(s.ei, false);
    }
    void type(String str) { for (char c : str.toCharArray()) { if (c == '\b') s.onKey(KeyboardLayout.DELETE); else s.onKey(c); } ShadowLooper.idleMainLooper(); }
    String text() { return et.getText().toString(); }

    @Test public void typingAutocorrectAndUndo() {
        type("i dont think its tomorow ");
        assertEquals("I don't think its tomorrow ", text());
        type("\b"); assertEquals("I don't think its tomorow", text()); // backspace undoes autocorrect
    }
    @Test public void autoCapitalAndShiftCycle() {
        assertEquals(KeyboardView.Shift.ON, s.keyboardForTest().shift());
        type("hello"); assertEquals("Hello", text()); assertEquals(KeyboardView.Shift.OFF, s.keyboardForTest().shift());
        type(". "); type("ok"); assertEquals("Hello. Ok", text());
    }
    @Test public void punctuationSwapsAutoSpaceAndDoubleSpace() {
        s.onSuggestion(0, s.barForTest().words().get(0)); type("great");
        type(" ");
        type(" "); assertTrue(text(), text().endsWith("great. "));
    }
    @Test public void suggestionsShowAndTapCommits() {
        type("tomo"); assertTrue(s.barForTest().words().toString(), s.barForTest().words().contains("Tomorrow"));
        int i = s.barForTest().words().indexOf("Tomorrow"); s.onSuggestion(i, "Tomorrow");
        assertEquals("Tomorrow ", text());
    }
    @Test public void symbolsLayerAndDelete() {
        s.onKey(KeyboardLayout.SYMBOLS); assertEquals(KeyboardLayout.Layer.SYMBOLS, s.keyboardForTest().layer());
        type("12"); s.onKey(' '); assertEquals(KeyboardLayout.Layer.LETTERS, s.keyboardForTest().layer());
        type("\b\b"); assertEquals("1", text());
    }
    @Test public void passwordFieldsNeverLearnOrSuggest() {
        s.ei.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD; s.onStartInputView(s.ei, false);
        type("secretword"); assertTrue(s.barForTest().words().isEmpty());
    }

    // ---------- screenshots ----------
    static void shot(View v, int w, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
        Bitmap b = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(b)); new File(OUT).mkdirs();
        try (FileOutputStream o = new FileOutputStream(OUT + "/" + name + ".png")) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }
    @Test public void screenshots() throws Exception {
        int W = 1440; // S24 Ultra portrait width (px) at xxxhdpi-ish density
        type("Thank you so "); shot(input, W, "01_letters_next_word");
        type("much for the tomo"); shot(input, W, "02_completion");
        et.setText(""); s.onStartInputView(s.ei, false);
        type("see you tomorow"); shot(input, W, "03_autocorrect_pending");
        s.keyboardForTest().setShift(KeyboardView.Shift.ON); shot(input, W, "04_shift");
        s.keyboardForTest().showAlternatesFor('e'); shot(input, W, "05_long_press_alternates");
        s.keyboardForTest().onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_CANCEL, 0, 0, 0));
        s.keyboardForTest().setShift(KeyboardView.Shift.OFF);
        s.onKey(KeyboardLayout.SYMBOLS); shot(input, W, "06_symbols"); s.onKey(KeyboardLayout.MORE_SYMBOLS); shot(input, W, "07_more_symbols"); s.onKey(KeyboardLayout.ALPHA);
        s.onAiButton(); shot(input, W, "08_ai_actions");
        s.onAiButton();
        s.showPreviewForTest("Fix grammar (sample text)", "I don't think they're going to come tomorrow because they were busy.");
        shot(input, W, "09_ai_preview");
    }
    @Test @Config(qualifiers = "w915dp-h411dp-land-xxxhdpi") public void screenshotLandscape() throws Exception {
        type("Let me know if you "); shot(input, 3120, "10_landscape");
    }
    @Test public void settingsScreenshot() throws Exception {
        Activity a = Robolectric.buildActivity(Settings.class).setup().get();
        View root = a.getWindow().getDecorView();
        shot(((android.view.ViewGroup) root.findViewById(android.R.id.content)).getChildAt(0), 1440, "11_settings");
    }


    // ---------- v2: nav bar insets ----------
    static android.view.WindowInsets navInsets(int l, int r, int b, int cutL) {
        return new android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(l, 0, r, b))
            .setInsets(android.view.WindowInsets.Type.displayCutout(), android.graphics.Insets.of(cutL, 0, 0, 0)).build();
    }
    /** Renders the input view, then paints a simulated nav bar (translucent + icons) over the inset zone, like the system does. */
    void insetShot(int w, android.view.WindowInsets wi, int l, int r, int b, String name) throws Exception {
        input.dispatchApplyWindowInsets(wi);
        input.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        input.layout(0, 0, input.getMeasuredWidth(), input.getMeasuredHeight());
        int H = input.getHeight();
        View kb = s.keyboardForTest(); int[] lk = new int[2]; kb.getLocationInWindow(lk);
        int kbBottom = kb.getBottom() + ((View) kb.getParent()).getTop();
        assertEquals(name + " bottom padding", b, input.getPaddingBottom());
        assertTrue(name + ": keyboard bottom " + kbBottom + " must be above inset top " + (H - b), kbBottom <= H - b);
        assertEquals(l, kb.getLeft()); assertEquals(w - r, kb.getRight());
        Bitmap bm = Bitmap.createBitmap(w, H, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(bm); input.draw(c);
        android.graphics.Paint p = new android.graphics.Paint(); p.setColor(0x55FF3B30);
        if (b > 0) c.drawRect(0, H - b, w, H, p);
        if (r > 0) c.drawRect(w - r, 0, w, H, p);
        if (l > 0) c.drawRect(0, 0, l, H, p);
        p.setColor(0xFFFFFFFF); p.setStrokeWidth(6); p.setStyle(android.graphics.Paint.Style.STROKE);
        if (b >= 150) { float cy = H - b / 2f; c.drawRect(w*0.25f-30, cy-30, w*0.25f+30, cy+30, p); c.drawCircle(w*0.5f, cy, 34, p); c.drawLine(w*0.75f-30, cy, w*0.75f+30, cy, p); }
        else if (b > 0) { float cy = H - b / 2f; c.drawLine(w*0.4f, cy, w*0.6f, cy, p); }
        new File(OUT).mkdirs();
        try (FileOutputStream o = new FileOutputStream(OUT + "/" + name + ".png")) { bm.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }
    @Test public void insetsThreeButtonNav() throws Exception { int b = 48 * 4; insetShot(1440, navInsets(0, 0, b, 0), 0, 0, b, "v2_01_three_button_nav"); }
    @Test public void insetsGestureNav() throws Exception { int b = 20 * 4; insetShot(1440, navInsets(0, 0, b, 0), 0, 0, b, "v2_02_gesture_nav"); }
    @Test public void insetsNone() throws Exception { int b = s.navBarResourceHeight(); assertTrue(b > 0); insetShot(1440, navInsets(0, 0, 0, 0), 0, 0, b, "v4_03_zero_insets_fallback"); }
    @Test public void v4NavBar48dp() throws Exception { int b = 48 * 4; insetShot(1440, navInsets(0, 0, b, 0), 0, 0, b, "v4_navbar"); }
    @Test public void v4HiddenNavStillPadded() throws Exception {
        int b = 48 * 4;
        android.view.WindowInsets wi = new android.view.WindowInsets.Builder()
            .setInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, b))
            .setVisible(android.view.WindowInsets.Type.navigationBars(), false).build();
        input.dispatchApplyWindowInsets(wi); assertEquals(b, input.getPaddingBottom());
    }
    @Test public void v4EmojiPanelAboveNavBar() throws Exception {
        int b = 48 * 4; s.onEmojiButton();
        input.dispatchApplyWindowInsets(navInsets(0, 0, b, 0));
        input.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        input.layout(0, 0, input.getMeasuredWidth(), input.getMeasuredHeight());
        View e = null; for (int i = 0; i < ((android.view.ViewGroup) input).getChildCount(); i++) if (((android.view.ViewGroup) input).getChildAt(i) instanceof EmojiPanel) e = ((android.view.ViewGroup) input).getChildAt(i);
        assertEquals(View.VISIBLE, e.getVisibility());
        assertTrue("emoji bottom above nav", e.getBottom() <= input.getHeight() - b);
        shot(input, 1440, "v4_emoji_navbar");
    }
    @Test @Config(qualifiers = "w915dp-h411dp-land-xxxhdpi") public void insetsLandscape() throws Exception {
        int r = 48 * 4, cut = 32 * 4; insetShot(3120, navInsets(0, r, 0, cut), cut, r, 0, "v2_04_landscape_side_nav_cutout");
    }

    // ---------- v3: personal learning through the real service ----------
    void restartService() {
        s.onFinishInputView(true); Learned.resetForTests();
        s = Robolectric.buildService(TestService.class).create().get();
        s.ei = new EditorInfo(); s.ei.inputType = et.getInputType(); s.ic = et.onCreateInputConnection(s.ei); s.ei.inputType = et.getInputType();
        input = s.onCreateInputView(); s.onStartInputView(s.ei, false);
    }
    @Test public void typingSessionLearnsAndPersistsAcrossRestart() {
        type("Can you merge the ");
        java.util.List<String> before = new java.util.ArrayList<>(s.barForTest().words());
        for (int i = 0; i < 3; i++) { type("Can you merge the dead space files with termux. "); }
        et.setText(""); s.onStartInputView(s.ei, false);
        type("Can you merge the ");
        java.util.List<String> after = s.barForTest().words();
        System.out.println("[service] next-word strip for 'Can you merge the ' before " + before + " after 3 sessions " + after);
        assertFalse(before.toString().toLowerCase().contains("dead"));
        assertEquals("dead", after.get(0).toLowerCase());
        restartService(); // process restart
        assertTrue(new File(RuntimeEnvironment.getApplication().getFilesDir(), Learned.FILE).exists());
        assertTrue(new File(RuntimeEnvironment.getApplication().getFilesDir(), Learned.PHRASES).exists());
        et.setText(""); s.onStartInputView(s.ei, false); type("Can you merge the ");
        assertEquals("dead", s.barForTest().words().get(0).toLowerCase());
        assertTrue(s.userForTest().phraseCount() >= 1);
    }
    @Test public void backspacingAutocorrectTwiceStopsIt() {
        type("play galcon "); assertEquals("Play falcon ", text());
        type("\b"); assertEquals("Play galcon", text());
        type(" now. Play galcon "); assertEquals("Play galcon now. Play falcon ", text());
        type("\b"); type(" ok. ");
        et.setText(""); s.onStartInputView(s.ei, false);
        type("play galcon "); assertEquals("Play galcon ", text());
        assertTrue(s.userForTest().neverCorrect("galcon"));
    }
    @Test public void tappingTypedWordRejectsAutocorrect() {
        for (int i = 0; i < 2; i++) {
            et.setText(""); s.onStartInputView(s.ei, false); type("open dynarmic");
            java.util.List<String> w = s.barForTest().words(); assertEquals("dynarmic", w.get(0)); s.onSuggestion(0, w.get(0));
        }
        et.setText(""); s.onStartInputView(s.ei, false); type("open dynarmic ");
        assertEquals("Open dynarmic ", text());
    }
    @Test public void personalPhrasesGoIntoAiPromptOnlyWhenAllowed() {
        type("Put the game in the new games folder please. ");
        String c = s.aiContext("Did you put the ");
        System.out.println("[service] AI prompt context: " + c.replace("\n", " | "));
        assertTrue(c, c.startsWith("Put the game in the new games folder please"));
        assertTrue(c.endsWith("Did you put the ")); assertTrue(c.length() <= 160 + 20);
        Settings.prefs(s).edit().putBoolean(Settings.PERSONALIZE_AI, false).commit(); s.onStartInputView(s.ei, false);
        assertEquals("Did you put the ", s.aiContext("Did you put the "));
    }
    @Test public void privacyNoLearningInPasswordOrIncognitoOrWhenOff() {
        s.ei.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD; s.onStartInputView(s.ei, false);
        type("hunter2secret password. "); s.onFinishInputView(true);
        assertEquals(0, s.userForTest().size()); assertEquals(0, s.userForTest().phraseCount());
        s.ei.inputType = InputType.TYPE_CLASS_TEXT; s.ei.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING; s.onStartInputView(s.ei, false);
        type("my private galcon message here. "); type("\b"); s.onFinishInputView(true);
        assertEquals(0, s.userForTest().size()); assertEquals(0, s.userForTest().phraseCount()); assertFalse(s.userForTest().neverCorrect("galcon"));
        assertEquals("Did you ", s.aiContext("Did you "));
        s.ei.imeOptions = 0; Settings.prefs(s).edit().putBoolean(Settings.LEARN, false).commit(); s.onStartInputView(s.ei, false);
        type("learning is off now. "); s.onFinishInputView(true);
        assertEquals(0, s.userForTest().size());
        assertFalse(new File(RuntimeEnvironment.getApplication().getFilesDir(), Learned.PHRASES).exists());
    }
    @Test public void clearAllLearnedData() {
        type("Can you add unlimited gold to caligo. "); s.onFinishInputView(true);
        File dir = RuntimeEnvironment.getApplication().getFilesDir();
        assertTrue(new File(dir, Learned.FILE).exists()); assertTrue(s.userForTest().size() > 0);
        Learned.clearAll(s);
        assertFalse(new File(dir, Learned.FILE).exists()); assertFalse(new File(dir, Learned.PHRASES).exists());
        assertEquals(0, s.userForTest().size()); assertEquals(0, s.userForTest().phraseCount());
        Learned.resetForTests(); assertEquals(0, Learned.get(s).size());
    }
    @Test public void v3SettingsScreenshot() throws Exception {
        for (String m : Corpus.messages(11, 40)) type(m + ". ");
        type("play galcon "); type("\b"); type(". play galcon "); type("\b"); type(". ");
        s.onFinishInputView(true);
        Activity a = Robolectric.buildActivity(Settings.class).setup().resume().get();
        View root = a.getWindow().getDecorView();
        android.widget.ScrollView sv = (android.widget.ScrollView) ((android.view.ViewGroup) root.findViewById(android.R.id.content)).getChildAt(0);
        String all = dumpText(sv);
        assertTrue(all.contains("Learn from my typing")); assertTrue(all.contains("Personalize AI")); assertTrue(all.contains("Clear all learned data"));
        assertTrue(all.contains("Export learned data")); assertTrue(all.contains("Import learned data")); assertTrue(all, all.contains("1 words no longer autocorrected"));
        shot(sv, 1440, "v3_11_settings_full");
        // crop: the personal learning section
        View lin = sv.getChildAt(0); int top = 0;
        for (int i = 0; i < ((android.view.ViewGroup) lin).getChildCount(); i++) { View c = ((android.view.ViewGroup) lin).getChildAt(i);
            if (c instanceof TextView && ((TextView) c).getText().toString().equals("Personal learning")) { top = c.getTop(); break; } }
        int y0 = Math.max(0, top - 300), h = Math.min(sv.getHeight() - y0, 2600);
        android.graphics.Bitmap crop = android.graphics.Bitmap.createBitmap(sv.getWidth(), h, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(crop); cc.drawColor(0xFF121316); cc.translate(0, -y0); sv.draw(cc);
        try (FileOutputStream o = new FileOutputStream(OUT + "/v3_12_settings_learning.png")) { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); }
    }
    static String dumpText(View v) {
        if (v instanceof TextView) return ((TextView) v).getText() + "\n";
        StringBuilder sb = new StringBuilder();
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) sb.append(dumpText(((android.view.ViewGroup) v).getChildAt(i)));
        return sb.toString();
    }

    // ---------- v3: paste button ----------
    android.content.ClipboardManager cm() { return (android.content.ClipboardManager) RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE); }
    @Test public void pasteButton() throws Exception {
        cm().clearPrimaryClip(); s.onStartInputView(s.ei, false);
        assertFalse(s.barForTest().pasteEnabled());
        shot(input, 1440, "v3_02_paste_disabled");
        cm().setPrimaryClip(android.content.ClipData.newPlainText("x", "unique pastedword here"));
        ShadowLooper.idleMainLooper(); s.onStartInputView(s.ei, false);
        assertTrue(s.barForTest().pasteEnabled());
        type("Hello ");
        // tap the paste button (between ✨ and the 3 slots) via touch events
        View bar = s.barForTest(); input.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); input.layout(0, 0, 1440, input.getMeasuredHeight());
        float d = bar.getResources().getDisplayMetrics().density, px = 48 * d + 23 * d, py = bar.getHeight() / 2f;
        shot(input, 1440, "v3_01_ai_and_paste_buttons");
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, px, py, 0));
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, px, py, 0));
        ShadowLooper.idleMainLooper();
        assertEquals("Hello unique pastedword here", text());
        assertEquals(0, s.userForTest().count("pastedword")); // pasted text isn't learned
        assertTrue(s.barForTest().words().size() >= 3);      // suggestions still there
        // long press shows a preview, release doesn't paste
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, px, py, 0));
        ShadowLooper.idleMainLooper(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("unique pastedword here", s.barForTest().pastePreviewShown());
        shot(input, 1440, "v3_03_paste_long_press_preview");
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 700, android.view.MotionEvent.ACTION_UP, px, py, 0));
        assertEquals("Hello unique pastedword here", text());
        // a suggestion slot still taps normally
        float sx = 140 * d + (1440 - 140 * d) / 6f;
        String w0 = s.barForTest().words().get(0);
        type(" ");
        String first = s.barForTest().words().get(0);
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, sx, py, 0));
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, sx, py, 0));
        ShadowLooper.idleMainLooper();
        assertTrue(text(), text().endsWith(first + " "));
    }

    // ---------- v3: emoji panel ----------
    void tap(View v, float x, float y) {
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x, y, 0));
        v.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 40, android.view.MotionEvent.ACTION_UP, x, y, 0)); ShadowLooper.idleMainLooper();
    }
    void layoutInput(int w) { input.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); input.layout(0, 0, w, input.getMeasuredHeight()); }
    @Test public void emojiPanel() throws Exception {
        layoutInput(1440); int kbH = s.keyboardForTest().getHeight();
        View bar = s.barForTest(); float d = bar.getResources().getDisplayMetrics().density, py = bar.getHeight() / 2f;
        shot(input, 1440, "v3_04_strip_ai_paste_emoji");
        type("Nice ");
        tap(bar, 48 * d + 46 * d + 23 * d, py); // 😊 button
        EmojiPanel ep = s.emojiForTest(); layoutInput(1440);
        assertEquals(View.VISIBLE, ep.getVisibility()); assertEquals(View.GONE, s.keyboardForTest().getVisibility());
        assertEquals("panel matches keyboard height", kbH, ep.getHeight());
        for (String c : EmojiPanel.CATS) if (!c.equals("recent")) assertTrue(c, EmojiPanel.count(c) > 50);
        System.out.println("[emoji] counts: smileys " + EmojiPanel.count("smileys") + ", people " + EmojiPanel.count("people") + ", flags " + EmojiPanel.count("flags"));
        assertEquals(1, ep.category()); // smileys (no recents yet)
        float cell = 1440f / Math.max(6, (int) (1440 / (46 * d)));
        tap(ep, cell / 2, 40 * d + cell / 2); // first emoji 😀
        assertEquals("Nice 😀", text());
        ep.setCategory(2); // people: 👋 has skin tones
        String wave = ep.items().get(0); assertEquals("👋", wave); assertEquals(5, EmojiPanel.tonesFor(wave).length);
        ep.longPressForTest(0); assertEquals(6, ep.toneRowForTest().length); layoutInput(1440);
        shot(input, 1440, "v3_06_emoji_skin_tones");
        float tx = ep.getWidth() > 0 ? 0 : 0;
        ep.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, cell * 3.2f, 40 * d, 0));
        String pick = ep.toneRowForTest()[Math.max(0, Math.min(5, (int) ((cell * 3.2f - 4 * d) / (cell * 1.05f))))];
        ep.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_UP, cell * 3.2f, 40 * d, 0)); ShadowLooper.idleMainLooper();
        assertTrue(text(), text().startsWith("Nice 😀") && text().length() > "Nice 😀".length());
        // ⌫ removes the whole emoji (incl. skin tone modifier)
        int before = text().length(); tap(ep, 1440 - 40 * d, ep.getHeight() - 23 * d);
        assertEquals("Nice 😀", text());
        et.setText("🇺🇸👍🏽"); et.setSelection(et.length()); tap(ep, 1440 - 40 * d, ep.getHeight() - 23 * d); assertEquals("🇺🇸", text());
        // recents persisted locally, shown first
        assertTrue(ep.recents().contains("😀"));
        ep.setCategory(0); layoutInput(1440); shot(input, 1440, "v3_07_emoji_recents");
        ep.setCategory(1); layoutInput(1440); shot(input, 1440, "v3_05_emoji_panel");
        EmojiPanel ep2 = new EmojiPanel(s); assertEquals(0, ep2.category()); assertEquals("😀", ep2.recents().get(ep2.recents().size() - 1));
        // emoji not learned
        assertEquals(0, s.userForTest().count("😀"));
        // ABC returns to letters
        tap(ep, 40 * d, ep.getHeight() - 23 * d);
        assertEquals(View.GONE, ep.getVisibility()); assertEquals(View.VISIBLE, s.keyboardForTest().getVisibility());
        // nav bar insets still respected with the panel open
        tap(bar, 48 * d + 46 * d + 23 * d, py);
        input.dispatchApplyWindowInsets(navInsets(0, 0, 192, 0)); layoutInput(1440);
        assertEquals(192, input.getPaddingBottom()); assertTrue(ep.getBottom() <= input.getHeight() - 192);
        shot(input, 1440, "v3_08_emoji_with_nav_bar");
    }
    /** End-to-end with the real model through JNI (host build of the same native code). */
    @Test public void realModelThroughJni() throws Exception {
        String model = System.getProperty("aik.model");
        org.junit.Assume.assumeTrue(model != null);
        Context c = RuntimeEnvironment.getApplication();
        File dst = new File(c.getFilesDir(), "model.gguf"); java.nio.file.Files.deleteIfExists(dst.toPath());
        java.nio.file.Files.createSymbolicLink(dst.toPath(), new File(model).toPath());
        AiEngine ai = AiEngine.get(c);
        for (int i = 0; i < 200 && ai.state() == AiEngine.State.LOADING; i++) Thread.sleep(50);
        System.out.println("model file exists: " + dst.exists() + " " + dst);
        AiEngine.resetInstanceForTests(); ai = AiEngine.get(c); ai.ensureLoaded();
        for (int i = 0; i < 600 && ai.state() == AiEngine.State.LOADING; i++) Thread.sleep(50);
        assertEquals(ai.error(), AiEngine.State.READY, ai.state());
        s.onStartInputView(s.ei, false);
        type("Can you send me the ");
        for (int i = 0; i < 100; i++) { Thread.sleep(30); ShadowLooper.idleMainLooper(); }
        System.out.println("JNI next-word strip: " + s.barForTest().words() + " (" + ai.lastPredictMs + " ms)");
        shot(input, 1440, "12_live_model_next_word");
        et.setText("me and him goes to the store yesterday"); s.onAiAction(0);
        for (int i = 0; i < 400 && !s.previewTextForTest().contains("."); i++) { Thread.sleep(30); ShadowLooper.idleMainLooper(); }
        System.out.println("JNI fix grammar: " + s.previewTextForTest());
        shot(input, 1440, "13_live_model_fix_grammar");
        s.acceptForTest(); System.out.println("after Replace: " + text());
        assertNotEquals("me and him goes to the store yesterday", text());
    }

    // ---------- v5: swipe typing + scrollable strip ----------
    float[][] swipePath(String w) {
        KeyboardView kv = s.keyboardForTest(); java.util.Map<Character, float[]> c = kv.letterCentres();
        java.util.List<float[]> pts = new java.util.ArrayList<>(); char last = 0;
        for (char ch : w.toCharArray()) { if (ch == last) continue; last = ch; pts.add(c.get(ch)); }
        java.util.List<Float> xs = new java.util.ArrayList<>(), ys = new java.util.ArrayList<>(); java.util.Random r = new java.util.Random(w.hashCode());
        for (int i = 1; i < pts.size(); i++) { float[] a = pts.get(i - 1), b = pts.get(i); for (int k = 0; k < 12; k++) { float t = k / 12f; xs.add(a[0] + t * (b[0] - a[0]) + (float) r.nextGaussian() * 6); ys.add(a[1] + t * (b[1] - a[1]) + (float) r.nextGaussian() * 6); } }
        float[] e = pts.get(pts.size() - 1); xs.add(e[0]); ys.add(e[1]);
        float[] x = new float[xs.size()], y = new float[ys.size()]; for (int i = 0; i < x.length; i++) { x[i] = xs.get(i); y[i] = ys.get(i); }
        return new float[][]{x, y};
    }
    void swipe(String w) { layoutInput(1440); float[][] p = swipePath(w); assertTrue(s.keyboardForTest().swipeForTest(p[0], p[1], true)); ShadowLooper.idleMainLooper(); }
    @Test public void v5SwipeTypesWordsWithAutoSpaceAndAlternatives() {
        swipe("hello"); assertEquals("Hello ", text());
        swipe("how"); swipe("are"); swipe("you"); assertEquals("Hello how are you ", text());
        assertEquals("you", s.barForTest().words().get(0));
        assertTrue(s.barForTest().words().size() > 3);
        assertTrue("decode ms " + s.swipeDecodeMsForTest(), s.swipeDecodeMsForTest() < 50);
        // backspace right after a swipe removes the whole word
        s.onKey(KeyboardLayout.DELETE); ShadowLooper.idleMainLooper(); assertEquals("Hello how are ", text());
        // the next backspace is a normal one
        s.onKey(KeyboardLayout.DELETE); assertEquals("Hello how are", text());
        // tapped punctuation after a swipe swaps the auto space
        type(" "); swipe("you"); type("?"); assertEquals("Hello how are you? ", text());
    }
    @Test public void v5TapsStillWorkAndTapAfterSwipeStartsNewWord() {
        // a short drag within one key is still a tap
        layoutInput(1440); KeyboardView kv = s.keyboardForTest(); float[] h = kv.letterCentres().get('h');
        assertFalse(kv.swipeForTest(new float[]{h[0], h[0] + 8, h[0] + 12}, new float[]{h[1], h[1] + 4, h[1] + 5}, true));
        type("i"); type(" "); assertEquals("Hi ", text());
        swipe("there"); type("x"); assertEquals("Hi there x", text());
    }
    @Test public void v5PickingAlternativeReplacesSwipedWordAndLearns() {
        swipe("good"); java.util.List<String> alts = new java.util.ArrayList<>(s.barForTest().words());
        assertTrue(alts.size() >= 2);
        String alt = alts.get(1); s.onSuggestion(1, alt); assertEquals(alt + " ", text());
        assertTrue(s.userForTest().count(alt) >= 1);
        swipe("morning"); assertTrue(s.userForTest().count("morning") >= 1);
    }
    @Test public void v5SwipeOffSetting() {
        Settings.prefs(s).edit().putBoolean(Settings.SWIPE, false).commit(); s.onStartInputView(s.ei, false); layoutInput(1440);
        float[][] p = swipePath("hello"); assertFalse(s.keyboardForTest().swipeForTest(p[0], p[1], true));
        Settings.prefs(s).edit().putBoolean(Settings.SWIPE, true).commit();
    }
    @Test public void v5ScrollableStripAndScreenshots() throws Exception {
        layoutInput(1440);
        swipe("thanks"); swipe("for");
        float[][] p = swipePath("everything"); s.keyboardForTest().swipeForTest(p[0], p[1], false); // finger still down: trail visible
        shot(input, 1440, "v5_swipe_trail");
        s.keyboardForTest().swipeForTest(new float[]{p[0][p[0].length - 1]}, new float[]{p[1][p[1].length - 1]}, true);
        ShadowLooper.idleMainLooper();
        type("\b"); type("gre"); layoutInput(1440);
        CandidateBar bar = s.barForTest(); assertTrue("words " + bar.words(), bar.words().size() > 3); assertTrue(bar.maxScrollForTest() > 0);
        // drag the strip left; tapping afterwards picks the slot under the finger at the scrolled offset
        float d = bar.getResources().getDisplayMetrics().density, y = bar.getHeight() / 2f, x0 = 1400;
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, x0, y, 0));
        for (int i = 1; i <= 10; i++) bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, i * 16, android.view.MotionEvent.ACTION_MOVE, x0 - i * 60, y, 0));
        bar.dispatchTouchEvent(android.view.MotionEvent.obtain(0, 400, android.view.MotionEvent.ACTION_UP, x0 - 600, y, 0));
        ShadowLooper.idleMainLooper(); bar.computeScroll();
        assertTrue("scrolled " + bar.scrollPosForTest(), bar.scrollPosForTest() > 100);
        bar.scrollToForTest(bar.maxScrollForTest() * 0.5f);
        shot(input, 1440, "v5_scroll_bar");
        String before = text();
        // AI chips still render in place of the strip
        s.onAiButton(); assertTrue(bar.chipsShown()); shot(input, 1440, "v5_ai_chips_still_ok"); s.onAiButton();
        assertEquals(before, text());
    }
    @Test public void v5SwipeAboveNavBarScreenshot() throws Exception { int b = 48 * 4; insetShot(1440, navInsets(0, 0, b, 0), 0, 0, b, "v5_navbar_still_ok"); }

    // ---------- v6: current word in the bar, typed word kept, unlearn, live swipe preview ----------
    @Test public void v6SwipedWordIsCurrentAndHighlighted() {
        swipe("good"); CandidateBar bar = s.barForTest(); java.util.List<String> w = new java.util.ArrayList<>(bar.words());
        assertEquals("Good", w.get(0)); assertEquals(0, bar.currentIndex());
        assertEquals(new java.util.HashSet<>(w).size(), w.size()); // no duplicates
        // pick an alternative: it becomes the current word, the original stays in the list
        String alt = w.get(1); s.onSuggestion(1, alt); assertEquals(alt + " ", text());
        java.util.List<String> w2 = bar.words(); assertEquals(alt, w2.get(0)); assertEquals(0, bar.currentIndex()); assertTrue(w2.contains("Good"));
        assertEquals(new java.util.HashSet<>(w2).size(), w2.size());
        // original swipe learning undone
        assertEquals(0, s.userForTest().count("good"), 0.01); assertTrue(s.userForTest().count(alt) >= 1);
        // pick the original back: swap again
        s.onSuggestion(w2.indexOf("Good"), "Good"); assertEquals("Good ", text()); assertEquals(0, s.userForTest().count(alt), 0.01);
        // tap the current word: keeps text, confirms, strip moves on to predictions
        s.onSuggestion(0, "Good"); assertEquals("Good ", text()); assertEquals(-1, bar.currentIndex()); assertTrue(s.userForTest().count("good") >= 1);
    }
    @Test public void v6BackspaceAfterSwipeUnlearns() {
        swipe("morning"); assertTrue(s.userForTest().count("morning") >= 1);
        s.onKey(KeyboardLayout.DELETE); ShadowLooper.idleMainLooper(); assertEquals("", text());
        assertEquals(0, s.userForTest().count("morning"), 0.01);
    }
    @Test public void v6TypedWordAlwaysFirstInQuotes() {
        layoutInput(1440); type("the"); CandidateBar bar = s.barForTest();
        assertTrue(bar.words().get(0).equalsIgnoreCase("the")); assertEquals(0, bar.typedIndexForTest());
        s.onSuggestion(0, bar.words().get(0)); assertTrue(text().equalsIgnoreCase("the "));
        type("xqzv"); assertEquals("xqzv", bar.words().get(0)); assertEquals(0, bar.typedIndexForTest());
    }
    @Test public void v6LiveSwipePreview() throws Exception {
        layoutInput(1440); swipe("see"); swipe("you");
        float[][] p = swipePath("tomorrow"); int half = p[0].length * 3 / 4;
        s.keyboardForTest().swipeForTest(java.util.Arrays.copyOf(p[0], half), java.util.Arrays.copyOf(p[1], half), false);
        s.awaitLiveForTest(); ShadowLooper.idleMainLooper();
        assertTrue("live updates " + s.liveUpdates, s.liveUpdates > 0);
        System.out.println("v6 live mid-swipe bar: " + s.barForTest().words() + " live decode ms " + s.lastLiveDecodeMs);
        assertEquals(0, s.barForTest().currentIndex()); assertTrue(s.lastLiveDecodeMs < 50);
        shot(input, 1440, "v6_live_swipe");
        // finish the same gesture: final decode commits
        s.keyboardForTest().swipeForTest(p[0], p[1], true); ShadowLooper.idleMainLooper();
        assertTrue(text(), text().endsWith(" "));
        shot(input, 1440, "v6_current_word");
    }

    // ---------------- v8.2: action key (Search/Go/Send/Done/Next/Previous) ----------------
    java.util.List<String> acts = new java.util.ArrayList<>();
    void field(int inputType, int imeOptions) {
        et.setInputType(inputType); et.setImeOptions(imeOptions); et.setText(""); acts.clear();
        s.ei = new EditorInfo(); InputConnection real = et.onCreateInputConnection(s.ei);
        s.ei.inputType = inputType; s.ei.imeOptions = imeOptions;
        s.ic = new android.view.inputmethod.InputConnectionWrapper(real, true) {
            @Override public boolean performEditorAction(int a) { acts.add("action:" + a); return true; }
            @Override public boolean sendKeyEvent(android.view.KeyEvent k) { if (k.getAction() == android.view.KeyEvent.ACTION_UP) acts.add("key:" + k.getKeyCode()); return true; }
        };
        s.onStartInputView(s.ei, false);
    }
    void enter() { s.onKey(KeyboardLayout.ENTER); ShadowLooper.idleMainLooper(); }
    static final int TXT = InputType.TYPE_CLASS_TEXT, ML = InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    @Test public void v82EveryActionTypeEmptyAndMidWord() {
        int[] ids = {EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS};
        for (int multi : new int[]{0, ML}) for (int id : ids) {
            field(TXT | multi, id); enter();
            assertEquals("empty " + id, "", text()); assertEquals(java.util.List.of("action:" + id), acts);
            field(TXT | multi, id); type("dont"); enter();   // mid-word: literal word, no autocorrect, no newline
            assertEquals("dont", text()); assertEquals(java.util.List.of("action:" + id), acts);
        }
    }
    @Test public void v82GoogleSearchBoxNeverInsertsPrediction() {
        field(TXT | ML | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, EditorInfo.IME_ACTION_SEARCH);
        type("i dont"); enter(); assertEquals("I dont", text()); assertEquals(java.util.List.of("action:3"), acts);
        field(TXT | ML, EditorInfo.IME_ACTION_SEARCH); type("weather "); acts.clear(); enter();
        assertEquals("weather ", text()); assertEquals(java.util.List.of("action:3"), acts);
    }
    @Test public void v82SwipedWordThenSearch() {
        field(TXT | ML, EditorInfo.IME_ACTION_SEARCH); swipe("hello"); acts.clear(); enter();
        assertEquals("Hello ", text()); assertEquals(java.util.List.of("action:3"), acts);
    }
    @Test public void v82NewlineAndEnterKeyFallbacks() {
        field(TXT | ML, EditorInfo.IME_ACTION_UNSPECIFIED); type("hi"); enter(); assertEquals("hi\n", text()); assertTrue(acts.isEmpty());
        field(TXT | ML, EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION); type("hi"); enter(); assertEquals("hi\n", text()); assertTrue(acts.isEmpty());
        field(TXT, EditorInfo.IME_ACTION_UNSPECIFIED); type("hi"); enter(); assertEquals("hi", text()); assertEquals(java.util.List.of("key:66"), acts);
        field(TXT, EditorInfo.IME_ACTION_NONE); enter(); assertEquals(java.util.List.of("key:66"), acts);
    }
    @Test public void v82CustomActionIdAndLabels() {
        field(TXT, EditorInfo.IME_ACTION_DONE); s.ei.actionId = 42; s.ei.actionLabel = "Log in"; s.onStartInputView(s.ei, false);
        enter(); assertEquals(java.util.List.of("action:42"), acts);
        assertEquals("🔍", AIKeyboardService.enterAction(ei(TXT | ML, EditorInfo.IME_ACTION_SEARCH)).label);
        assertEquals("⏎", AIKeyboardService.enterAction(ei(TXT | ML, EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_ENTER_ACTION)).label);
    }
    static EditorInfo ei(int t, int o) { EditorInfo e = new EditorInfo(); e.inputType = t; e.imeOptions = o; return e; }
}
