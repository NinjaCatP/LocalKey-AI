package com.jason.aikeyboard.core;

/** v8.2: what the Enter/action key does for an editor (pure logic, mirrors AOSP LatinIME).
 *  The editor's action (Search/Go/Send/Done/Next/Previous or a custom actionId) wins, even in
 *  multi-line fields (Google's search box is multi-line + actionSearch); only IME_FLAG_NO_ENTER_ACTION
 *  or "no action" turns it into a newline. */
public final class EnterAction {
    // android.view.inputmethod.EditorInfo / InputType constants (kept here so this stays JVM-testable)
    public static final int MASK_ACTION = 0xff, UNSPECIFIED = 0, NONE = 1, GO = 2, SEARCH = 3, SEND = 4, NEXT = 5, DONE = 6, PREVIOUS = 7;
    public static final int FLAG_NO_ENTER_ACTION = 0x40000000, TYPE_MULTI_LINE = 0x20000;
    public enum Kind { ACTION, NEWLINE, ENTER_KEY }
    public final Kind kind; public final int actionId; public final String label;
    private EnterAction(Kind k, int id, String l) { kind = k; actionId = id; label = l; }

    public static EnterAction of(int inputType, int imeOptions, int customActionId, CharSequence customLabel) {
        boolean multi = (inputType & TYPE_MULTI_LINE) != 0;
        int action = imeOptions & MASK_ACTION;
        if ((imeOptions & FLAG_NO_ENTER_ACTION) != 0) return new EnterAction(Kind.NEWLINE, 0, "⏎");
        if (customActionId != 0 && customLabel != null && customLabel.length() > 0)
            return new EnterAction(Kind.ACTION, customActionId, customLabel.toString());
        switch (action) {
            case SEARCH: return new EnterAction(Kind.ACTION, SEARCH, "🔍");
            case GO: return new EnterAction(Kind.ACTION, GO, "Go");
            case SEND: return new EnterAction(Kind.ACTION, SEND, "➤");
            case NEXT: return new EnterAction(Kind.ACTION, NEXT, "Next");
            case DONE: return new EnterAction(Kind.ACTION, DONE, "Done");
            case PREVIOUS: return new EnterAction(Kind.ACTION, PREVIOUS, "Prev");
            default: // UNSPECIFIED / NONE
                return multi ? new EnterAction(Kind.NEWLINE, 0, "⏎") : new EnterAction(Kind.ENTER_KEY, 0, "⏎");
        }
    }
}
