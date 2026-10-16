package com.jason.aikeyboard.ai;

/** JNI entry points into libaikvoice.so (whisper.cpp speech-to-text, fully on-device). */
public final class WhisperBridge {
    private static boolean libLoaded; private static String libError = "";
    public static synchronized boolean loadLibrary() {
        if (libLoaded) return true;
        try { System.loadLibrary("aikvoice"); libLoaded = true; libError = ""; } catch (Throwable t) { libLoaded = false; libError = t.getClass().getSimpleName() + ": " + t.getMessage(); }
        return libLoaded;
    }
    public static synchronized String libError() { return libError; }
    /** length = bytes of the model (entry length inside the APK); <= 0 = to end of file. */
    public static native boolean nativeLoad(String path, long offset, long length, int threads);
    public static native void nativeUnload();
    public static native void nativeAbort();
    public static native String nativeError();
    public static native String nativeSystemInfo();
    /** pcm: 16 kHz mono floats. fast = greedy + clip-sized encoder window (partials); otherwise beam search (final). */
    public static native String nativeTranscribe(float[] pcm, int n, String prompt, boolean fast);
}
