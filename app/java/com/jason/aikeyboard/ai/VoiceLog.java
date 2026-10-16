package com.jason.aikeyboard.ai;

import android.content.Context;

/** v8: small rolling in-memory + file log of voice sessions (files/voice-log.txt), for the diagnostics screen. */
public final class VoiceLog {
    private final java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>(); private final java.io.File file;
    VoiceLog(Context c) { file = new java.io.File(c.getFilesDir(), "voice-log.txt"); }
    public synchronized void add(String s) {
        String l = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(new java.util.Date()) + " " + s;
        lines.add(l); while (lines.size() > 300) lines.poll();
        android.util.Log.i("AIKeyboard", "voice: " + s);
        try (java.io.FileWriter w = new java.io.FileWriter(file, true)) { w.write(l + "\n"); } catch (Throwable ignored) {}
        if (file.length() > 256 * 1024) file.delete();
    }
    public synchronized String text() { StringBuilder b = new StringBuilder(); for (String l : lines) b.append(l).append('\n'); return b.toString(); }
}
