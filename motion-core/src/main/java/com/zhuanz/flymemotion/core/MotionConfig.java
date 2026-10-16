package com.zhuanz.flymemotion.core;

/** Immutable, bounded configuration. One instance is pinned for an entire gesture and settle. */
public final class MotionConfig {
    public final boolean enabled;
    public final boolean lateDesktopAnimation;
    public final float stiffness, expandDamping, closeDamping, minimumVelocityDp, threshold, blurStrength;
    public final long revision;
    public final BottomGestureRegion bottomRegion;
    /** Bottom corners of the lifted sheet in dp; FOLLOW_SCREEN uses the panel's own radius. */
    public final float cornerRadiusDp;
    public static final float FOLLOW_SCREEN = -1, MAX_CORNER_DP = 80;

    public MotionConfig(boolean enabled, boolean lateDesktopAnimation, float stiffness, float expandDamping,
                        float closeDamping, float minimumVelocityDp, float threshold, float blurStrength,
                        long revision) {
        this(enabled, lateDesktopAnimation, stiffness, expandDamping, closeDamping, minimumVelocityDp,
                threshold, blurStrength, revision, BottomGestureRegion.defaults());
    }

    public MotionConfig(boolean enabled, boolean lateDesktopAnimation, float stiffness, float expandDamping,
                        float closeDamping, float minimumVelocityDp, float threshold, float blurStrength,
                        long revision, BottomGestureRegion bottomRegion) {
        this(enabled, lateDesktopAnimation, stiffness, expandDamping, closeDamping, minimumVelocityDp,
                threshold, blurStrength, revision, bottomRegion, FOLLOW_SCREEN);
    }

    public MotionConfig(boolean enabled, boolean lateDesktopAnimation, float stiffness, float expandDamping,
                        float closeDamping, float minimumVelocityDp, float threshold, float blurStrength,
                        long revision, BottomGestureRegion bottomRegion, float cornerRadiusDp) {
        this.enabled = enabled;
        this.lateDesktopAnimation = lateDesktopAnimation;
        this.stiffness = bounded(stiffness, 50, 800, 200);
        this.expandDamping = bounded(expandDamping, .3f, 1.5f, .81f);
        this.closeDamping = bounded(closeDamping, .3f, 1.5f, .95f);
        this.minimumVelocityDp = bounded(minimumVelocityDp, 50, 2000, 250);
        this.threshold = bounded(threshold, .1f, .9f, .5f);
        this.blurStrength = bounded(blurStrength, 0, 1, 1);
        this.revision = Math.max(0, revision);
        this.bottomRegion = bottomRegion == null ? BottomGestureRegion.defaults() : bottomRegion;
        this.cornerRadiusDp = Float.isFinite(cornerRadiusDp) && cornerRadiusDp >= 0
                ? Math.min(MAX_CORNER_DP, cornerRadiusDp) : FOLLOW_SCREEN;
    }

    public static MotionConfig defaults() {
        return new MotionConfig(true, false, 200, .81f, .95f, 250, .5f, 1, 0);
    }

    public MotionConfig withEnabled(boolean value) {
        return copy(value, lateDesktopAnimation, stiffness, expandDamping, closeDamping,
                minimumVelocityDp, threshold, blurStrength, bottomRegion, cornerRadiusDp);
    }

    public MotionConfig withLateDesktopAnimation(boolean value) {
        return copy(enabled, value, stiffness, expandDamping, closeDamping, minimumVelocityDp,
                threshold, blurStrength, bottomRegion, cornerRadiusDp);
    }
    public MotionConfig withBottomRegion(BottomGestureRegion value) {
        return copy(enabled, lateDesktopAnimation, stiffness, expandDamping, closeDamping,
                minimumVelocityDp, threshold, blurStrength, value, cornerRadiusDp);
    }
    /** The distance a slow drag must cover: the manager shows it as {@code 1 - threshold}. */
    public MotionConfig withThreshold(float value) {
        return copy(enabled, lateDesktopAnimation, stiffness, expandDamping, closeDamping,
                minimumVelocityDp, value, blurStrength, bottomRegion, cornerRadiusDp);
    }
    public MotionConfig withCornerRadius(float dp) {
        return copy(enabled, lateDesktopAnimation, stiffness, expandDamping, closeDamping,
                minimumVelocityDp, threshold, blurStrength, bottomRegion, dp);
    }
    /** Every {@code with…} goes through here, so a new field can never be silently dropped. */
    private MotionConfig copy(boolean enabled, boolean lateDesktopAnimation, float stiffness, float expandDamping,
                              float closeDamping, float minimumVelocityDp, float threshold, float blurStrength,
                              BottomGestureRegion bottomRegion, float cornerRadiusDp) {
        return new MotionConfig(enabled, lateDesktopAnimation, stiffness, expandDamping, closeDamping,
                minimumVelocityDp, threshold, blurStrength, revision + 1, bottomRegion, cornerRadiusDp);
    }

    public static float bounded(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
