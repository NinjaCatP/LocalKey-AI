package com.jason.aikeyboard.core;

/**
 * v7: energy voice-activity detector on 16 kHz PCM frames (20 ms). Adaptive noise floor; "speech" = frame energy well above
 * the floor. Auto-stop after {@code silenceStopMs} of silence once speech started, or {@code noSpeechMs} with no speech at all.
 */
public final class Vad {
    public int silenceStopMs = 1600, noSpeechMs = 7000, maxMs = 120_000;
    /** v8: input sample rate (the mic may run at 48 kHz) and thresholds lowered for quiet Samsung VOICE_RECOGNITION input */
    public int rate = 16000; public double absDb = -58, overFloorDb = 7; private double peakDb = -120;
    public double peakDb() { return peakDb; }
    private double floorDb = -60, levelDb = -90; private boolean speech, inSpeech; private int ms, silentMs, speechMs;
    public void reset() { floorDb = -60; levelDb = -90; speech = inSpeech = false; ms = silentMs = speechMs = 0; peakDb = -120; }
    /** Feed one frame; returns true if this frame is speech. */
    public boolean feed(short[] pcm, int n) {
        double e = 0; for (int i = 0; i < n; i++) { double v = pcm[i] / 32768.0; e += v * v; }
        double db = 10 * Math.log10(e / Math.max(1, n) + 1e-10);
        int fm = n * 1000 / rate; ms += fm; peakDb = Math.max(peakDb, db);
        levelDb = db;
        // floor follows quiet frames quickly, loud frames very slowly
        floorDb = db < floorDb ? floorDb + (db - floorDb) * 0.3 : floorDb + (db - floorDb) * 0.005;
        boolean s = db > absDb && db > floorDb + overFloorDb;
        if (s) { speechMs += fm; silentMs = 0; if (speechMs >= 100) { speech = true; inSpeech = true; } }
        else { silentMs += fm; if (silentMs > 300) { inSpeech = false; speechMs = 0; } }
        return s;
    }
    public boolean heardSpeech() { return speech; }
    /** true while the user is talking (hysteresis: 300 ms of silence ends a run). */
    public boolean inSpeech() { return inSpeech; }
    public int silentMs() { return silentMs; }
    public int elapsedMs() { return ms; }
    public boolean shouldStop() { return speech ? silentMs >= silenceStopMs || ms >= maxMs : ms >= noSpeechMs; }
    /** 0..1 meter level for the UI (-60 dBFS .. -10 dBFS). */
    public float level() { return (float) Math.max(0, Math.min(1, (levelDb + 60) / 50)); }
}
