package com.zhuanz.flymemotion.core;

/** An opaque fallback is retired only after a real scene and its final alpha transaction. */
public final class BackdropBlend {
    public enum Phase { COVERED, FADING, EXPOSED }
    private static final long DURATION_MS = 180;
    private long generation, started;
    private Phase phase = Phase.COVERED;
    public synchronized long reveal(long now, boolean realSceneReady) {
        if (!realSceneReady || phase != Phase.COVERED) return -1;
        started = now; phase = Phase.FADING; return ++generation;
    }
    public synchronized void protect() { ++generation; phase = Phase.COVERED; }
    public synchronized boolean current(long token) { return phase == Phase.FADING && generation == token; }
    public synchronized float alpha(long token, long now) {
        if (!current(token)) return phase == Phase.EXPOSED ? 0 : 1;
        float p = Math.max(0, Math.min(1, (now - started) / (float) DURATION_MS));
        return 1 - p * p * (3 - 2 * p);
    }
    public synchronized boolean complete(long token) {
        if (!current(token)) return false;
        phase = Phase.EXPOSED; return true;
    }
    public synchronized boolean exposed() { return phase == Phase.EXPOSED; }
    /** The current phase, for diagnostics: a fade that never completes reads as FADING forever. */
    public synchronized Phase phase() { return phase; }
}
