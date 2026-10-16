package com.zhuanz.flymemotion.hook;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import com.zhuanz.flymemotion.core.MotionFrame;
import java.lang.reflect.Method;

/** Let the OEM Drawable track RenderNode positions/crop and aggregate its own blur ABI. */
final class NativeBlurSheet {
    private final Method create, radius, crop, blends, invalidate, corners;
    private int cornerPx;
    private ViewGroup window;
    private View sheet;
    private Drawable drawable;
    private boolean clipChildren, clipPadding;
    /** Null when the blur Drawable can be cropped to the sheet on this build; otherwise no sheet blur. */
    final String missing;
    /**
     * True when the region nudge had to go through the platform {@link Drawable} API instead of
     * {@code invalidateBlurRegion}. Reported in the compat detail so the fallback is visible rather
     * than silent.
     */
    final boolean invalidateFallback;
    /**
     * Set when the sheet failed at runtime, with the reason. This layer is optional and it is
     * created from the composition's rebuild path, which the warm pose calls without a guard; an
     * escaping throw there reaches the pre-draw listener and disables the entire SystemUI
     * adaptation. Every entry point therefore swallows its own failure and turns this layer off
     * instead of taking the gesture path down with it.
     */
    private volatile String runtimeFailure;
    private volatile boolean disabled;
    NativeBlurSheet(ClassLoader loader) throws ClassNotFoundException {
        Class<?> root = Class.forName("android.view.ViewRootImpl", false, loader);
        Class<?> type = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable", false, loader);
        create = NativeReflection.methodOrNull(root, "createBackgroundBlurDrawable");
        // Per-corner since Android 12. Without it the blur stays square rather than rounding its top.
        corners = NativeReflection.methodOrNull(type, "setCornerRadius", float.class, float.class, float.class, float.class);
        radius = NativeReflection.methodOrNull(type, "setBlurRadius", int.class);
        // setCropRect/setBlendModes are MIUI additions to the AOSP Drawable.
        crop = NativeReflection.methodOrNull(type, "setCropRect", boolean.class, Rect.class);
        blends = NativeReflection.methodOrNull(type, "setBlendModes", int[][].class);
        invalidate = NativeReflection.methodOrNull(type, "invalidateBlurRegion");
        // invalidateBlurRegion is NOT required. It is a region nudge on the same Drawable, and this
        // build does not have it (the module's own probe reports "invalidateBlurRegion missing"),
        // yet createBackgroundBlurDrawable/setBlurRadius/setCropRect all exist. Treating the nudge as
        // mandatory therefore disabled the whole blur sheet on a build that can render it: the
        // receipt showed "解锁模糊层|off|invalidateBlurRegion missing" while every member the blur
        // actually needs was present. Drawable#invalidateSelf plus a View invalidate is the platform
        // equivalent - the drawable is this sheet View's background, so redrawing it re-reads the
        // radius and crop that were just written.
        invalidateFallback = invalidate == null;
        String miss = create == null ? "ViewRootImpl#createBackgroundBlurDrawable" : radius == null ? "setBlurRadius"
                : crop == null ? "setCropRect" : null;
        missing = miss == null ? null : miss + " missing";
    }
    /** True while this layer can still be used; false once a member is missing or it has failed. */
    boolean enabled() { return missing == null && !disabled; }
    /** Why the layer is off: the missing member, or the runtime failure that turned it off. */
    String disableReason() { return missing != null ? missing : runtimeFailure; }
    void begin(View windowView, Object root) {
        // Retry any sheet whose removal failed earlier, before adding another one.
        if (!pendingDetach.isEmpty()) detach(null);
        if (!enabled()) return;
        try {
            if (!(windowView instanceof ViewGroup group)) throw new IllegalStateException("Shade root is not a ViewGroup");
            window = group;
            clipChildren = group.getClipChildren(); clipPadding = group.getClipToPadding();
            group.setClipChildren(false); group.setClipToPadding(false);
            drawable = (Drawable) NativeReflection.invoke(create, root);
            NativeReflection.invoke(radius, drawable, 0);
            // Same bottom corners as the wallpaper sheet above it; the top edge stays square.
            if (corners != null) NativeReflection.invoke(corners, drawable, 0f, 0f, (float) cornerPx, (float) cornerPx);
            sheet = new View(group.getContext());
            sheet.setTag("FlymeMotion-NativeBlurSheet");
            sheet.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            sheet.setClickable(false); sheet.setFocusable(false);
            sheet.setBackground(drawable);
            // Draw before the original controls: the native Drawable's transparent SRC must never
            // erase clock/notifications. It is not a new window or an input target.
            group.addView(sheet, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable failed) {
            failSoft("begin", failed);
        }
    }
    boolean cornersSupported() { return corners != null; }
    void corner(int px) {
        cornerPx = px;
        if (!enabled()) return;
        try {
            if (drawable != null && corners != null) NativeReflection.invoke(corners, drawable, 0f, 0f, (float) px, (float) px);
        } catch (Throwable failed) { failSoft("corner", failed); }
    }
    void apply(MotionFrame frame, int blurRadius, int color) {
        applyRegion(frame.blurY(), frame.blurHeight(), blurRadius, color);
    }
    void applyRegion(float screenY, float height, int blurRadius, int color) {
        if (!enabled() || sheet == null || drawable == null || window == null) return;
        try {
            // Root motion must not shift the native blur sheet a second time.
            sheet.setTranslationY(screenY - window.getTranslationY());
            NativeReflection.invoke(radius, drawable, blurRadius);
            if (blends != null) NativeReflection.invoke(blends, drawable, (Object) (blurRadius == 0 ? new int[0][] : new int[][]{{9, color}}));
            NativeReflection.invoke(crop, drawable, true, new Rect(0, 0, window.getWidth(), Math.max(0, Math.round(height))));
            drawable.setVisible(blurRadius > 0 && height > 0, false);
            // See invalidateFallback: the native region nudge is a platform Drawable invalidation here.
            if (invalidate != null) NativeReflection.invoke(invalidate, drawable);
            else { drawable.invalidateSelf(); sheet.invalidate(); }
        } catch (Throwable failed) { failSoft("apply", failed); }
    }
    void release() {
        // Order matters. The View has to leave the hierarchy and the clip flags have to be restored
        // even when the drawable nudge fails, because that nudge is a reflective call into an OEM
        // method whose handle dies with its ViewRoot. Guarding all three steps together meant one
        // failure skipped the removal and the clip restore, the fields were cleared anyway, and the
        // sheet could never be removed again: a rebuild then added a second MATCH_PARENT child while
        // the first stayed on screen, and the shade root kept clipChildren=false for good.
        Drawable owned = drawable; View detached = sheet; ViewGroup owner = window;
        drawable = null; sheet = null; window = null;
        detach(detached);
        if (owner != null) try {
            if (!owner.getClipChildren()) owner.setClipChildren(clipChildren);
            if (!owner.getClipToPadding()) owner.setClipToPadding(clipPadding);
        } catch (Throwable ignored) {
            // Cosmetic only: a root left with clipChildren=false draws slightly outside bounds.
        }
        if (owned != null) try {
            NativeReflection.invoke(radius, owned, 0);
            owned.setVisible(false, false);
        } catch (Throwable ignored) {
            // The handle is already gone with its ViewRoot; nothing left to switch off.
        }
    }
    /** Sheets whose removal failed once, kept so a later release/begin can retry. */
    private final java.util.List<View> pendingDetach = new java.util.ArrayList<>();
    /**
     * Remove a sheet View, remembering it if this attempt fails. Dropping the reference on a failed
     * removal is what made the leak permanent, so a failure keeps the View queued instead.
     */
    private void detach(View view) {
        if (view != null) pendingDetach.add(view);
        for (java.util.Iterator<View> it = pendingDetach.iterator(); it.hasNext(); ) {
            View candidate = it.next();
            try {
                candidate.setBackground(null);
                if (candidate.getParent() instanceof ViewGroup parent) parent.removeView(candidate);
                it.remove();
            } catch (Throwable retryLater) {
                // Left queued on purpose; the next release/begin tries again.
            }
        }
    }
    /** Turn this optional layer off for good and report why, without letting the throw escape. */
    private void failSoft(String stage, Throwable failed) {
        disabled = true;
        runtimeFailure = stage + " failed: " + failed;
        try { release(); } catch (Throwable ignored) { }
    }
}
