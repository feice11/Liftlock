package com.zhuanz.flymemotion.core;

/** Formulas verified against the supplied Flyme 12.6 SystemUI, adapted to native blur ratios. */
public final class FlymeMotion {
    private FlymeMotion() {}
    public static float clamp(float n, float lo, float hi) { return Math.max(lo, Math.min(hi, n)); }
    public static float progress(float height, float fullHeight) {
        return Float.isFinite(height) && Float.isFinite(fullHeight) && fullHeight > 0
                ? clamp(height / fullHeight, 0, 1) : 1;
    }
    public static float keyguardY(float p, float height, boolean dismissible) {
        return -height * (1 - clamp(p, 0, 1)) / (dismissible ? 1 : 8);
    }
    public static float nativeBlurRatio(float p, float strength) {
        // Flyme backgroundBlurBg maps p=1..0.7 to radius=0..80. Keep only its ratio;
        // HyperOS owns the actual kernel, radius, material, blending and availability.
        return clamp((1 - p) / .3f, 0, 1) * clamp(strength, 0, 1);
    }
    /**
     * The release decision as "the sheet springs back rather than unlocking".
     *
     * <p>Order matters. A release the finger actually flicked is decided by that flick: upward (or
     * mostly vertical at speed) unlocks, downward or sideways springs back. Only a release too slow
     * to be called a flick is decided by position, and there the distances are the other way round:
     * a drag that got further than {@link MotionConfig#threshold} commits the unlock, one that
     * stopped short of it springs back. Reading "past the threshold" as a spring-back - which this
     * did first - makes a long slow drag unable to unlock at all, because the only remaining way
     * out is a flick.
     */
    public static boolean expand(float p, float vy, float vx, float density, MotionConfig config) {
        float minimum = config.minimumVelocityDp * Math.max(.1f, density);
        if (Math.abs(vy) >= minimum) {
            float speed = (float) Math.hypot(vx, vy);
            return vy >= 0 || (speed > 0 && Math.abs(vy / speed) < .5f);
        }
        return p <= config.threshold;
    }
    public static float damping(float p) { return (float) Math.sin(clamp(p, 0, 1) * Math.PI / 2); }
}
