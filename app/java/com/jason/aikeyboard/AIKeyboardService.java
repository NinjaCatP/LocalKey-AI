package com.jason.aikeyboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jason.aikeyboard.ai.AiEngine;
import com.jason.aikeyboard.core.ComposeLogic;
import com.jason.aikeyboard.core.Dictionary;
import com.jason.aikeyboard.core.KeyboardLayout;
import com.jason.aikeyboard.core.SuggestionEngine;
import com.jason.aikeyboard.core.UserModel;

import java.io.File;
import java.util.List;

public class AIKeyboardService extends InputMethodService implements KeyboardView.Listener, CandidateBar.Listener, EmojiPanel.Listener, VoicePanel.Listener, com.jason.aikeyboard.ai.VoiceEngine.Listener {
    static Dictionary sharedDict; // loaded once per process
    private UserModel user; private SuggestionEngine engine;
    private AiEngine ai() { return AiEngine.get(this); }
    private KeyboardView keyboard; private CandidateBar bar; private LinearLayout preview; private TextView previewText, previewTitle;
    private final StringBuilder composing = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean noLearn, passwordField, noSuggest, personalAi; private int shiftLock;
    private java.util.List<String> nextStripShown; // v3: last next-word strip, for accept/ignore feedback
    private String lastAutoCorrectFrom, lastAutoCorrectTo; // for undo with backspace
    private boolean justAutoSpaced; private long lastShiftTap;
    private SuggestionEngine.Result lastResult;
    private String aiOriginal; private boolean aiOnSelection; private String aiResult;
    private static final String[] AI_CHIPS = {"Fix grammar", "Polite", "Shorter", "Casual"};

    @Override public void onCreate() {
        super.onCreate();
        prefs = Settings.prefs(this);
        synchronized (AIKeyboardService.class) {
            if (sharedDict == null) { sharedDict = new Dictionary(); try { sharedDict.load(getAssets().open("words.txt")); } catch (Exception ignored) {} }
        }
        user = Learned.get(this); // v3: shared personal model (n-grams, reverts, feedback, key slips, phrases)
        engine = new SuggestionEngine(sharedDict, user);
        engine.maxWords = 15; // v5: the strip scrolls sideways
        swipe = new com.jason.aikeyboard.core.SwipeDecoder(sharedDict, user);
    }
    // ---------------- v5: swipe typing ----------------
    private com.jason.aikeyboard.core.SwipeDecoder swipe;
    private String lastSwipeCommit; private java.util.List<String> swipeAlts; // text committed by the last swipe (word + space)
    private boolean swipeOn() { return prefs.getBoolean(Settings.SWIPE, true) && !passwordField; }
    @Override public void onSwipe(float[] xs, float[] ys, int n) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        float[] pitch = keyboard.letterPitch();
        swipe.setKeys(keyboard.letterCentres(), pitch[0], pitch[1]);
        liveGen.incrementAndGet(); // drop any in-flight live preview
        commitWord(ic, true);
        CharSequence bcs = ic.getTextBeforeCursor(600, 0); String before = bcs == null ? "" : bcs.toString();
        java.util.List<com.jason.aikeyboard.core.SwipeDecoder.Candidate> c = swipe.decode(xs, ys, n, SuggestionEngine.previousWord2(before), SuggestionEngine.previousWord(before), 15);
        lastSwipeDecodeMs = swipe.lastDecodeMicros / 1000.0;
        if (c.isEmpty()) return;
        KeyboardView.Shift sh = keyboard.shift(); boolean cap = sh == KeyboardView.Shift.ON || SuggestionEngine.sentenceStart(before) && prefs.getBoolean(Settings.AUTO_CAP, true);
        java.util.List<String> alts = new java.util.ArrayList<>();
        for (com.jason.aikeyboard.core.SwipeDecoder.Candidate x : c) {
            String d = user.display(x.word); if (d == null || d.equals(d.toLowerCase(java.util.Locale.ROOT)) && !d.contains("'")) d = Dictionary.display(x.word);
            if (sh == KeyboardView.Shift.CAPS) d = d.toUpperCase(java.util.Locale.ROOT);
            else if (cap && !d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
            if (!alts.contains(d)) alts.add(d);
        }
        if (bar != null && bar.chipsShown()) bar.showChips(null);
        commitSwipeWord(ic, before, alts.get(0));
        swipeAlts = alts; ai().cancelPredictions();
        if (sh == KeyboardView.Shift.ON) keyboard.setShift(KeyboardView.Shift.OFF);
        if (!noSuggest) { lastResult = null; nextStripShown = null; bar.setSuggestions(alts, 0, -1, 0); }
    }
    // v6: live preview while the finger is still moving. Decoded off the UI thread; stale results dropped.
    private final java.util.concurrent.ExecutorService liveExec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "aik-swipe-live"); t.setDaemon(true); return t; });
    private final java.util.concurrent.atomic.AtomicInteger liveGen = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean liveBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    double lastLiveDecodeMs; int liveUpdates;
    @Override public void onSwipeProgress(float[] xs, float[] ys, int n) {
        if (noSuggest || bar == null || !swipeOn()) return;
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        if (!liveBusy.compareAndSet(false, true)) return; // a decode is still running: skip this frame (no queue build-up)
        float[] pitch = keyboard.letterPitch(); swipe.setKeys(keyboard.letterCentres(), pitch[0], pitch[1]);
        CharSequence bcs = ic.getTextBeforeCursor(600, 0); String before = bcs == null ? "" : bcs.toString();
        if (composing.length() > 0 && before.endsWith(composing.toString())) before = before.substring(0, before.length() - composing.length());
        final String b = before; final boolean cap = keyboard.shift() != KeyboardView.Shift.OFF || SuggestionEngine.sentenceStart(b) && prefs.getBoolean(Settings.AUTO_CAP, true);
        final boolean caps = keyboard.shift() == KeyboardView.Shift.CAPS; final int gen = liveGen.incrementAndGet();
        liveExec.execute(() -> {
            try {
                long t0 = System.nanoTime();
                java.util.List<com.jason.aikeyboard.core.SwipeDecoder.Candidate> c = swipe.decode(xs, ys, n, SuggestionEngine.previousWord2(b), SuggestionEngine.previousWord(b), 8);
                lastLiveDecodeMs = (System.nanoTime() - t0) / 1e6;
                java.util.List<String> w = new java.util.ArrayList<>();
                for (com.jason.aikeyboard.core.SwipeDecoder.Candidate x : c) { String d = Dictionary.display(x.word); if (d == null) d = x.word;
                    if (caps) d = d.toUpperCase(java.util.Locale.ROOT); else if (cap && !d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
                    if (!w.contains(d)) w.add(d); }
                // prefix matches: finger is still moving, so also offer completions of the leading readings
                for (int i = 0; i < Math.min(2, c.size()); i++) { String pre = c.get(i).word; if (pre.length() < 2) continue;
                    int added = 0; for (String x : engine.suggest(b, pre, null).words) { String k = Dictionary.norm(x);
                        if (k.length() > pre.length() && k.startsWith(pre) && added < 3) { String d = caps ? x.toUpperCase(java.util.Locale.ROOT) : cap ? Character.toUpperCase(x.charAt(0)) + x.substring(1) : x;
                            if (!w.contains(d)) { w.add(Math.min(w.size(), 1 + i * 2 + added), d); added++; } } } }
                lastLiveDecodeMs = (System.nanoTime() - t0) / 1e6;
                main.post(() -> { if (gen == liveGen.get() && keyboard.isSwipingForLive() && !w.isEmpty()) { bar.setSuggestions(w, 0, -1, 0); liveUpdates++; } });
            } finally { liveBusy.set(false); }
        });
    }
    void awaitLiveForTest() { try { liveExec.submit(() -> {}).get(); } catch (Exception e) { throw new RuntimeException(e); } }
    double lastSwipeDecodeMs;
    private void commitSwipeWord(InputConnection ic, String before, String word) {
        String pre = before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1)) || before.endsWith("(") || before.endsWith("\"") ? "" : " ";
        ic.commitText(pre + word, 1);
        learn(ic, word); swipeLearned = noLearn ? null : lastLearned;
        ic.commitText(" ", 1); justAutoSpaced = true; lastAutoCorrectFrom = null;
        lastSwipeCommit = pre + word + " ";
    }
    /** Backspace right after a swipe deletes the whole swiped word (and its spaces). */
    private String[] lastLearned, swipeLearned; // v6: {prev2, prev, word} of the last learn(), and of the current swiped word
    private void unlearnSwipe() { String[] l = swipeLearned; swipeLearned = null; if (l != null && !noLearn) user.unlearn(l[0], l[1], l[2]); }
    private boolean deleteSwipedWord(InputConnection ic, String sw) {
        CharSequence b = ic.getTextBeforeCursor(sw.length(), 0);
        if (b == null || !b.toString().equals(sw)) return false;
        ic.deleteSurroundingText(sw.length(), 0); unlearnSwipe(); justAutoSpaced = false; updateShiftAuto(); updateSuggestions(); return true;
    }
    private boolean swapSwipeAlt(InputConnection ic, String word) {
        String sw = lastSwipeCommit; lastSwipeCommit = null;
        if (sw == null) return false;
        CharSequence b = ic.getTextBeforeCursor(sw.length(), 0);
        if (b == null || !b.toString().equals(sw)) return false;
        ic.deleteSurroundingText(sw.length(), 0); unlearnSwipe();
        CharSequence b2 = ic.getTextBeforeCursor(600, 0);
        commitSwipeWord(ic, b2 == null ? "" : b2.toString(), word); // v6: stays the "current" word (⌫ still removes it whole)
        return true;
    }

    @Override public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(KeyboardView.BG);
        FrameLayout top = new FrameLayout(this);
        bar = new CandidateBar(this); bar.setListener(this);
        top.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        preview = buildPreview(); preview.setVisibility(View.GONE);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        keyboard = new KeyboardView(this); keyboard.setListener(this);
        root.addView(keyboard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emoji = new EmojiPanel(this); emoji.setListener(this); emoji.setVisibility(View.GONE); // v3: replaces the key area
        root.addView(emoji, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        voice = new VoicePanel(this); voice.setListener(this); voice.setVisibility(View.GONE); // v7: replaces the key area while dictating
        root.addView(voice, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // v4: robust nav-bar handling. Bottom padding = max(root insets, decor insets, android navigation_bar_height
        // fallback when insets report 0). Applied to root, so keys AND emoji panel sit above the nav bar; LinearLayout
        // measurement includes the padding.
        inputRoot = root;
        root.setOnApplyWindowInsetsListener((v, wi) -> { lastRootInsets = wi; updateNavPadding(); return wi; });
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { hookDecor(); v.requestApplyInsets(); updateNavPadding(); }
            @Override public void onViewDetachedFromWindow(View v) {}
        });
        updateNavPadding();
        root.requestApplyInsets();
        refreshPaste();
        return root;
    }
    private View inputRoot; private android.view.WindowInsets lastRootInsets, lastDecorInsets; private boolean decorHooked;
    private void hookDecor() {
        try {
            android.view.Window w = getWindow().getWindow();
            if (w == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false); // we pad ourselves
            if (decorHooked) return; decorHooked = true;
            View d = w.getDecorView();
            d.setOnApplyWindowInsetsListener((v, wi) -> { lastDecorInsets = wi; WindowInsetsHelper.post(v, this::updateNavPadding); return v.onApplyWindowInsets(wi); });
            d.requestApplyInsets();
        } catch (Throwable ignored) {}
    }
    static final class WindowInsetsHelper { static void post(View v, Runnable r) { v.post(r); } }
    static int[] insetsOf(android.view.WindowInsets wi) {
        if (wi == null) return new int[]{0, 0, 0};
        android.graphics.Insets nb = wi.getInsets(android.view.WindowInsets.Type.navigationBars() | android.view.WindowInsets.Type.displayCutout());
        android.graphics.Insets hidden = wi.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars());
        android.graphics.Insets g = wi.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures());
        int bottom = Math.max(nb.bottom, hidden.bottom);
        if (bottom == 0) bottom = Math.min(g.bottom, 200); // gesture-only devices: keep space bar out of the swipe zone
        return new int[]{nb.left, nb.right, bottom};
    }
    int navBarResourceHeight() {
        try {
            android.content.res.Resources r = getResources();
            boolean land = r.getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int id = r.getIdentifier(land ? "navigation_bar_height_landscape" : "navigation_bar_height", "dimen", "android");
            if (id == 0) id = r.getIdentifier("navigation_bar_height", "dimen", "android");
            return id != 0 ? r.getDimensionPixelSize(id) : dp(48);
        } catch (Throwable t) { return dp(48); }
    }
    void updateNavPadding() {
        View v = inputRoot; if (v == null) return;
        int[] a = insetsOf(lastRootInsets), b = insetsOf(lastDecorInsets), c = insetsOf(v.getRootWindowInsets());
        int l = Math.max(a[0], Math.max(b[0], c[0])), r = Math.max(a[1], Math.max(b[1], c[1])), bot = Math.max(a[2], Math.max(b[2], c[2]));
        if (bot == 0 && l == 0 && r == 0 && !isFullscreenMode()) bot = navBarResourceHeight(); // fallback: insets report 0 (seen on device in v2/v3)
        applyNavPadding(v, l, r, bot);
    }
    static void applyNavPadding(View v, int l, int r, int b) {
        if (v.getPaddingLeft() != l || v.getPaddingRight() != r || v.getPaddingBottom() != b) { v.setPadding(l, 0, r, b); v.requestLayout(); }
    }
    @Override public void onWindowShown() {
        super.onWindowShown();
        try {
            android.view.Window w = getWindow().getWindow();
            if (w != null) {
                w.setNavigationBarColor(KeyboardView.BG); // ignored on 35+ edge-to-edge, harmless elsewhere
                if (w.getInsetsController() != null) w.getInsetsController().setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } catch (Throwable ignored) {}
        hookDecor(); updateNavPadding();
    }
    @Override public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        // content/visible top = top of our keyboard (nav padding included in the panel), so apps resize above it
        View d = getWindow().getWindow() != null ? getWindow().getWindow().getDecorView() : null;
        if (d != null && keyboard != null && keyboard.getRootView() == d && !isFullscreenMode()) {
            View root = (View) bar.getParent().getParent();
            int[] loc = new int[2]; root.getLocationInWindow(loc);
            if (root.getHeight() > 0) { outInsets.contentTopInsets = loc[1]; outInsets.visibleTopInsets = loc[1]; }
        }
    }
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
    private LinearLayout buildPreview() {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setBackgroundColor(0xFF1B1D24); p.setPadding(dp(10), dp(6), dp(10), dp(6));
        previewTitle = new TextView(this); previewTitle.setTextColor(0xFF9BB3FF); previewTitle.setTextSize(12);
        p.addView(previewTitle);
        ScrollView sv = new ScrollView(this);
        previewText = new TextView(this); previewText.setTextColor(0xFFECEDEF); previewText.setTextSize(15); previewText.setTextIsSelectable(false);
        sv.addView(previewText);
        p.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)));
        LinearLayout btns = new LinearLayout(this); btns.setGravity(Gravity.END);
        Button cancel = button("Cancel", 0xFF2B2D33, 0xFFECEDEF), retry = button("Undo", 0xFF2B2D33, 0xFFECEDEF), ok = button("Replace", 0xFF7C9CFF, 0xFF0B0D12);
        retry.setVisibility(View.GONE);
        cancel.setOnClickListener(v -> closePreview(true));
        ok.setOnClickListener(v -> acceptAi());
        btns.addView(cancel); btns.addView(ok);
        p.addView(btns);
        return p;
    }
    private Button button(String t, int bgc, int fg) {
        Button b = new Button(this); b.setText(t); b.setAllCaps(false); b.setTextColor(fg); b.setBackgroundColor(bgc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)); lp.leftMargin = dp(8); b.setLayoutParams(lp); b.setMinWidth(dp(88));
        return b;
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        composing.setLength(0); lastAutoCorrectFrom = null; justAutoSpaced = false;
        int cls = info.inputType & InputType.TYPE_MASK_CLASS, var = info.inputType & InputType.TYPE_MASK_VARIATION;
        passwordField = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            || cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        noSuggest = passwordField || (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 || var == InputType.TYPE_TEXT_VARIATION_URI || var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        noLearn = passwordField || (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 || !prefs.getBoolean(Settings.LEARN, true);
        personalAi = !noLearn && prefs.getBoolean(Settings.PERSONALIZE_AI, true);
        engine.personalRerank = personalAi; nextStripShown = null;
        engine.autocorrect = prefs.getBoolean(Settings.AUTOCORRECT, true) && !noSuggest && var != InputType.TYPE_TEXT_VARIATION_PERSON_NAME;
        keyboard.setHaptics(prefs.getBoolean(Settings.HAPTICS, true));
        keyboard.setSwipe(swipeOn(), prefs.getBoolean(Settings.SWIPE_TRAIL, true)); lastSwipeCommit = null; swipeAlts = null;
        keyboard.setLayer(cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME ? KeyboardLayout.Layer.SYMBOLS : KeyboardLayout.Layer.LETTERS);
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean multiLine = (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 || (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 && action == EditorInfo.IME_ACTION_UNSPECIFIED;
        keyboard.setEnterLabel(multiLine ? "⏎" : action == EditorInfo.IME_ACTION_SEARCH ? "🔍" : action == EditorInfo.IME_ACTION_SEND ? "➤" : action == EditorInfo.IME_ACTION_GO ? "Go" : action == EditorInfo.IME_ACTION_NEXT ? "Next" : action == EditorInfo.IME_ACTION_DONE ? "Done" : "⏎");
        closePreview(false); if (emoji != null && emoji.getVisibility() == View.VISIBLE) showEmoji(false);
        if (voice != null && voice.getVisibility() == View.VISIBLE) { voiceEngine().cancel(); showVoice(false); }
        if (prefs.getBoolean(Settings.AI_PREDICT, true) || prefs.getBoolean(Settings.AI_ACTIONS, true)) ai().ensureLoaded();
        refreshPaste();
        updateShiftAuto(); updateSuggestions();
    }
    // ---------------- v3: paste ----------------
    private android.content.ClipboardManager clip() { return (android.content.ClipboardManager) getApplicationContext().getSystemService(CLIPBOARD_SERVICE); }
    private final android.content.ClipboardManager.OnPrimaryClipChangedListener clipListener = this::refreshPaste;
    private boolean clipListening, afterPaste;
    private EmojiPanel emoji;
    // ---------------- v3: emoji ----------------
    @Override public void onEmojiButton() { if (voiceShown()) onVoiceCancel(); showEmoji(emoji.getVisibility() != View.VISIBLE); }
    void showEmoji(boolean on) {
        if (emoji == null) return;
        if (on) { if (keyboard.getHeight() > 0) emoji.setForcedHeight(keyboard.getHeight()); if (preview != null && preview.getVisibility() == View.VISIBLE) closePreview(true); if (bar.chipsShown()) bar.showChips(null);
            InputConnection ic = getCurrentInputConnection(); if (ic != null) commitWord(ic, false); }
        keyboard.setVisibility(on ? View.GONE : View.VISIBLE); emoji.setVisibility(on ? View.VISIBLE : View.GONE); bar.setEmojiActive(on);
        if (!on) updateShiftAuto();
        updateSuggestions();
    }
    @Override public void onEmoji(String e) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        lastSwipeCommit = null; swipeAlts = null;
        commitWord(ic, false); ic.commitText(e, 1); // emoji aren't learned by the personal model
        lastAutoCorrectFrom = null; justAutoSpaced = false; nextStripShown = null; afterPaste = true; updateSuggestions();
    }
    @Override public void onEmojiDelete() { InputConnection ic = getCurrentInputConnection(); if (ic != null) handleDelete(ic); }
    @Override public void onEmojiAbc() { showEmoji(false); }
    EmojiPanel emojiForTest() { return emoji; }
    // ---------------- v7: voice typing ----------------
    private VoicePanel voice; private boolean voiceDone;
    com.jason.aikeyboard.ai.VoiceEngine voiceEngine() { return com.jason.aikeyboard.ai.VoiceEngine.get(this); }
    VoicePanel voiceForTest() { return voice; }
    boolean voiceShown() { return voice != null && voice.getVisibility() == View.VISIBLE; }
    static volatile Boolean micGrantedOverride; // tests
    boolean micGranted() { Boolean o = micGrantedOverride; return o != null ? o : checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; }
    @Override public void onMicButton() {
        if (voiceShown()) { onVoiceStop(); return; }
        if (passwordField) { bar.setStatus("Voice typing is off in password fields"); return; }
        if (!micGranted()) {
            android.content.Intent pi = new android.content.Intent(this, MicPermissionActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); lastPermIntent = pi;
            try { startActivity(pi); } catch (Throwable ignored) {}
            lastVoiceError = "mic permission requested"; return;
        }
        boolean android_ = Settings.ENGINE_ANDROID.equals(prefs.getString(Settings.VOICE_ENGINE, Settings.ENGINE_WHISPER));
        voiceEngine().vad.silenceStopMs = prefs.getBoolean(Settings.VOICE_AUTOSTOP, true) ? 1600 : Integer.MAX_VALUE / 2;
        voiceEngine().vad.noSpeechMs = prefs.getBoolean(Settings.VOICE_AUTOSTOP, true) ? 7000 : Integer.MAX_VALUE / 2;
        showVoice(true); voice.setEngineLabel(android_ ? "Android on-device" : "Local Whisper"); voiceDone = false;
        voiceEngine().start(android_ ? com.jason.aikeyboard.ai.VoiceEngine.Engine.ANDROID : com.jason.aikeyboard.ai.VoiceEngine.Engine.WHISPER, this);
    }
    void showVoice(boolean on) {
        if (voice == null) return;
        if (on) { if (emoji.getVisibility() == View.VISIBLE) showEmoji(false); int kh = keyboard.getHeight(); if (kh > 0) voice.setForcedHeight(kh);
            if (preview != null && preview.getVisibility() == View.VISIBLE) closePreview(true); if (bar.chipsShown()) bar.showChips(null);
            InputConnection ic = getCurrentInputConnection(); if (ic != null) commitWord(ic, false); lastSwipeCommit = null; swipeAlts = null; voice.clear(); }
        keyboard.setVisibility(on ? View.GONE : View.VISIBLE); voice.setVisibility(on ? View.VISIBLE : View.GONE);
        bar.setMicActive(on); bar.setVoiceText(on ? "" : null);
        if (!on) { updateShiftAuto(); updateSuggestions(); }
    }
    @Override public void onVoiceStop() { if (voiceEngine().isRunning()) { voiceEngine().stop(); voice.setState("transcribing"); } else showVoice(false); }
    @Override public void onVoiceCancel() { voiceEngine().cancel(); showVoice(false); }
    String lastVoiceError; String lastVoiceInserted; android.content.Intent lastPermIntent;
    @Override public void onVoiceState(String st) {
        if (voice != null) voice.setState(st);
        if ("idle".equals(st) && voiceShown() && (voiceDone || !voiceEngine().isRunning()) && voice.error() == null) showVoice(false); // errors stay visible until Close
    }
    @Override public void onVoiceEngineChanged(String label) { if (voice != null) voice.setEngineLabel(label); }
    @Override public void onVoiceLevel(float l) { if (voice != null) voice.setLevel(l); if (bar != null) bar.setMicLevel(l); }
    @Override public void onVoicePartial(String t) { if (voice != null) voice.setPartial(t); if (bar != null && voiceShown()) bar.setVoiceText(t); }
    @Override public void onVoiceError(String m) { lastVoiceError = m; if (voice != null) voice.setError(m.startsWith("Voice error") || m.startsWith("Didn") || m.startsWith("No speech") || m.contains("Using Android") || m.startsWith("Microphone") ? m : "Voice error: " + m); android.util.Log.w("AIKeyboard", "voice: " + m); }
    @Override public void onVoiceFinal(String text, boolean done) {
        voiceDone = done;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && text != null && !text.isEmpty()) insertVoiceText(ic, text);
        if (voice != null) voice.setPartial("");
        if (bar != null && voiceShown()) bar.setVoiceText("");
        if (done && (voice == null || voice.error() == null || (text != null && !text.isEmpty()))) showVoice(false);
    }
    void insertVoiceText(InputConnection ic, String text) {
        if (composing.length() > 0) commitWord(ic, false);
        CharSequence bcs = ic.getTextBeforeCursor(600, 0); String before = bcs == null ? "" : bcs.toString();
        String ins = com.jason.aikeyboard.core.VoiceText.format(before, text, prefs.getBoolean(Settings.AUTO_CAP, true),
            w -> { String d = Dictionary.display(w); return sharedDict != null && sharedDict.contains(w) && d != null && !d.isEmpty() && Character.isLowerCase(d.charAt(0)); });
        if (ins.isEmpty()) return;
        ic.beginBatchEdit(); ic.commitText(ins, 1); ic.endBatchEdit();
        lastVoiceInserted = ins; lastAutoCorrectFrom = null; justAutoSpaced = false; nextStripShown = null;
        if (!noLearn && prefs.getBoolean(Settings.VOICE_LEARN, true)) {
            String p2 = SuggestionEngine.previousWord2(before), p1 = SuggestionEngine.previousWord(before);
            for (String w : com.jason.aikeyboard.core.VoiceText.words(text)) {
                if (w == null) { user.endPhrase(); p2 = null; p1 = null; continue; }
                user.learn(p2, p1, w); p2 = p1; p1 = w;
            }
            Learned.save(this);
        }
    }

    String clipboardText() {
        try {
            android.content.ClipboardManager cm = clip(); if (cm == null || !cm.hasPrimaryClip()) return null;
            android.content.ClipData cd = cm.getPrimaryClip(); if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence t = cd.getItemAt(0).coerceToText(this); return t == null || t.length() == 0 ? null : t.toString();
        } catch (Throwable t) { return null; } // Android may deny clipboard reads when we're not the focused IME
    }
    void refreshPaste() {
        if (!clipListening) { try { clip().addPrimaryClipChangedListener(clipListener); clipListening = true; } catch (Throwable ignored) {} }
        if (bar != null) bar.setPasteEnabled(clipboardText() != null);
    }
    @Override public void onDestroy() { try { if (clipListening) clip().removePrimaryClipChangedListener(clipListener); } catch (Throwable ignored) {} super.onDestroy(); }
    @Override public String clipboardPreview() { String t = clipboardText(); return t == null ? null : t.length() > 200 ? t.substring(0, 200) + "…" : t; }
    @Override public void onPaste() {
        InputConnection ic = getCurrentInputConnection(); String t = clipboardText();
        if (ic == null || t == null) { refreshPaste(); return; }
        lastSwipeCommit = null; swipeAlts = null;
        commitWord(ic, false);
        ic.commitText(t, 1); // pasted text is never learned by the personal model
        lastAutoCorrectFrom = null; justAutoSpaced = false; nextStripShown = null; afterPaste = true;
        updateShiftAuto(); updateSuggestions();
    }
    @Override public void onFinishInputView(boolean finishing) {
        super.onFinishInputView(finishing);
        if (voiceShown()) { voiceEngine().cancel(); showVoice(false); }
        ai().cancelPredictions();
        if (!noLearn) user.endPhrase(); else user.discardPhrase();
        Learned.save(this);
    }
    @Override public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        // cursor moved away from the composing region (tap elsewhere): drop composing state
        if (composing.length() > 0 && (cs == -1 || ns != ne || ns != ce)) {
            InputConnection ic = getCurrentInputConnection(); if (ic != null) ic.finishComposingText();
            composing.setLength(0); updateSuggestions();
        } else if (composing.length() == 0 && ns == ne && Math.abs(ns - oe) > 1) { justAutoSpaced = false; lastAutoCorrectFrom = null; updateShiftAuto(); updateSuggestions(); }
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; } // never take over the screen in landscape

    // ---------------- keys ----------------
    @Override public void onKey(int code) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        String sw = lastSwipeCommit;
        if (code != KeyboardLayout.SHIFT) { lastSwipeCommit = null; swipeAlts = null; }
        if (code == KeyboardLayout.DELETE && sw != null && deleteSwipedWord(ic, sw)) return;
        switch (code) {
            case KeyboardLayout.SHIFT: {
                long now = System.currentTimeMillis();
                KeyboardView.Shift s = keyboard.shift();
                if (s == KeyboardView.Shift.OFF) keyboard.setShift(KeyboardView.Shift.ON);
                else if (s == KeyboardView.Shift.ON && now - lastShiftTap < 400) keyboard.setShift(KeyboardView.Shift.CAPS);
                else keyboard.setShift(KeyboardView.Shift.OFF);
                lastShiftTap = now; return;
            }
            case KeyboardLayout.SYMBOLS: keyboard.setLayer(KeyboardLayout.Layer.SYMBOLS); return;
            case KeyboardLayout.MORE_SYMBOLS: keyboard.setLayer(KeyboardLayout.Layer.MORE); return;
            case KeyboardLayout.ALPHA: keyboard.setLayer(KeyboardLayout.Layer.LETTERS); updateShiftAuto(); return;
            case KeyboardLayout.DELETE: handleDelete(ic); return;
            case KeyboardLayout.ENTER: {
                commitWord(ic, false);
                if (!noLearn) user.endPhrase();
                EditorInfo ei = getCurrentInputEditorInfo(); int action = ei.imeOptions & EditorInfo.IME_MASK_ACTION;
                boolean multi = (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
                if (!multi && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) ic.performEditorAction(action);
                else if (!multi && action == EditorInfo.IME_ACTION_UNSPECIFIED) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
                else ic.commitText("\n", 1);
                justAutoSpaced = false; updateShiftAuto(); updateSuggestions(); return;
            }
            default: handleChar(ic, code);
        }
    }
    @Override public void onText(String text) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        lastSwipeCommit = null; swipeAlts = null;
        if (text.length() == 1) { handleChar(ic, text.charAt(0)); return; }
        commitWord(ic, false); ic.commitText(text, 1); updateSuggestions();
    }
    @Override public void onSettings() {
        Intent i = new Intent(this, Settings.class); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
    }

    private void handleChar(InputConnection ic, int code) {
        KeyboardView.Shift shift = keyboard.shift();
        if (keyboard.layer() == KeyboardLayout.Layer.LETTERS && shift != KeyboardView.Shift.OFF && Character.isLetter(code)) code = Character.toUpperCase(code);
        boolean wordChar = Character.isLetterOrDigit(code) || (code == '\'' && composing.length() > 0);
        if (wordChar && !noSuggest) {
            composing.appendCodePoint(code); ic.setComposingText(composing, 1);
        } else if (wordChar) {
            ic.commitText(String.valueOf((char) code), 1);
        } else {
            boolean autocorrect = code == ' ' || ComposeLogic.endsWord(code);
            if (code == ' ' && composing.length() == 0 && justAutoSpaced && prefs.getBoolean(Settings.DOUBLE_SPACE, true)) {
                CharSequence b = ic.getTextBeforeCursor(2, 0);
                if (b != null && ComposeLogic.doubleSpacePeriod(b)) { ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); justAutoSpaced = false; updateShiftAuto(); updateSuggestions(); return; }
            }
            commitWord(ic, autocorrect);
            if (!noLearn && (code == '.' || code == '!' || code == '?')) user.endPhrase();
            if (ComposeLogic.swapsSpace(code) && justAutoSpaced) {
                CharSequence b = ic.getTextBeforeCursor(1, 0);
                if (b != null && b.length() == 1 && b.charAt(0) == ' ') ic.deleteSurroundingText(1, 0);
                ic.commitText(String.valueOf((char) code) + " ", 1); justAutoSpaced = true;
            } else { ic.commitText(String.valueOf((char) code), 1); justAutoSpaced = code == ' ' && justAutoSpaced; }
        }
        if (shift == KeyboardView.Shift.ON && keyboard.layer() == KeyboardLayout.Layer.LETTERS) keyboard.setShift(KeyboardView.Shift.OFF);
        if (code == ' ' || code == '\n') updateShiftAuto();
        if (!wordChar && keyboard.layer() != KeyboardLayout.Layer.LETTERS && (code == ' ' || code == '\'')) { keyboard.setLayer(KeyboardLayout.Layer.LETTERS); updateShiftAuto(); }
        updateSuggestions();
    }

    /** Finish the composing word, applying autocorrect if requested. */
    private void commitWord(InputConnection ic, boolean autocorrect) {
        if (composing.length() == 0) return;
        String typed = composing.toString(), out = typed;
        if (autocorrect && lastResult != null && lastResult.typed.equals(typed) && lastResult.autoCorrectIndex >= 0 && engine.autocorrect)
            out = lastResult.words.get(lastResult.autoCorrectIndex);
        ic.commitText(out, 1);
        if (!out.equals(typed)) { lastAutoCorrectFrom = typed; lastAutoCorrectTo = out; if (!noLearn) user.correctionAccepted(typed, out); } else lastAutoCorrectFrom = null;
        if (!noLearn && nextStripShown != null) user.feedback(nextStripShown, out, false);
        nextStripShown = null;
        learn(ic, out);
        composing.setLength(0);
    }
    private void learn(InputConnection ic, String word) {
        if (noLearn) return;
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        String b = before == null ? "" : before.toString();
        if (b.endsWith(word)) b = b.substring(0, b.length() - word.length());
        if (afterPaste) { afterPaste = false; user.learn(null, null, word); lastLearned = new String[]{null, null, word}; return; } // don't learn sequences from pasted words
        String p2 = SuggestionEngine.previousWord2(b), p1 = SuggestionEngine.previousWord(b);
        user.learn(p2, p1, word); lastLearned = new String[]{p2, p1, word};
    }
    private void handleDelete(InputConnection ic) {
        justAutoSpaced = false;
        if (composing.length() > 0) {
            composing.setLength(composing.length() - Character.charCount(composing.codePointBefore(composing.length())));
            if (composing.length() == 0) ic.commitText("", 1); else ic.setComposingText(composing, 1);
            updateSuggestions(); return;
        }
        // backspace right after an autocorrect reverts it
        if (lastAutoCorrectFrom != null) {
            CharSequence b = ic.getTextBeforeCursor(lastAutoCorrectTo.length() + 1, 0);
            if (b != null && (b.toString().equals(lastAutoCorrectTo + " ") || b.toString().endsWith(lastAutoCorrectTo + " "))) {
                ic.deleteSurroundingText(lastAutoCorrectTo.length() + 1, 0); ic.commitText(lastAutoCorrectFrom, 1);
                if (!noLearn) user.autocorrectReverted(lastAutoCorrectFrom, lastAutoCorrectTo); // v3: learn "don't correct this"
                lastAutoCorrectFrom = null; updateSuggestions(); return;
            }
            lastAutoCorrectFrom = null;
        }
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else {
            CharSequence b = ic.getTextBeforeCursor(32, 0);
            int n = graphemeTail(b);
            if (b == null || b.length() == 0) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); else ic.deleteSurroundingText(n, 0);
        }
        updateShiftAuto(); updateSuggestions();
    }
    /** Length of the last user-perceived character (so ⌫ removes a whole emoji like 👍🏽 or 🇺🇸 at once). */
    static int graphemeTail(CharSequence b) {
        if (b == null || b.length() == 0) return 1;
        String s = b.toString();
        android.icu.text.BreakIterator it = android.icu.text.BreakIterator.getCharacterInstance(); it.setText(s);
        int end = s.length(), start = it.preceding(end);
        return start == android.icu.text.BreakIterator.DONE ? 1 : Math.max(1, end - start);
    }
    private void updateShiftAuto() {
        if (keyboard == null || keyboard.layer() != KeyboardLayout.Layer.LETTERS || keyboard.shift() == KeyboardView.Shift.CAPS) return;
        EditorInfo ei = getCurrentInputEditorInfo(); InputConnection ic = getCurrentInputConnection();
        if (ei == null || ic == null || !prefs.getBoolean(Settings.AUTO_CAP, true)) return;
        boolean cap = ic.getCursorCapsMode(ei.inputType) != 0;
        keyboard.setShift(cap ? KeyboardView.Shift.ON : KeyboardView.Shift.OFF);
    }

    // ---------------- suggestions ----------------
    private void updateSuggestions() {
        if (bar == null || bar.chipsShown()) return;
        InputConnection ic = getCurrentInputConnection();
        if (noSuggest || ic == null) { bar.setSuggestions(java.util.Collections.emptyList(), -1, -1); return; }
        CharSequence bcs = ic.getTextBeforeCursor(600, 0);
        String before = bcs == null ? "" : bcs.toString();
        String typed = composing.toString();
        // the editor's text before cursor includes the composing text; strip it
        if (!typed.isEmpty() && before.endsWith(typed)) before = before.substring(0, before.length() - typed.length());
        final String ctx = before, tp = typed;
        show(engine.suggest(ctx, tp, null));
        boolean useAi = prefs.getBoolean(Settings.AI_PREDICT, true) && ai().state() == AiEngine.State.READY && !ctx.trim().isEmpty();
        String aiCtx = useAi ? aiContext(ctx) : ctx;
        lastAiContext = aiCtx;
        if (useAi) ai().predict(aiCtx, tp, 3, words -> { if (composing.toString().equals(tp)) show(engine.suggest(ctx, tp, words)); });
        else ai().cancelPredictions();
    }
    String lastAiContext;
    /** v3: prepend a few relevant recent phrases of yours as style context for the LLM (short, for speed). */
    String aiContext(String ctx) {
        if (!personalAi) return ctx;
        String pc = user.promptContext(ctx, 2, 160);
        return pc.isEmpty() ? ctx : pc + (ctx.length() > 400 ? ctx.substring(ctx.length() - 400) : ctx);
    }
    private void show(SuggestionEngine.Result r) {
        lastResult = r;
        if (composing.length() == 0) nextStripShown = r.words;
        bar.setSuggestions(r.words, r.autoCorrectIndex >= 0 ? r.autoCorrectIndex : (composing.length() > 0 ? (r.typedValid ? r.typedIndex : Math.min(1, r.words.size() - 1)) : -1), r.typedIndex);
    }
    @Override public void onSuggestion(int index, String word) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        if (swipeAlts != null && lastSwipeCommit != null) { // v5: pick another reading of the last swipe
            java.util.List<String> alts = swipeAlts; swipeAlts = null;
            if (!noLearn) user.feedback(alts, word, true);
            if (index == bar.currentIndex() || !alts.isEmpty() && alts.get(0).equals(word)) { // v6: tapped the current word: keep it, confirm
                lastSwipeCommit = null; swipeLearned = null; updateShiftAuto(); updateSuggestions(); return;
            }
            if (swapSwipeAlt(ic, word)) {
                if (keyboard.shift() == KeyboardView.Shift.ON) keyboard.setShift(KeyboardView.Shift.OFF);
                java.util.List<String> na = new java.util.ArrayList<>(); na.add(word);
                for (String a : alts) if (!a.equals(word)) na.add(a); // the original swiped word stays in the list
                swipeAlts = na; lastResult = null; nextStripShown = null;
                if (!noSuggest) bar.setSuggestions(na, 0, -1, 0);
                return;
            }
        }
        lastSwipeCommit = null; swipeAlts = null;
        if (!noLearn) {
            SuggestionEngine.Result lr = lastResult;
            String typed = composing.toString();
            if (lr != null && composing.length() > 0 && lr.typed.equals(typed) && lr.autoCorrectIndex >= 0 && index == lr.typedIndex)
                user.autocorrectReverted(typed, lr.words.get(lr.autoCorrectIndex)); // picked "typed" over the pending autocorrect
            else if (composing.length() > 0 && !word.equalsIgnoreCase(typed)) user.correctionAccepted(typed, word);
            user.feedback(lr != null ? lr.words : null, word, true);
        }
        nextStripShown = null;
        if (composing.length() > 0) {
            ic.commitText(word, 1); learn(ic, word); composing.setLength(0);
        } else {
            CharSequence b = ic.getTextBeforeCursor(1, 0);
            if (b != null && b.length() == 1 && !Character.isWhitespace(b.charAt(0))) ic.commitText(" ", 1);
            ic.commitText(word, 1); learn(ic, word);
        }
        ic.commitText(" ", 1); justAutoSpaced = true; lastAutoCorrectFrom = null;
        if (keyboard.shift() == KeyboardView.Shift.ON) keyboard.setShift(KeyboardView.Shift.OFF);
        updateShiftAuto(); updateSuggestions();
    }

    // ---------------- AI actions ----------------
    @Override public void onAiButton() {
        if (bar.chipsShown()) { bar.showChips(null); updateSuggestions(); return; }
        if (!prefs.getBoolean(Settings.AI_ACTIONS, true)) { bar.setSuggestions(java.util.Collections.singletonList("AI actions are off in settings"), -1, -1); return; }
        bar.showChips(AI_CHIPS);
    }
    @Override public void onAiAction(int index) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        commitWord(ic, false);
        AiEngine.Action a = AiEngine.Action.values()[index];
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) { aiOriginal = sel.toString(); aiOnSelection = true; }
        else {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            String all = et != null && et.text != null ? et.text.toString() : null;
            if (all == null) { CharSequence b = ic.getTextBeforeCursor(4000, 0); CharSequence af = ic.getTextAfterCursor(4000, 0); all = (b == null ? "" : b) + "" + (af == null ? "" : af); }
            aiOriginal = all; aiOnSelection = false;
        }
        bar.showChips(null); updateSuggestions();
        if (aiOriginal.trim().isEmpty()) { showPreview(a.label, "Type something first, or select the text you want to change.", false); return; }
        if (aiOriginal.length() > 2400) { showPreview(a.label, "That's too long for the on-device model. Select a shorter part (under ~2,400 characters).", false); return; }
        switch (ai().state()) {
            case FAILED: showPreview(a.label, "The AI model couldn't load: " + ai().error(), false); return;
            case IDLE: ai().ensureLoaded(); // fall through
            default: break;
        }
        showPreview(a.label, ai().state() == AiEngine.State.READY ? "Thinking…" : "Loading the on-device model, then thinking…", false);
        waitAndRewrite(a, 0);
    }
    private void waitAndRewrite(AiEngine.Action a, int tries) {
        if (ai().state() == AiEngine.State.LOADING && tries < 120) { handler.postDelayed(() -> { if (preview.getVisibility() == View.VISIBLE) waitAndRewrite(a, tries + 1); }, 250); return; }
        if (ai().state() != AiEngine.State.READY) { showPreview(a.label, "The AI model isn't available: " + ai().error(), false); return; }
        ai().rewrite(a, aiOriginal, r -> {
            if (preview.getVisibility() != View.VISIBLE) return;
            if (r == null || r.isEmpty()) showPreview(a.label, "No result. Try again.", false);
            else showPreview(a.label + "  ·  " + (ai().lastRewriteMs / 100) / 10.0 + " s on device", r, true);
        });
    }
    private void showPreview(String title, String text, boolean result) {
        previewTitle.setText(title); previewText.setText(text); aiResult = result ? text : null; preview.setVisibility(View.VISIBLE);
    }
    private void closePreview(boolean cancel) {
        if (preview == null) return;
        if (cancel) ai().cancelRewrite();
        preview.setVisibility(View.GONE); aiResult = null;
    }
    private void acceptAi() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || aiResult == null) { closePreview(true); return; }
        ic.beginBatchEdit();
        if (!aiOnSelection) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null && et.text.toString().equals(aiOriginal)) { ic.setSelection(0, et.text.length()); ic.commitText(aiResult, 1); }
            else { ic.performContextMenuAction(android.R.id.selectAll); ic.commitText(aiResult, 1); }
        } else ic.commitText(aiResult, 1);
        ic.endBatchEdit();
        closePreview(false); updateSuggestions();
    }

    // test hooks
    CandidateBar barForTest() { return bar; }
    double swipeDecodeMsForTest() { return lastSwipeDecodeMs; }
    KeyboardView keyboardForTest() { return keyboard; }
    void showPreviewForTest(String title, String text) { showPreview(title, text, true); }
    String previewTextForTest() { return previewText.getText().toString(); }
    void acceptForTest() { acceptAi(); }
    String aiContextForTest() { return lastAiContext; }
    com.jason.aikeyboard.core.UserModel userForTest() { return user; }
}
