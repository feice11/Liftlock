package com.zhuanz.flymemotion.hook;

import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceControl;
import com.zhuanz.flymemotion.core.BackdropBlend;

/** Full protection below Shade: live HOME fallback, independently of the return app's first frame. */
final class ServerSceneCover {
    private final Handler handler;
    private final BackdropBlend blend = new BackdropBlend();
    private SurfaceControl cover, home, parent, pendingHome, nativeHome;
    private long homeSource = -1, pendingSource = -1, homeGeneration = -1, binding, fadeToken = -1;
    private int layer, width, height;
    private boolean homeCommitted, protecting = true;
    private boolean fallbackDisabled;
    private boolean nativeHomeCommitted;
    private Runnable changed = () -> {};
    private java.util.function.Consumer<RuntimeException> failure = e -> android.util.Log.w("FlymeMotion", "Backdrop transition unavailable", e);
    ServerSceneCover(Handler handler) { this.handler = handler; }
    void onChanged(Runnable callback) { changed = callback; }
    void onFailure(java.util.function.Consumer<RuntimeException> callback) { failure = callback; }
    private static long id(SurfaceControl s) {
        return s == null || !s.isValid() ? -1 : ((Number) NativeReflection.call(s, "getLayerId")).longValue();
    }
    private static SurfaceControl mirror(SurfaceControl source) {
        SurfaceControl result = (SurfaceControl) NativeReflection.invoke(NativeReflection.method(SurfaceControl.class,
                "mirrorSurface", SurfaceControl.class), null, source);
        if (result == null || !result.isValid()) throw new IllegalStateException("Protection mirror unavailable");
        return result;
    }
    private void geometry(SurfaceControl.Transaction t, SurfaceControl s) {
        t.reparent(s, parent).setLayer(s, layer).setPosition(s, 0, 0).setScale(s, 1, 1);
        NativeReflection.invoke(NativeReflection.method(SurfaceControl.Transaction.class, "setCrop", SurfaceControl.class, Rect.class),
                t, s, new Rect(0, 0, width, height));
    }
    synchronized void bind(SurfaceControl source, SurfaceControl nextParent, int nextLayer, int w, int h, Runnable committed) {
        if (id(source) < 0 || id(nextParent) < 0 || w <= 0 || h <= 0)
            throw new IllegalStateException("Protection cover binding unavailable");
        SurfaceControl next = mirror(source), old = cover;
        parent = nextParent; layer = nextLayer; width = w; height = h;
        // A replacement cover inside a scene that has already revealed must stay revealed.
        //
        // The cover is re-bound whenever the scene stops, restarts and the mirror commits again, and
        // this device's cover journal shows exactly that twice inside one swipe
        // (retire, rearm, bind, retire, rearm, bind). Re-binding forced the new surface to alpha 1,
        // so the sheet's rounded bottom corner went square and then rounded again two or three times
        // per swipe - the flicker reported against the corner. The reveal belongs to the scene, not to
        // one cover surface, so it is evaluated before protect() resets the blend phase and carried on.
        boolean revealed = !protecting && !retired && blend.phase() != BackdropBlend.Phase.COVERED;
        float carriedAlpha = revealed ? blend.alpha(fadeToken, SystemClock.uptimeMillis()) : 1f;
        cover = next; retired = false; armExpiry = 0;
        if (!revealed) { protecting = true; blend.protect(); note("bind"); }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SurfaceControl fallback = id(pendingHome) > 0 ? pendingHome : homeCommitted && id(home) > 0 ? home : null;
            geometry(t, next);
            if (revealed) {
                t.setAlpha(next, carriedAlpha).setVisibility(next, carriedAlpha > .01f);
                note("bind-carried-reveal");
            } else {
                t.setAlpha(next, 1).setVisibility(next, fallback == null && !nativeHomeCommitted);
            }
            if (fallback != null) { geometry(t, fallback); t.setAlpha(fallback, 1).setVisibility(fallback, true); }
            if (id(old) > 0) t.setVisibility(old, false).reparent(old, null);
            t.addTransactionCommittedListener(handler::post, () -> { if (old != null) old.release(); committed.run(); }); t.apply();
        }
    }
    /** Caller has validated this HOME engine's current client identity, EGL swap and visibility. */
    synchronized void prepareHome(SurfaceControl source, long generation) {
        long sourceId = id(source);
        if (fallbackDisabled || !bound() || sourceId < 0 || generation <= 0 || sourceId == pendingSource) return;
        if (sourceId == homeSource && backdropReady()) { homeGeneration = generation; return; }
        // Wait for the previous atomic replacement before allocating another one.
        if (pendingHome != null) return;
        SurfaceControl next = mirror(source), old = home;
        long serial = ++binding;
        pendingHome = next; pendingSource = sourceId;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            geometry(t, next); t.setAlpha(next, protecting ? 1 : blend.alpha(fadeToken, SystemClock.uptimeMillis()))
                    .setVisibility(next, protecting || !blend.exposed());
            if (id(cover) > 0 && protecting) t.setVisibility(cover, false);
            if (id(old) > 0) t.setVisibility(old, false).reparent(old, null);
            t.addTransactionCommittedListener(handler::post, () -> {
                synchronized (ServerSceneCover.this) {
                    if (serial != binding || pendingHome != next) { next.release(); return; }
                    home = next; homeSource = sourceId; homeGeneration = generation; homeCommitted = true;
                    pendingHome = null; pendingSource = -1;
                    if (old != null) old.release();
                }
                changed.run();
            }); t.apply();
        }
    }
    synchronized boolean bound() { return id(cover) > 0; }
    synchronized boolean backdropReady() { return nativeHomeCommitted && id(nativeHome) > 0 || homeCommitted && id(home) > 0; }
    synchronized long backdropGeneration() { return backdropReady() ? homeGeneration : -1; }
    synchronized long backdropLayer() { return nativeHomeCommitted ? id(nativeHome) : backdropReady() ? id(home) : -1; }
    /** HOME has its own wallpaper below its icons; no foreground wallpaper clone is needed. */
    synchronized void useNativeHome(SurfaceControl source, long generation) {
        if (!bound() || id(source) <= 0 || generation <= 0) return;
        if (id(nativeHome) == id(source)) { homeGeneration = generation; return; }
        long serial = ++binding;
        SurfaceControl oldHome = home, oldPending = pendingHome;
        nativeHome = source; nativeHomeCommitted = false; home = pendingHome = null;
        homeCommitted = false; homeSource = pendingSource = -1; homeGeneration = generation;
        blend.protect(); protecting = true;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setVisibility(cover, false);
            for (SurfaceControl old : new SurfaceControl[]{oldHome, oldPending}) if (id(old) > 0) t.setVisibility(old, false).reparent(old, null);
            t.addTransactionCommittedListener(handler::post, () -> {
                synchronized (ServerSceneCover.this) {
                    if (oldHome != null) oldHome.release();
                    if (serial != binding || nativeHome != source) return;
                    nativeHomeCommitted = true;
                }
                changed.run();
            }); t.apply();
        }
    }
    synchronized void requireLockProtection() {
        if (nativeHome == null) return;
        ++binding; nativeHome = null; nativeHomeCommitted = false; homeGeneration = -1;
        protecting = false; protect();
    }
    synchronized boolean exposureComplete() { return blend.exposed(); }
    /**
     * A retired cover is dead until something re-binds it. Without this, a late {@code protect}
     * from outside the gesture - a hazard transaction, an idle scene publish, an invalidate - put
     * the wallpaper clone back on screen with alpha 1, and once the scene was over nothing faded it
     * out again: the layer then sat over the launcher showing the wallpaper and hiding every icon.
     * The cover is a wallpaper mirror, so that failure looks exactly like "the desktop lost its UI".
     */
    synchronized void protect(SurfaceControl.Transaction t) {
        // A re-arm waiting for a bind that never came must not keep this cover lit over the desktop.
        expireRearm();
        if (retired) { revealRefusal = "retired"; return; }
        // No phase gate here. The debounce that decides "transient dip vs genuinely un-presentable"
        // lives in the caller (SystemServerSceneHooks), which knows the actual readiness; this method
        // only ever does what it is told. The earlier "ignore protect once revealing" guard fixed the
        // corner flicker but was too strong: once the cover was EXPOSED it never re-covered, so a
        // scene whose return window stopped drawing was left showing "only the wallpaper" (the cover
        // transparent over a blank window) until a sleep/wake happened to reset it.
        if (!protecting) note("protect-relight");
        blend.protect(); protecting = true;
        if (nativeHomeCommitted && id(nativeHome) > 0) {
            if (bound()) t.setVisibility(cover, false);
            return;
        }
        SurfaceControl fallback = id(pendingHome) > 0 ? pendingHome : homeCommitted && id(home) > 0 ? home : null;
        if (fallback != null) t.setAlpha(fallback, 1).setVisibility(fallback, true);
        if (bound()) t.setAlpha(cover, 1).setVisibility(cover, fallback == null);
    }
    synchronized void reveal(boolean realSceneReady) {
        // Every gate below closes silently, and a cover that never fades reads the same in a receipt
        // as one that was never asked to. The refusal is therefore recorded per gate.
        if (retired) { revealRefusal = "retired"; return; }
        if (!realSceneReady) { revealRefusal = "not-ready"; return; }
        if (!bound()) { revealRefusal = "unbound"; return; }
        if (!protecting) { revealRefusal = "already-revealed"; return; }
        if (pendingHome != null) { revealRefusal = "pending-home"; return; }
        long token = blend.reveal(SystemClock.uptimeMillis(), true); fadeToken = token;
        if (token < 0) { revealRefusal = "blend-refused"; return; }
        revealCalls++;
        protecting = false;
        if (homeCommitted && id(home) > 0) { revealRefusal = "fading-home"; scheduleFade(token, 0); }
        else try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            revealRefusal = "fading-crossfade";
            t.setVisibility(cover, false);
            t.addTransactionCommittedListener(handler::post, () -> complete(token)); t.apply();
        }
    }
    /** Why the last {@link #reveal} did or did not start, and how many actually started. */
    synchronized String revealState() {
        return "reveal=" + revealRefusal + "/started=" + revealCalls + "/phase=" + blend.phase();
    }
    /** What the cover would reveal onto: without either of these it has no content to show. */
    synchronized String backdropState() {
        return "backdrop=" + backdropReady() + "/home=" + homeCommitted + "/native=" + nativeHomeCommitted
                + "/protecting=" + protecting + "/retired=" + retired + "/bound=" + bound()
                // Both mirror layers here are cropped to a plain rectangle (see geometry()), so while
                // either is still partly opaque it fills the region outside the wallpaper band's
                // rounded bottom corners, and the rounding only appears as they fade. The crop and the
                // live fade alpha are reported so that attribution is checkable instead of guessed.
                // cover and home share this alpha expression, so one value describes both.
                + "/crop=0,0," + width + "," + height + "/corner=0"
                + "/fadeAlpha=" + Math.round(blend.alpha(fadeToken, SystemClock.uptimeMillis()) * 1000) / 1000f
                + "/coverJournal=" + journal;
    }
    /**
     * Re-arms the cover for a scene that is starting.
     *
     * <p>The retired guard exists to stop a late {@code protect} from resurrecting the cover after a
     * scene has ended. It must not outlive that scene, though: {@link #bind} is only reached once the
     * mirror commits, so a scene whose commit never arrives would otherwise leave the cover dead for
     * good - no wallpaper under the lifting sheet, and a black lockscreen whenever the native side had
     * hidden its own copy too. Re-arming here scopes the guard to the window it was written for:
     * between the end of one scene and the start of the next.
     *
     * <p>A re-arm is only valid until the {@code bind} it is waiting for. This device measured the
     * re-arm firing repeatedly within tens of milliseconds of a retire, with no bind between them
     * (coverJournal: {@code 3287 retire, 3338 rearm, 3340 retire, 7619 rearm}); an unbounded re-arm
     * there is the same permanent-overlay failure the guard exists to prevent, because the scene was
     * already over and only {@code protect} needed one window to put the wallpaper clone back on the
     * desktop. The arming therefore expires: if the mirror never commits and nothing binds, the cover
     * returns to retired instead of staying live over the launcher forever.
     */
    synchronized void rearm() {
        retired = false; note("rearm");
        armExpiry = SystemClock.uptimeMillis() + REARM_WINDOW_MS;
        // Self-expiring as well as checked on the lighting paths: a scene can end without ever
        // calling protect/reveal again, and nothing else would come back to retire this cover.
        handler.removeCallbacks(expireTask);
        handler.postDelayed(expireTask, REARM_WINDOW_MS + 50);
    }
    private final Runnable expireTask = () -> {
        synchronized (ServerSceneCover.this) {
            if (!rearmExpired()) return;
            retire(new SurfaceControl.Transaction(), () -> {});
        }
    };
    /** How long a re-arm waits for its bind before the cover is retired again. See {@link #rearm}. */
    private static final long REARM_WINDOW_MS = 1200;
    private long armExpiry;
    /** True while a re-arm is still waiting for the bind that justifies it. */
    synchronized boolean rearmExpired() { return !retired && cover == null && armExpiry > 0 && SystemClock.uptimeMillis() > armExpiry; }
    /** Retire a re-arm whose bind never arrived; leaves a bound cover alone. */
    synchronized void expireRearm() {
        if (!rearmExpired()) return;
        retired = true; armExpiry = 0; note("rearm-expired");
    }
    /** Binding/retire/rearm order, so a cover left alive after the scene ended names its cause. */
    private final java.util.ArrayDeque<String> journal = new java.util.ArrayDeque<>();
    private void note(String what) {
        if (journal.size() >= 8) journal.removeFirst();
        journal.addLast((SystemClock.uptimeMillis() % 100000) + " " + what);
    }
    synchronized String journalState() { return journal.toString(); }
    private String revealRefusal = "none";
    private long revealCalls;
    /** See {@link #protect(SurfaceControl.Transaction)}: dead until the next bind. */
    private boolean retired;
    private void scheduleFade(long token, long delay) {
        handler.postDelayed(() -> {
            try { fade(token); }
            catch (RuntimeException error) {
                synchronized (this) {
                    if (!blend.current(token)) return;
                    fallbackDisabled = true; homeCommitted = false; blend.protect(); protecting = true;
                    // A failed optional fade must not crash the system_server Handler.
                    try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                        if (bound()) t.setAlpha(cover, 1).setVisibility(cover, true);
                        if (id(home) > 0) t.setVisibility(home, false);
                        t.apply();
                    } catch (RuntimeException restoreError) { error.addSuppressed(restoreError); }
                }
                failure.accept(error);
            }
        }, delay);
    }
    private void fade(long token) {
        synchronized (this) {
            if (!blend.current(token) || !backdropReady()) return;
            if (pendingHome != null) { scheduleFade(token, 8); return; }
            float alpha = blend.alpha(token, SystemClock.uptimeMillis());
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                t.setAlpha(home, alpha);
                if (bound()) t.setVisibility(cover, false);
                if (alpha == 0) {
                    t.setVisibility(home, false);
                    t.addTransactionCommittedListener(handler::post, () -> complete(token));
                }
                t.apply();
            }
            if (alpha > 0) scheduleFade(token, 8);
        }
    }
    private void complete(long token) {
        synchronized (this) { if (!blend.complete(token)) return; }
        changed.run();
    }
    synchronized void protect() {
        if (!protecting && bound()) try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) { protect(t); t.apply(); }
    }
    synchronized void retire(SurfaceControl.Transaction t, Runnable committed) {
        ++binding; blend.protect();
        // Retired until something binds again: see protect(Transaction).
        retired = true; armExpiry = 0; note("retire");
        SurfaceControl old = cover, oldHome = home, oldPending = pendingHome;
        cover = home = pendingHome = parent = nativeHome = null; protecting = true; homeCommitted = nativeHomeCommitted = false;
        homeSource = pendingSource = homeGeneration = -1;
        for (SurfaceControl s : new SurfaceControl[]{old, oldHome, oldPending}) if (id(s) > 0) t.setVisibility(s, false).reparent(s, null);
        t.addTransactionCommittedListener(handler::post, () -> {
            if (old != null) old.release(); if (oldHome != null) oldHome.release();
            // Pending prepare callbacks own their wrapper and reject this retired binding.
            committed.run();
        });
    }
}
