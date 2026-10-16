package com.zhuanz.flymemotion.ui

import com.zhuanz.flymemotion.core.MotionConfig
import kotlin.math.abs

/** The six advanced fields, as one draft the tuning page edits before a single save. */
data class MotionParams(
    val stiffness: Float,
    val expandDamping: Float,
    val closeDamping: Float,
    val minimumVelocityDp: Float,
    val threshold: Float,
    val blurStrength: Float,
) {
    /** Keeps the switch, late-desktop choice, bottom region and corners. */
    fun applyTo(c: MotionConfig): MotionConfig = MotionConfig(
        c.enabled, c.lateDesktopAnimation, stiffness, expandDamping, closeDamping,
        minimumVelocityDp, threshold, blurStrength, c.revision + 1, c.bottomRegion, c.cornerRadiusDp,
    )

    /** The same draft passed through MotionConfig's bounds, for the live preview. */
    fun preview(c: MotionConfig): MotionConfig = MotionConfig(
        c.enabled, c.lateDesktopAnimation, stiffness, expandDamping, closeDamping,
        minimumVelocityDp, threshold, blurStrength, c.revision, c.bottomRegion, c.cornerRadiusDp,
    )

    companion object {
        fun of(c: MotionConfig) = MotionParams(
            c.stiffness, c.expandDamping, c.closeDamping, c.minimumVelocityDp, c.threshold, c.blurStrength,
        )
        val Defaults = of(MotionConfig.defaults())
    }
}

/**
 * Named points in the existing configuration space. A preset writes only stiffness, the two
 * dampings and the two release thresholds; blur strength stays the user's.
 *
 * Measured with core SpringCurve (zero release velocity):
 * Flyme 默认 rebound 704ms / 1.3% overshoot, close 629ms;
 * 跟手 579ms / 2.0%, close 558ms; 柔和 875ms / 0.3%, close 925ms; 干脆 450ms / 0.1%, close 504ms.
 * A higher threshold unlocks after a shorter slow swipe (FlymeMotion.expand: p > threshold rebounds).
 */
enum class MotionPreset(
    val label: String,
    val summary: String,
    val stiffness: Float,
    val expandDamping: Float,
    val closeDamping: Float,
    val minimumVelocityDp: Float,
    val threshold: Float,
) {
    Flyme("原版", "原版 Flyme 手感，弹回时带一点过冲。", 200f, .81f, .95f, 250f, .5f),
    Responsive("跟手", "更快更轻，短一些的上滑就能解锁。", 300f, .78f, .92f, 200f, .58f),
    Gentle("柔和", "慢而稳，几乎没有过冲。", 130f, .88f, 1.0f, 300f, .45f),
    Crisp("干脆", "弹簧很硬，收起和弹回都利落。", 480f, .92f, 1.0f, 250f, .5f);

    fun applyTo(c: MotionConfig): MotionConfig = MotionConfig(
        c.enabled, c.lateDesktopAnimation, stiffness, expandDamping, closeDamping,
        minimumVelocityDp, threshold, c.blurStrength, c.revision + 1, c.bottomRegion, c.cornerRadiusDp,
    )

    fun matches(c: MotionConfig): Boolean =
        near(c.stiffness, stiffness, .5f) && near(c.expandDamping, expandDamping, .005f) &&
            near(c.closeDamping, closeDamping, .005f) && near(c.minimumVelocityDp, minimumVelocityDp, 5f) &&
            near(c.threshold, threshold, .005f)

    companion object {
        fun of(c: MotionConfig): MotionPreset? = entries.firstOrNull { it.matches(c) }
    }
}

private fun near(a: Float, b: Float, tolerance: Float) = abs(a - b) <= tolerance
