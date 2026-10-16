package com.zhuanz.flymemotion.core;

/** A proof belongs to one window/buffer binding, never to an historical draw state. */
public final class ScenePresentation {
    public enum Phase { PREPARING, REVEALABLE, HANDOFF, NATIVE_OWNED, ENDED }
    /** Establish the session before the native target creates/recreates its first Surface. */
    public synchronized void open(long id) {
        session=id;window=surface=0;++epoch;
        freshDraw=presented=nativeHidden=producerProof=false;drawSequence=-1;phase=Phase.PREPARING;
    }
    /** Control may be prepared under an opaque cover; this NEVER qualifies a return frame. */
    public synchronized boolean prepareNativeExit(long id) {
        if(id!=session || phase==Phase.ENDED)return false;
        if(!handoff()){phase=Phase.HANDOFF;nativeHidden=false;}
        return true;
    }
    public synchronized boolean cancelNativeExit(long id) {
        if(id!=session || phase!=Phase.HANDOFF)return false;
        nativeHidden=false;phase=presented?Phase.REVEALABLE:Phase.PREPARING;return true;
    }
    private long epoch, session = -1, window, surface, drawSequence = -1;
    private int expectedSequence;
    private boolean freshDraw, presented, nativeHidden, producerProof;
    private Phase phase = Phase.ENDED;
    public synchronized long bind(long id, long windowId, long layer, int expected) {
        boolean continuingExit = session == id && handoff();
        session = id; window = windowId; surface = layer; expectedSequence = expected;
        freshDraw = presented = nativeHidden = producerProof = false; drawSequence = -1;
        phase = continuingExit ? Phase.HANDOFF : Phase.PREPARING; return ++epoch;
    }
    public synchronized boolean matches(long id, long binding, long windowId, long layer) {
        return phase != Phase.ENDED && session == id && epoch == binding && window == windowId && surface == layer;
    }
    public synchronized void expect(long binding, int sequence) { if (binding == epoch) expectedSequence = sequence; }
    /** Which term of {@link #drawn} refused the last delivery, so the proof chain is inspectable. */
    private String drawnRefusal = "none";
    public synchronized String drawnRefusal() { return drawnRefusal; }
    public synchronized boolean drawn(long binding, int sequence, boolean realTransaction) {
        if (binding != epoch) { drawnRefusal = "epoch " + binding + "!=" + epoch; return false; }
        if (phase == Phase.ENDED) { drawnRefusal = "ended"; return false; }
        if (producerProof) { drawnRefusal = "producerProof"; return false; }
        if (!realTransaction) { drawnRefusal = "no-buffer-transaction"; return false; }
        if (sequence == Integer.MAX_VALUE) { drawnRefusal = "max-sequence"; return false; }
        if (sequence < expectedSequence) { drawnRefusal = "sequence " + sequence + "<" + expectedSequence; return false; }
        // Pin the first accepted draw until its fence returns. A later app frame must not
        // orphan that outstanding completion callback. Rebinding/invalidation starts afresh.
        if (freshDraw) { drawnRefusal = "pinned " + sequence + "!=" + drawSequence; return sequence == drawSequence; }
        freshDraw = true; drawSequence = sequence; drawnRefusal = "accepted " + sequence; return true;
    }
    public synchronized boolean presented(long binding, long sequence, boolean latched, boolean fenceSignalled) {
        if (binding != epoch || !freshDraw || sequence != drawSequence || !latched || !fenceSignalled || phase == Phase.ENDED) return false;
        presented = true; if (phase == Phase.PREPARING) phase = Phase.REVEALABLE; return true;
    }
    /** Producer frame numbers are independent of WMS resize/draw sequence numbers. */
    public synchronized boolean producerPresented(long binding, long frame, boolean latched, boolean fenceSignalled) {
        if(binding!=epoch || phase==Phase.ENDED || frame<=0 || !latched || !fenceSignalled)return false;
        if(presented)return true;
        producerProof=freshDraw=presented=true;drawSequence=frame;
        if(phase==Phase.PREPARING)phase=Phase.REVEALABLE;
        return true;
    }
    public synchronized String proofSource(){return presented ? (producerProof ? "SURFACE_BUFFER" : "CLIENT_TRANSACTION") : "NONE";}
    public synchronized void invalidate() {
        if(phase==Phase.ENDED)return;
        ++epoch; // A delayed producer/fence from before the loss cannot revive coverage.
        freshDraw = presented = producerProof = false; drawSequence = -1;
        if (!handoff()) phase = Phase.PREPARING;
    }
    public synchronized boolean beginHandoff(long binding) {
        if (binding != epoch || phase != Phase.REVEALABLE || !presented) return false;
        phase = Phase.HANDOFF; nativeHidden = false; return true;
    }
    public synchronized void foregroundHidden(long binding) { if (binding == epoch && handoff()) nativeHidden = true; }
    public synchronized boolean nativeOwned(boolean nativeVisible, boolean ancestorsVisible) {
        if (!handoff() || !nativeHidden || !presented || !nativeVisible || !ancestorsVisible) return false;
        phase = Phase.NATIVE_OWNED; return true;
    }
    public synchronized boolean handoff() { return phase == Phase.HANDOFF || phase == Phase.NATIVE_OWNED; }
    public synchronized boolean ready() { return presented && phase != Phase.ENDED; }
    public synchronized boolean foregroundHiddenCommitted() { return nativeHidden && handoff(); }
    /** A presentation never qualifies a different selected window or replacement layer. */
    public synchronized boolean readyFor(long id, long windowId, long layer) {
        return id >= 0 && windowId > 0 && layer > 0 && session == id
                && window == windowId && surface == layer && ready();
    }
    public synchronized Phase phase() { return phase; }
    public synchronized long epoch() { return epoch; }
    public synchronized long drawSequence() { return drawSequence; }
    public synchronized void end() { phase = Phase.ENDED; freshDraw = presented = nativeHidden = producerProof = false; }
}
