package com.jason.aikeyboard.ai;

/** JNI entry points into libaikeyboard.so (llama.cpp, fully on-device). */
public final class LlamaBridge {
    private static boolean libLoaded;
    public static synchronized boolean loadLibrary() {
        if (libLoaded) return true;
        try { System.loadLibrary("aikeyboard"); libLoaded = true; } catch (Throwable t) { libLoaded = false; }
        return libLoaded;
    }
    public static native boolean nativeLoad(String path, long offset, int threads, int nCtx);
    public static native void nativeCancel();
    public static native void nativeUnload();
    public static native String[] nativePredict(String context, String prefix, int k);
    public static native String nativeGenerate(String system, String user, int maxTokens);
}
