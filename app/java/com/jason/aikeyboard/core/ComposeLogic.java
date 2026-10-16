package com.jason.aikeyboard.core;

/** Small text rules shared by the service and tests. */
public final class ComposeLogic {
    private ComposeLogic() {}
    /** When typing punctuation right after an auto-inserted space, the space moves after the punctuation. */
    public static boolean swapsSpace(int code) { return code == '.' || code == ',' || code == '!' || code == '?' || code == ':' || code == ';' || code == ')'; }
    /** Characters that end a word (trigger autocorrect + learning). */
    public static boolean endsWord(int code) { return code == ' ' || code == '\n' || (!Character.isLetterOrDigit(code) && code != '\'' && code != '-' && code != '_' && code != '@' && code != '#' && code != '&'); }
    /** Double space -> ". " */
    public static boolean doubleSpacePeriod(CharSequence before) {
        int n = before.length(); if (n < 2) return false;
        return before.charAt(n - 1) == ' ' && Character.isLetterOrDigit(before.charAt(n - 2));
    }
}
