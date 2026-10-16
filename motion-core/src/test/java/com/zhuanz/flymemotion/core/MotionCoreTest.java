package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionCoreTest {
    @Test public void flymeDefaults() {
        MotionConfig c = MotionConfig.defaults();
        assertEquals(200, c.stiffness, 0); assertEquals(.81f, c.expandDamping, 0);
        assertEquals(.95f, c.closeDamping, 0); assertFalse(c.lateDesktopAnimation);
    }
    @Test public void densityAndSpeedDetermineRelease() {
        MotionConfig c = MotionConfig.defaults();
        // A flick is decided by the flick itself, whichever way it points.
        assertFalse(FlymeMotion.expand(.2f, -800, 0, 3, c));
        assertTrue(FlymeMotion.expand(.8f, 800, 0, 3, c));
        // Sideways at speed is not an unlock.
        assertTrue(FlymeMotion.expand(.8f, -800, 2000, 3, c));
    }
    @Test public void aSlowDragUnlocksOnDistanceAndSpringsBackShortOfIt() {
        MotionConfig c = MotionConfig.defaults();
        // Too slow to be a flick, so position decides: past the threshold commits the unlock.
        assertFalse(FlymeMotion.expand(.6f, -100, 0, 3, c));
        assertTrue(FlymeMotion.expand(.4f, -100, 0, 3, c));
    }
    @Test public void thresholdIsStrictAndConfigurable() {
        MotionConfig c = MotionConfig.defaults();
        // Exactly at the threshold still springs back; just past it commits the unlock.
        assertTrue(FlymeMotion.expand(.5f, 0, 0, 3, c));
        assertFalse(FlymeMotion.expand(.5001f, 0, 0, 3, c));
        // The slider moves the distance a slow drag has to cover; the manager shows it as
        // "慢滑解锁距离 = 1 - threshold", so a lower threshold means an easier unlock.
        MotionConfig near = c.withThreshold(.15f);
        assertFalse(FlymeMotion.expand(.2f, 0, 0, 3, near));
        assertTrue(FlymeMotion.expand(.1f, 0, 0, 3, near));
    }
    @Test public void blurIsNativeRatioRatherThanFlymePixels() {
        assertEquals(0, FlymeMotion.nativeBlurRatio(1, 1), 0);
        assertEquals(.5f, FlymeMotion.nativeBlurRatio(.85f, 1), .00001);
        assertEquals(1, FlymeMotion.nativeBlurRatio(.7f, 1), .00001);
        assertEquals(.4f, FlymeMotion.nativeBlurRatio(.1f, .4f), .00001);
    }
    @Test public void nonDismissibleLockscreenHasOneEighthTranslation() {
        assertEquals(-800, FlymeMotion.keyguardY(.6f, 2000, true), .001);
        assertEquals(-100, FlymeMotion.keyguardY(.6f, 2000, false), .001);
    }
    @Test public void invalidParametersAreBounded() {
        MotionConfig c = new MotionConfig(true, false, Float.NaN, 99, -2, Float.POSITIVE_INFINITY, 0, 5, -1);
        assertEquals(200, c.stiffness, 0); assertEquals(1.5f, c.expandDamping, 0);
        assertEquals(.3f, c.closeDamping, 0); assertEquals(250, c.minimumVelocityDp, 0);
        assertEquals(.1f, c.threshold, 0); assertEquals(1, c.blurStrength, 0); assertEquals(0, c.revision);
    }
    @Test public void cornerRadiusFollowsScreenUnlessBounded() {
        MotionConfig c = MotionConfig.defaults();
        assertEquals(MotionConfig.FOLLOW_SCREEN, c.cornerRadiusDp, 0);
        assertEquals(MotionConfig.FOLLOW_SCREEN, c.withCornerRadius(-7).cornerRadiusDp, 0);
        assertEquals(MotionConfig.FOLLOW_SCREEN, c.withCornerRadius(Float.NaN).cornerRadiusDp, 0);
        assertEquals(MotionConfig.MAX_CORNER_DP, c.withCornerRadius(500).cornerRadiusDp, 0);
        assertEquals(0, c.withCornerRadius(0).cornerRadiusDp, 0);
        MotionConfig custom = c.withCornerRadius(24);
        assertEquals(24, custom.withEnabled(false).cornerRadiusDp, 0);
        assertEquals(24, custom.withLateDesktopAnimation(true).cornerRadiusDp, 0);
        assertEquals(24, custom.withBottomRegion(BottomGestureRegion.defaults()).cornerRadiusDp, 0);
        assertEquals(c.revision + 1, custom.revision);
    }
    @Test public void everyWithKeepsEveryOtherChoice() {
        // Each with… writes one field and must carry the rest over untouched. A forgotten field here
        // does not fail loudly: it silently resets the user's other settings whenever they move one
        // slider. All of them go through one copy helper for that reason, and this pins it.
        MotionConfig base = MotionConfig.defaults().withCornerRadius(24).withLateDesktopAnimation(true)
                .withBottomRegion(new BottomGestureRegion(false, .3f, .8f, .4f)).withThreshold(.2f);
        keepsTheRest(base.withEnabled(false), base, "enabled");
        keepsTheRest(base.withLateDesktopAnimation(false), base, "late");
        keepsTheRest(base.withCornerRadius(0), base, "corner");
        keepsTheRest(base.withThreshold(.7f), base, "threshold");
        keepsTheRest(base.withBottomRegion(BottomGestureRegion.defaults()), base, "region");
        // ... and the field each one is named for really is the one that changed.
        assertFalse(base.withEnabled(false).enabled);
        assertFalse(base.withLateDesktopAnimation(false).lateDesktopAnimation);
        assertEquals(0, base.withCornerRadius(0).cornerRadiusDp, 0);
        assertEquals(.7f, base.withThreshold(.7f).threshold, 0);
        assertEquals(BottomGestureRegion.defaults(), base.withBottomRegion(BottomGestureRegion.defaults()).bottomRegion);
    }
    /**
     * Every field except {@code changedField} must still hold exactly what {@code base} held, and the
     * revision must have advanced exactly once.
     */
    private static void keepsTheRest(MotionConfig changed, MotionConfig base, String changedField) {
        if (!"enabled".equals(changedField)) assertEquals(base.enabled, changed.enabled);
        if (!"late".equals(changedField)) assertEquals(base.lateDesktopAnimation, changed.lateDesktopAnimation);
        if (!"stiffness".equals(changedField)) assertEquals(base.stiffness, changed.stiffness, 0);
        if (!"expand".equals(changedField)) assertEquals(base.expandDamping, changed.expandDamping, 0);
        if (!"close".equals(changedField)) assertEquals(base.closeDamping, changed.closeDamping, 0);
        if (!"velocity".equals(changedField)) assertEquals(base.minimumVelocityDp, changed.minimumVelocityDp, 0);
        if (!"threshold".equals(changedField)) assertEquals(base.threshold, changed.threshold, 0);
        if (!"blur".equals(changedField)) assertEquals(base.blurStrength, changed.blurStrength, 0);
        if (!"corner".equals(changedField)) assertEquals(base.cornerRadiusDp, changed.cornerRadiusDp, 0);
        if (!"region".equals(changedField)) assertEquals(base.bottomRegion, changed.bottomRegion);
        assertEquals(base.revision + 1, changed.revision);
    }
    @Test public void configurationIsPinnedUntilSettled() {        GestureSession s = new GestureSession(); long token = s.begin();
        s.update(MotionConfig.defaults().withEnabled(false));
        assertTrue(s.config().enabled); s.finish(token); assertFalse(s.config().enabled);
    }
    @Test public void retouchIgnoresOldAnimatorCompletion() {
        GestureSession s = new GestureSession(); long old = s.begin(); long current = s.begin();
        s.finish(old); assertTrue(s.active()); s.finish(current); assertFalse(s.active());
    }
    @Test public void emergencyDisableInvalidatesTheGesture() {
        GestureSession s = new GestureSession(); long old = s.begin(); s.disable();
        assertFalse(s.config().enabled); assertFalse(s.active()); s.finish(old); assertFalse(s.config().enabled);
    }
    @Test public void controlCenterAodBouncerEditorAreExcluded() {
        MotionConfig c = MotionConfig.defaults();
        for (MotionPolicy.Surface p : MotionPolicy.Surface.values())
            assertEquals(p == MotionPolicy.Surface.LOCKSCREEN, MotionPolicy.permits(c, p, true, false));
    }
    @Test public void disabledUnsupportedAndFailedKeepNative() {
        assertFalse(MotionPolicy.permits(MotionConfig.defaults().withEnabled(false), MotionPolicy.Surface.LOCKSCREEN, true, false));
        assertFalse(MotionPolicy.permits(MotionConfig.defaults(), MotionPolicy.Surface.LOCKSCREEN, false, false));
        assertFalse(MotionPolicy.permits(MotionConfig.defaults(), MotionPolicy.Surface.LOCKSCREEN, true, true));
    }
    @Test public void springInitialPositionAndVelocityAreContinuous() {
        for (float damping : new float[]{.81f, .95f, 1, 1.4f}) {
            SpringCurve s = new SpringCurve(200, damping, 2.2f, 1500);
            assertEquals(0, s.valueAtSeconds(0), 1e-8);
            assertEquals(2.2f, s.velocityAtSeconds(0), 1e-6);
            assertEquals(1, s.interpolate(1), 0);
            assertEquals(1, s.valueAtSeconds(s.durationMillis / 1000.0), .001);
        }
    }
    @Test public void springSamplingIsFrameRateIndependent() {
        SpringCurve a = new SpringCurve(200, .81f, 1, 1200);
        for (int frame = 0; frame <= 60; frame++)
            assertEquals(a.valueAtSeconds(frame / 60.0), a.valueAtSeconds((frame * 2) / 120.0), 1e-12);
    }
    @Test public void springFiniteAndNativeHeightBoundedAcrossExtremes() {
        for (float stiffness : new float[]{50, 200, 800}) for (float damping : new float[]{.3f, .81f, 1, 1.5f})
            for (float velocity : new float[]{-80, 0, 80}) {
                SpringCurve s = new SpringCurve(stiffness, damping, velocity, 2000);
                for (int i = 0; i <= 200; i++) {
                    float p = s.interpolate(i / 200f); assertTrue(Float.isFinite(p));
                    MotionFrame frame = MotionFrame.sample(p * 2000, 0, 2000, true, 1);
                    assertTrue(frame.nativeHeight() >= 0 && frame.nativeHeight() <= 2000);
                }
            }
    }
}
