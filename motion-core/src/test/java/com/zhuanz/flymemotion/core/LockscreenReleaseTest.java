package com.zhuanz.flymemotion.core;

import java.util.ArrayList;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LockscreenReleaseTest {
    private LockscreenRelease.Input input(float progress, float vy, boolean dismissible) {
        return new LockscreenRelease.Input(progress, vy, 0, 3, -200, dismissible);
    }
    @Test public void fastReleaseAndZeroHeightUseSamePolicy() {
        for (float p : new float[]{.95f, .4f, 0})
            assertFalse(LockscreenRelease.decide(input(p, -2100, true), MotionConfig.defaults(), t -> false).expand());
    }
    @Test public void downThenUpWithoutMoveStillOwnsNativeAcceptedVerticalRelease() {
        assertTrue(LockscreenRelease.upwardReleasePastSlop(4, -180, 24));
        assertFalse(LockscreenRelease.upwardReleasePastSlop(4, -20, 24));
        assertFalse(LockscreenRelease.upwardReleasePastSlop(180, -70, 24));
        assertFalse(LockscreenRelease.upwardReleasePastSlop(4, 180, 24));
        assertFalse(LockscreenRelease.upwardReleasePastSlop(Float.NaN, -180, 24));
    }
    @Test public void secureReleaseUsesNativeAuthenticationInteraction() {
        var types = new ArrayList<Integer>();
        var decision = LockscreenRelease.decide(input(.4f, -2100, false), MotionConfig.defaults(), t -> { types.add(t); return false; });
        assertEquals(java.util.List.of(8, 8), types); assertFalse(decision.expand());
    }
    @Test public void stationaryReleaseUsesNativeFlingTypeAndDistance() {
        var types = new ArrayList<Integer>();
        // A stationary release short of the threshold springs back ...
        var short_ = LockscreenRelease.decide(input(.4f, 0, true), MotionConfig.defaults(), t -> { types.add(t); return false; });
        assertTrue(short_.expand());
        // ... and one that covered more than the threshold unlocks, which for a dismissible
        // lockscreen is the whole point of a slow drag.
        var past = LockscreenRelease.decide(input(.8f, 0, true), MotionConfig.defaults(), t -> { types.add(t); return false; });
        assertFalse(past.expand());
        // Both still consult the two native interaction types, in this order.
        assertEquals(java.util.List.of(4, 7, 4, 7), types);
    }
    @Test public void eitherNativeRejectionRebounds() {
        for (int rejected : new int[]{1, 2}) {
            int[] calls = {0};
            var decision = LockscreenRelease.decide(input(.3f, -2100, true), MotionConfig.defaults(), t -> ++calls[0] == rejected);
            assertTrue(decision.expand()); assertTrue(decision.falseTouch());
        }
    }
    @Test public void reversingReleaseRebounds() {
        assertTrue(LockscreenRelease.decide(input(.3f, 2100, true), MotionConfig.defaults(), t -> false).expand());
    }
    @Test public void slowReleaseUsesPinnedDistanceThreshold() {
        // Too slow to be a flick: the distance decides, and past the threshold the unlock commits.
        assertFalse(LockscreenRelease.decide(input(.6f, -100, true), MotionConfig.defaults(), t -> false).expand());
        assertTrue(LockscreenRelease.decide(input(.4f, -100, true), MotionConfig.defaults(), t -> false).expand());
    }
    @Test public void invalidNativeSnapshotDoesNotQueryFalsing() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY}) {
            try { LockscreenRelease.decide(input(.5f, invalid, true), MotionConfig.defaults(), t -> { fail(); return false; }); fail(); }
            catch (IllegalArgumentException expected) {}
        }
    }
    @Test public void gestureHistoryIsBoundedAndKeepsLatestFailure() {
        GestureJournal journal = new GestureJournal();
        for (int i = 0; i < 200; i++) journal.add(i, "x".repeat(400));
        journal.add(201, "native:shortcut\nblocked");
        assertTrue(journal.snapshot().length() <= 8192);
        assertTrue(journal.snapshot().endsWith("g201:native:shortcut blocked"));
        assertFalse(journal.snapshot().contains("g0:"));
    }
}
