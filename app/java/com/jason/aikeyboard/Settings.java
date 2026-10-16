package com.jason.aikeyboard;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings.Secure;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.jason.aikeyboard.ai.AiEngine;

/** Setup instructions, toggles and a test box. */
public final class Settings extends Activity {
    public static final String HAPTICS = "haptics", AUTOCORRECT = "autocorrect", AI_PREDICT = "ai_predict", AI_ACTIONS = "ai_actions",
        LEARN = "learn", PERSONALIZE_AI = "personalize_ai", AUTO_CAP = "auto_cap", DOUBLE_SPACE = "double_space",
        SWIPE = "swipe_typing", SWIPE_TRAIL = "swipe_trail",
        VOICE_ENGINE = "voice_engine", VOICE_AUTOSTOP = "voice_autostop", VOICE_LEARN = "voice_learn";
    public static final String ENGINE_WHISPER = "whisper", ENGINE_ANDROID = "android";
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings", MODE_PRIVATE); }

    private TextView status, stats; private Button micBtn;
    private static final int REQ_EXPORT = 31, REQ_IMPORT = 32;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(0xFF121316);
        ScrollView sv = new ScrollView(this); sv.setFitsSystemWindows(true);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); int p = dp(20); l.setPadding(p, p, p, p);
        sv.addView(l);
        TextView title = text("LocalKey AI", 26); title.setTypeface(Typeface.DEFAULT_BOLD); l.addView(title);
        l.addView(text("Smart suggestions, autocorrect and grammar fixes from an AI model that runs entirely on this phone. The app has no internet permission, so nothing you type can leave the device.", 14));
        status = text("", 14); status.setTextColor(0xFF9BB3FF); l.addView(status);
        l.addView(header("Set up"));
        l.addView(text("1. Turn on \"LocalKey AI\" in the list (Android will warn that keyboards can see what you type; that's the standard notice).", 14));
        Button en = btn("Open keyboard settings"); en.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))); l.addView(en);
        l.addView(text("2. Switch to it.", 14));
        Button sw = btn("Choose keyboard"); sw.setOnClickListener(v -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker()); l.addView(sw);
        l.addView(text("Tip: on Samsung you can also switch keyboards with the small keyboard icon at the bottom-right of the navigation bar while typing.", 13));
        l.addView(header("Options"));
        SharedPreferences sp = prefs(this);
        l.addView(toggle(sp, AI_PREDICT, "AI next-word suggestions", true));
        l.addView(toggle(sp, AI_ACTIONS, "AI button (Fix grammar, Polite, Shorter, Casual)", true));
        l.addView(toggle(sp, SWIPE, "Swipe typing (glide across letters; backspace right after deletes the whole word)", true));
        l.addView(toggle(sp, SWIPE_TRAIL, "Show swipe trail", true));
        l.addView(toggle(sp, AUTOCORRECT, "Autocorrect (backspace right after undoes it)", true));
        l.addView(toggle(sp, AUTO_CAP, "Auto-capitalize sentences", true));
        l.addView(toggle(sp, DOUBLE_SPACE, "Double-space types a period", true));
        l.addView(toggle(sp, HAPTICS, "Vibrate on keypress", true));
        l.addView(header("Voice typing"));
        l.addView(text("Tap 🎤 on the suggestion bar and talk. Speech is turned into text on this phone; no audio or text is sent anywhere.", 13));
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        android.widget.RadioButton rw = new android.widget.RadioButton(this), ra = new android.widget.RadioButton(this);
        rw.setText("Voice engine: Local Whisper (built in, recommended)"); ra.setText("Voice engine: Android on-device recognizer" + (com.jason.aikeyboard.ai.VoiceEngine.androidAvailable(this) ? "" : " (not available on this phone)"));
        for (android.widget.RadioButton x : new android.widget.RadioButton[]{rw, ra}) { x.setTextColor(0xFFECEDEF); x.setTextSize(15); x.setId(View.generateViewId()); rg.addView(x); }
        (ENGINE_ANDROID.equals(sp.getString(VOICE_ENGINE, ENGINE_WHISPER)) ? ra : rw).setChecked(true);
        rg.setOnCheckedChangeListener((g, id) -> sp.edit().putString(VOICE_ENGINE, id == ra.getId() ? ENGINE_ANDROID : ENGINE_WHISPER).apply());
        l.addView(rg);
        l.addView(toggle(sp, VOICE_AUTOSTOP, "Stop automatically when I stop talking", true));
        l.addView(toggle(sp, VOICE_LEARN, "Learn from voice-typed text", true));
        micBtn = btn(""); micBtn.setOnClickListener(v -> startActivity(new Intent(this, MicPermissionActivity.class))); l.addView(micBtn);
        Button diag = btn("Voice diagnostics (test mic + speech engine, save a log)"); diag.setOnClickListener(v -> startActivity(new Intent(this, VoiceDiagActivity.class))); l.addView(diag);
        l.addView(header("Personal learning"));
        l.addView(text("Learns from what you type so suggestions and autocorrect get more accurate with use: your word pairs and phrases, autocorrects you undo, suggestions you tap or skip, and which keys you tend to mix up. Stored only in this app's private storage (recent phrases are encrypted with an Android Keystore key). Never learns in password fields or apps that ask keyboards not to learn (incognito).", 13));
        l.addView(toggle(sp, LEARN, "Learn from my typing", true));
        l.addView(toggle(sp, PERSONALIZE_AI, "Personalize AI (use my recent phrases and habits to guide AI predictions)", true));
        stats = text("", 14); stats.setTextColor(0xFF9BB3FF); l.addView(stats);
        Button exp = btn("Export learned data to a file"); exp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, "aikeyboard-learned.txt");
            try { startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { toast("No file picker available"); } }); l.addView(exp);
        Button imp = btn("Import learned data from a file"); imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");
            try { startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { toast("No file picker available"); } }); l.addView(imp);
        Button clr = btn("Clear all learned data"); clr.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
            .setTitle("Clear all learned data?").setMessage("This deletes every word, phrase, undo rule and typing habit the keyboard has learned on this phone. It can't be undone (unless you exported a copy).")
            .setPositiveButton("Clear", (d, w) -> { Learned.clearAll(this); refreshStats(); toast("All learned data cleared"); })
            .setNegativeButton("Cancel", null).show()); l.addView(clr);
        l.addView(header("Try it"));
        EditText et = new EditText(this); et.setHint("Type here to test"); et.setTextColor(0xFFECEDEF); et.setHintTextColor(0xFF8E929B); et.setMinLines(3); l.addView(et);
        l.addView(header("Tips"));
        l.addView(text("• Tap ✨ on the suggestion bar for AI actions. They work on selected text, or on the whole field if nothing is selected, and show a preview before replacing anything.\n• Long-press a letter for accents, the period key for punctuation, and the comma key to open these settings.\n• Tap 🎤 for voice typing; it stops by itself when you pause, or tap Stop. Punctuation and capitals are added automatically.\n• Hold backspace to delete repeatedly. Double-tap shift for caps lock.", 13));
        l.addView(header("About"));
        l.addView(text("Model: Qwen2.5-0.5B-Instruct (Q4_K_M), Apache-2.0, by the Qwen team / Alibaba Cloud. Runs with llama.cpp (MIT). Voice: OpenAI Whisper base.en (MIT), quantized q5_1, run with whisper.cpp (MIT). Word list: FrequencyWords by Hermit Dave (CC BY-SA 4.0, from OpenSubtitles).", 12));
        setContentView(sv);
        AiEngine.get(this).ensureLoaded();
    }
    @Override protected void onResume() { super.onResume(); refresh(); refreshStats();
        if (micBtn != null) { boolean g = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; micBtn.setText(g ? "Microphone allowed ✓" : "Allow microphone for voice typing"); micBtn.setEnabled(!g); } }
    void refreshStats() {
        com.jason.aikeyboard.core.UserModel u = Learned.get(this);
        stats.setText("Learned so far: " + u.size() + " words you use" + (AIKeyboardService.sharedDict != null ? " (" + u.newWords(AIKeyboardService.sharedDict) + " not in the dictionary)" : "") + " · "
            + u.pairCount() + " word sequences · " + u.phraseCount() + " recent phrases · " + u.noCorrectCount() + " words no longer autocorrected · "
            + u.slipPatterns() + " key-slip patterns");
    }
    private void toast(String m) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show(); }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (req == REQ_EXPORT) {
                try (java.io.OutputStream o = getContentResolver().openOutputStream(data.getData(), "wt");
                     java.io.Writer w = new java.io.OutputStreamWriter(o, java.nio.charset.StandardCharsets.UTF_8)) { Learned.get(this).exportTo(w); }
                toast("Exported. The file contains words and phrases you typed, so keep it private.");
            } else if (req == REQ_IMPORT) {
                int n;
                try (java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                     java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) { n = Learned.get(this).importFrom(r); }
                Learned.save(this); refreshStats(); toast("Imported " + n + " entries");
            }
        } catch (Exception e) { toast("Failed: " + e.getMessage()); }
    }
    private void refresh() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        boolean enabled = false;
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) if (i.getPackageName().equals(getPackageName())) enabled = true;
        String cur = Secure.getString(getContentResolver(), Secure.DEFAULT_INPUT_METHOD);
        boolean active = cur != null && cur.startsWith(getPackageName() + "/");
        AiEngine ai = AiEngine.get(this);
        String m = ai.state() == AiEngine.State.READY ? "AI model ready (loaded in " + ai.loadMs + " ms)" : ai.state() == AiEngine.State.FAILED ? "AI model failed: " + ai.error() : "AI model loading…";
        status.setText((active ? "✓ LocalKey AI is your current keyboard" : enabled ? "Enabled. Now tap \"Choose keyboard\"." : "Not enabled yet. Follow step 1.") + "\n" + m);
        if (ai.state() == AiEngine.State.LOADING || ai.state() == AiEngine.State.IDLE) status.postDelayed(this::refresh, 1000);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) refresh(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private TextView text(String s, int sp) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(0xFFC9CCD3); t.setPadding(0, dp(6), 0, dp(6)); return t; }
    private TextView header(String s) { TextView t = text(s, 18); t.setTextColor(0xFFECEDEF); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(0, dp(18), 0, dp(4)); return t; }
    private Button btn(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private Switch toggle(SharedPreferences sp, String key, String label, boolean def) {
        Switch s = new Switch(this); s.setText(label); s.setTextColor(0xFFECEDEF); s.setTextSize(15); s.setPadding(0, dp(10), 0, dp(10));
        s.setChecked(sp.getBoolean(key, def)); s.setOnCheckedChangeListener((v, c) -> sp.edit().putBoolean(key, c).apply());
        s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }
}
