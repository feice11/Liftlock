package com.zhuanz.flymemotion.hook;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.View;
import com.zhuanz.flymemotion.PanelCorner;
import com.zhuanz.flymemotion.core.MotionFrame;
import java.lang.reflect.Method;

/** Owns a live mirror and native blur sheet; never writes the engine's original geometry. */
final class NativeWallpaperComposition {
    private final Method viewRoot, setMatrix, applyOnDraw, crop, containerLayer, cornerRadius;
    private final NativeBlurSheet blurSheet;
    private final NativeRegionalBlurLease regionalBlur;
    private final RuntimeReporter reporter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object root, shadeBlur;
    private View view;
    private SurfaceControl mirror, container;
    private final java.util.List<SurfaceControl> retiringContainers = new java.util.ArrayList<>();
    private long session = -1, generation = -1;
    private boolean active;
    private java.util.function.BooleanSupplier keyguardGone = () -> true;
    private java.util.function.Consumer<String> trace = s -> {};
    private long clampToken;
    private float cornerDp = Float.NaN;
    private int cornerPx;
    /** The mirror surface's height at rest; kept only for the receipt's mirrorH field. */
    private int mirrorHeight;
    /** Per-corner Transaction#setCornerRadius, when this build has it; preferred over the float form. */
    private final Method cornerRadius4;
    /** Corner decisions logged; see {@link #begin}. */
    private int cornerTrace;
    /**
     * begin() calls and container rebuilds. A rebuild allocates a SurfaceControl, releases and
     * re-creates the blur sheet, so one per frame is a defect rather than a refresh. The cached
     * {@code view} is only written on the rebuild path, so an early return there leaves it stale and
     * every later call re-enters the corner block: the two counters tell that apart from a genuinely
     * changing window, which the previous diag could not.
     */
    private long beginCalls, rebuilds;
    /** Crop bottom actually applied to the mirror and to the blur sheet in the same frame. */
    private long lastMirrorBottom = -1, lastBlurBottom = -1;
    /** Last composed state published, so the line is written on change instead of every frame. */
    private String lastComposeState = "";
    /** Last compose failure reported; see {@link #noteComposeFailure}. */
    private String lastComposeFailure = "";
    /** Null when the live mirror can be composed on this build; otherwise the missing member. */
    private final String missing;
    NativeWallpaperComposition(ClassLoader loader, RuntimeReporter reporter) throws ReflectiveOperationException {
        this.reporter = reporter;
        Class<?> rootClass = Class.forName("android.view.ViewRootImpl", false, loader);
        // Every mirror member is AOSP; a build missing one composes no mirror and the gesture runs
        // over the native wallpaper instead (the same mode as a wallpaper failure).
        viewRoot = NativeReflection.methodOrNull(View.class, "getViewRootImpl");
        applyOnDraw = NativeReflection.methodOrNull(rootClass, "applyTransactionOnDraw", SurfaceControl.Transaction.class);
        crop = NativeReflection.methodOrNull(SurfaceControl.Transaction.class, "setCrop", SurfaceControl.class, Rect.class);
        containerLayer = NativeReflection.methodOrNull(SurfaceControl.Builder.class, "setContainerLayer");
        Method surface = NativeReflection.methodOrNull(rootClass, "getSurfaceControl");
        missing = viewRoot == null ? "View#getViewRootImpl" : applyOnDraw == null ? "ViewRootImpl#applyTransactionOnDraw"
                : crop == null ? "Transaction#setCrop" : containerLayer == null ? "Builder#setContainerLayer"
                : surface == null ? "ViewRootImpl#getSurfaceControl" : null;
        // MIUI's keyguard wallpaper matrix setter; only observed for ownership, never required.
        setMatrix = NativeReflection.methodOrNull(rootClass, "setKeyguardMatrixAndAlpha", SurfaceControl.class, float[].class);
        // Optional: a system without layer corners still runs the scene, with square ends.
        cornerRadius = NativeReflection.methodOrNull(SurfaceControl.Transaction.class, "setCornerRadius", SurfaceControl.class, float.class);
        // Per-corner variant. The single-float call is documented as uniform, but on this build it is
        // not: held part-way, the bottom-RIGHT corner of the band renders as a ~140px arc (matching
        // cornerPx) while the bottom-LEFT stays perfectly straight to x=0, at 3x magnification. The
        // 4-arg overload names the corners explicitly, so the bottom pair can be requested directly.
        cornerRadius4 = NativeReflection.methodOrNull(SurfaceControl.Transaction.class, "setCornerRadius",
                SurfaceControl.class, float.class, float.class, float.class, float.class);
        blurSheet = new NativeBlurSheet(loader);
        regionalBlur = new NativeRegionalBlurLease(loader);
    }
    /** What this build supports, for the receipt's compat list. */
    void report(com.zhuanz.flymemotion.core.CompatLog log) {
        if (missing == null) log.on("壁纸跟随", ""); else log.off("壁纸跟随", missing + " missing");
        if (missing != null) log.off("解锁模糊层", "需要壁纸跟随");
        else if (blurSheet.enabled()) log.on("解锁模糊层", blurSheet.invalidateFallback ? "区域刷新用 Drawable#invalidateSelf" : "");
        else log.off("解锁模糊层", blurSheet.disableReason());
        if (missing != null) log.off("底部圆角", "需要壁纸跟随");
        else if (cornerRadius == null) log.off("底部圆角", "Transaction#setCornerRadius missing");
        else log.on("底部圆角", blurSheet.cornersSupported() ? "" : "模糊层为直角");
        if (setMatrix == null) log.note("壁纸跟随", "无 setKeyguardMatrixAndAlpha");
        if (!regionalBlur.wallpaperBlur()) log.note("区域模糊", "无 setWallpaperBlur");
    }
    boolean available() { return missing == null; }
    Method nativeSetter() { return setMatrix; }
    void unlockSignals(java.util.function.BooleanSupplier gone, java.util.function.Consumer<String> journal) {
        keyguardGone = gone; trace = journal;
    }
    void observeNative(SurfaceControl surface, float[] matrix) {}
    void observeInitialized(Object controller) {}
    boolean owns(Object owner) { return active && owner == root; }
    void captureBlur(Object controller) { shadeBlur = controller; }
    int fullWindowBlur(Object producer, Object owner, int radius) { return regionalBlur.fullWindowRequest(producer, owner, radius); }
    int wallpaperBlur(Object owner, int radius) { return regionalBlur.wallpaperRequest(owner, radius); }
    Object[] nativeUpdate(SurfaceControl surface, float[] matrix) { return null; }

    boolean begin(View window, NativeSceneChannel channel, float cornerDp) {
        beginCalls++;
        if (cornerDp != this.cornerDp || view != window) {
            this.cornerDp = cornerDp;
            cornerPx = cornerRadius == null ? 0 : Math.round(PanelCorner.radius(window, cornerDp));            blurSheet.corner(cornerPx);
            // The corner terms are otherwise invisible: shape() only ever leaves a radius on the
            // surfaces, so a wrong dp, a density mismatch and a radius that never reached the
            // transaction all look identical on screen (a square end). Report the decision itself,
            // with the counters that say why the block was entered, so "the corner did nothing" can
            // be told apart from "it never got here" and from a container rebuilt every frame.
            if (cornerTrace++ < 4) reporter.diag = "corner dp=" + cornerDp + " px=" + cornerPx
                    + " density=" + window.getResources().getDisplayMetrics().density
                    + " panelDp=" + Math.round(PanelCorner.screenDp(window))
                    + " method=" + (cornerRadius != null)
                    + " per4=" + (cornerRadius4 != null)
                    + " sameWindow=" + (view == window)
                    + " begin=" + beginCalls + " rebuilds=" + rebuilds
                    + " | " + reporter.diag;
        }
        if (missing != null) { reporter.wallpaper = "壁纸跟随不可用：" + missing + " missing"; return false; }
        NativeSceneChannel.MirrorState next = channel.mirror();
        if (active && view == window && next != null && session == next.session()
                && generation == next.generation() && mirror.isValid()) return true;
        if (next == null || !next.surface().isValid()) {
            reporter.wallpaper = "等待框架创建锁屏引擎的实时镜像"; return false;
        }
        SurfaceControl priorContainer = null, nextContainer = null;
        try {
            Object nextRoot = NativeReflection.invoke(viewRoot, window);
            if (nextRoot == null) return false;
            SurfaceControl parent = (SurfaceControl) NativeReflection.call(nextRoot, "getSurfaceControl");
            if (parent == null || !parent.isValid()) return false;
            rebuilds++;
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName("FlymeMotion-LockComposition")
                    .setParent(parent).setHidden(true).setOpaque(false);
            NativeReflection.invoke(containerLayer, builder);
            nextContainer = builder.build();
            // Replacement coverage is committed in the same draw transaction. Do not detach the
            // prior container immediately while the new ViewRoot frame is still queued.
            priorContainer = container;
            if (priorContainer != null) retiringContainers.add(priorContainer);
            blurSheet.release();
            regionalBlur.release(); active = false;
            root = nextRoot; container = nextContainer;
            view = window; mirror = next.surface(); session = next.session(); generation = next.generation();
            blurSheet.begin(window, root);
            active = true;
            long bindingSession = session, bindingGeneration = generation;
            long bindingLayer = ((Number) NativeReflection.call(mirror, "getLayerId")).longValue();
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                if (priorContainer != null && priorContainer.isValid()) t.setVisibility(priorContainer, false).reparent(priorContainer, null);
                // Negative child Z is below the Shade's UI buffer. Root RenderNode translation
                // moves that buffer's content; this independent Surface subtree stays stationary.
                t.setLayer(container, -10).setVisibility(container, true)
                        .reparent(mirror, container).setLayer(mirror, 0).setPosition(mirror, 0, 0)
                        .setScale(mirror, 1, 1).setAlpha(mirror, 1).setVisibility(mirror, true);
                // The rebuild happens with the sheet still at rest, so this is the mirror's natural
                // height and the denominator shape() scales the band against.
                mirrorHeight = Math.max(mirrorHeight, window.getHeight());
                shape(t, window.getWidth(), window.getHeight());
                final SurfaceControl retiring = priorContainer;
                t.addTransactionCommittedListener(main::post, () -> {
                    if (retiring != null && retiringContainers.remove(retiring)) retiring.release();
                    if (active && session == bindingSession && generation == bindingGeneration) {
                        reporter.wallpaper = "实时镜像已提交；等待原始锁屏容器与返回窗口切换";
                        channel.mirrorCommitted(bindingSession, bindingGeneration, bindingLayer, parent, window.getWidth(), window.getHeight());
                    }
                });
                submit(t);
            }
            return true;
        } catch (Throwable composeFailed) {
            // This path allocates a SurfaceControl, releases and re-creates the blur sheet, and talks
            // to SurfaceFlinger. begin() is reached from the warm pose without a guard, so an escaping
            // throw lands in the pre-draw listener's catch and disables the whole SystemUI adaptation.
            // The documented degradation for an unusable mirror is to compose nothing and run the
            // gesture over the native wallpaper, which is exactly what returning false gives here.
            noteComposeFailure(composeFailed);
            try { regionalBlur.release(); } catch (Throwable ignored) { }
            blurSheet.release();
            // Everything allocated for this attempt has to be released here. The transaction that
            // would have retired the previous container may never have been submitted, so its
            // committed-listener never runs and its queue entry would leak a SurfaceControl; the new
            // container is likewise never handed to anything that would release it.
            if (nextContainer != null && nextContainer != priorContainer && isValidSurface(nextContainer)) {
                try { nextContainer.release(); } catch (Throwable ignored) { }
            }
            if (priorContainer != null && retiringContainers.remove(priorContainer) && isValidSurface(priorContainer)) {
                try { priorContainer.release(); } catch (Throwable ignored) { }
            }
            // session/generation are cleared with the rest: leaving them set would let the
            // idempotent short-circuit at the top of begin() match a half-torn-down attempt if a
            // future reader ever calls it while active is still true.
            active = false; mirror = null; container = null; session = -1; generation = -1;
            return false;
        }
    }
    private static boolean isValidSurface(SurfaceControl surface) {
        try { return surface != null && surface.isValid(); } catch (Throwable invalid) { return false; }
    }
    /** Report a compose failure once per distinct cause; never let it escape or spam the receipt. */
    private void noteComposeFailure(Throwable failed) {
        String reason = "compose failed: " + failed;
        reporter.wallpaper = "镜面合成失败，改用原生壁纸：" + failed;
        if (reason.equals(lastComposeFailure)) return;
        lastComposeFailure = reason;
        if (reporter.diag == null) reporter.diag = reason;
        else reporter.diag = reason + " | " + reporter.diag;
        if (reporter.diag.length() > 1900) reporter.diag = reporter.diag.substring(0, 1900);
    }
    void apply(MotionFrame frame) { apply(frame, null); }
    void apply(MotionFrame frame, Runnable committed) {
        if (!active || mirror == null || !mirror.isValid())
            throw new IllegalStateException("Wallpaper mirror generation changed");
        int radius = 0, color = 0;
        if (frame.authenticationRequired() || frame.progress() >= 1) regionalBlur.release();
        else regionalBlur.begin(root, shadeBlur == null ? null
                : NativeReflection.get(NativeReflection.get(shadeBlur, "windowBlurUtils"), "blurUtils"));
        if (shadeBlur != null && frame.blurProgress() > 0) try {
            Object utils = NativeReflection.get(shadeBlur, "windowBlurUtils"), nativeBlur = NativeReflection.get(utils, "blurUtils");
            if (NativeReflection.bool(utils, "windowBlurAvailable")
                    && Boolean.TRUE.equals(NativeReflection.call(NativeReflection.get(utils, "windowBlurAvailableState"), "getValue"))
                    && (boolean) NativeReflection.call(nativeBlur, "supportsBlursOnWindows")) {
                radius = Math.round(((Number) NativeReflection.invoke(NativeReflection.method(nativeBlur.getClass(),
                        "blurRadiusOfRatio", float.class), nativeBlur, Math.min(1, frame.blurProgress()))).floatValue());
                color = (Math.round(NativeReflection.integer(shadeBlur, "dimAlpha") * frame.blurProgress()) << 24)
                        | NativeReflection.integer(shadeBlur, "dimRGB");
            }
        } catch (IllegalStateException | ClassCastException | NullPointerException otherBuild) {
            // Another build's Shade blur controller: no matching radius source, so the sheet stays clear.
            radius = 0; color = 0;
        }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            // Native child transforms (wake zoom, cover buffers, wallpaper offsets) are inherited.
            shape(t, view.getWidth(), Math.max(1, Math.round(frame.wallpaperHeight())));
            t.setAlpha(mirror, frame.wallpaperAlpha());
            if (committed != null) t.addTransactionCommittedListener(main::post, committed::run);
            submit(t);
        }
        blurSheet.apply(frame, radius, color);
        // cropH and blurH are printed together because the rounded bottom edge can only look right
        // if the mirror crop and the blur crop end at the same place; measured equal on this build.
        // cornerPx is the radius actually handed to the transaction this frame, and begin/rebuilds
        // say whether the container was re-created (which releases and re-makes the blur sheet).
        reporter.wallpaper = "原生 BackgroundBlurDrawable：r=" + radius + " cropH=" + Math.round(frame.wallpaperHeight())
                + " blurH=" + Math.round(frame.blurHeight()) + " cornerPx=" + cornerPx
                + " mirrorH=" + mirrorHeight
                + " begin=" + beginCalls + " rebuilds=" + rebuilds
                + " sheetY=" + Math.round(frame.blurY()) + " rootY=" + Math.round(view.getTranslationY())
                + " texScale=" + NativeReflection.optGet(root, "mTexScale") + " drawScale=" + NativeReflection.optGet(root, "mDrawSfScale");
        if (radius > 0) reporter.blur.incrementAndGet();
        reporter.wallpaperFrames.incrementAndGet();
    }
    /** HMC's idempotent scale correction, scoped to this owned root. */
    void alignBlurScale(Object owner) {
        if (!owns(owner)) return;
        // MIUI's texture/draw scale pair; a build without it has no mismatch to correct.
        if (!(NativeReflection.optGet(owner, "mTexScale") instanceof Number tex)
                || !(NativeReflection.optGet(owner, "mDrawSfScale") instanceof Number draw)) return;
        if (draw.floatValue() > 0 && tex.floatValue() > draw.floatValue() + .001f) {
            NativeReflection.set(owner, "mTexScale", draw.floatValue()); NativeReflection.optCall(owner, "checkSurTexSize");
        }
    }
    /**
     * Only the sheet's bottom edge is rounded.
     *
     * The crop starts one radius above the screen so the crop's top corners fall outside the display,
     * over nothing (the mirror has no content above y=0). setCrop + setCornerRadius(float) exist on
     * every Android 12+ build; HyperOS composes that radius as its continuous smooth corner
     * (persist.sys.support_window_smoothcorner), elsewhere it is the platform's circular corner.
     *
     * The band and its rounded edge are also applied to our OWN container, and that is the copy that
     * matters. Measured with the sheet held part-way, on the mirror these two calls do not take:
     * SurfaceFlinger reports geomCrop=[0 0 -1 -1] and geomContentCrop=[0 0 1440 3200] (full) even
     * though the call does not throw, while a setScale in the same transaction does land - the system
     * wallpaper surface's crop is owned and reset by MIUI's own wallpaper machinery (mTexScale /
     * mDrawSfScale / checkSurTexSize). The container is ours, so a crop there is the one that limits
     * the band, and a radius there is the one that rounds its bottom edge.
     *
     * A scale on the mirror was tried instead and reverted: it did move the geometry
     * (geomLayerBounds 1440x1748) but the uncropped 3200 source rows were mapped onto it, squashing
     * the wallpaper to 55%; the user saw one layer "折叠严重". Never scale this layer.
     */
    private void shape(SurfaceControl.Transaction t, int width, int bottom) {
        // Reverted experiment: starting the rect fully inside the buffer (top = 0 instead of
        // -cornerPx) to see whether the asymmetric rounding follows the crop's own corners. That
        // build never bound the mirror at all (receipt: wallpaperBound=false,
        // wallpaper=等待框架创建锁屏引擎的实时镜像, warm=unbound waiting-mirror for minutes), so the
        // test could not be read and the author's rect is restored. The asymmetry itself is measured
        // and is a property of MIUI's single-float Transaction#setCornerRadius (it rounds the band's
        // bottom-right only, on the mirror and on our own container alike), and this build has no
        // per-corner overload to name the left one - receipt: per4=false. Fixing it needs a
        // self-drawn corner or a second layer whose working corner lands on the left edge.
        Rect band = new Rect(0, -cornerPx, width, bottom);
        NativeReflection.invoke(crop, t, mirror, band);
        corners(t, mirror);
        if (container != null && container.isValid()) {
            NativeReflection.invoke(crop, t, container, band);
            corners(t, container);
        }
    }
    /**
     * Round the sheet's bottom pair on {@code target}.
     *
     * The float overload is documented as uniform but is not on this build: with the sheet held
     * part-way, the band's bottom-RIGHT corner renders as a ~140px arc (equal to cornerPx) while the
     * bottom-LEFT stays straight to x=0, measured at 3x magnification. The 4-arg overload names the
     * corners, and (0,0,px,px) puts the radius on the bottom pair under every common ordering -
     * (topLeft, topRight, bottomRight, bottomLeft) and (topLeft, topRight, bottomLeft, bottomRight)
     * both end with the two bottom corners.
     */
    private void corners(SurfaceControl.Transaction t, SurfaceControl target) {
        if (cornerRadius4 != null)
            NativeReflection.invoke(cornerRadius4, t, target, 0f, 0f, (float) cornerPx, (float) cornerPx);
        else if (cornerRadius != null)
            NativeReflection.invoke(cornerRadius, t, target, (float) cornerPx);
    }
    private void submit(SurfaceControl.Transaction t) {
        // After native unlock hides Shade there will be no ViewRoot draw to consume
        // this transaction. These are our independent mirror layers; commit their
        // final alpha/crop directly and retain the real transaction callback.
        if (view.getWindowVisibility() != View.VISIBLE || !view.isShown()) {
            t.apply();
            return;
        }
        if (!(boolean) NativeReflection.invoke(applyOnDraw, root, t))
            throw new IllegalStateException("ViewRoot rejected mirror transaction");
        view.invalidate();
    }
    /** Retain coverage until server atomically restores source/removes mirror and acknowledges. */
    Runnable retire() { return retire(false); }
    /**
     * After a completed unlock the desktop is already exposed, but MIUI's keyguard fade still
     * requests Shade and wallpaper blur; setWallpaperBlur self-blurs every wallpaper leash,
     * the desktop's included. Keep both radii at 0 until that fade has ended, then hand back
     * only what native asked for in the unlocked state (0 if the fade outlives the deadline).
     */
    Runnable retire(boolean unlocked) {
        SurfaceControl oldContainer = container;
        var oldRetiring = new java.util.ArrayList<>(retiringContainers); retiringContainers.clear();
        if (unlocked) clampUntilKeyguardGone(); else regionalBlur.release();
        blurSheet.release();
        active = false; container = mirror = null; root = null; view = null;
        session = generation = -1;
        return () -> {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                if (oldContainer != null && oldContainer.isValid()) t.reparent(oldContainer, null).setVisibility(oldContainer, false);
                for (SurfaceControl prior : oldRetiring)
                    if (prior.isValid()) t.reparent(prior, null).setVisibility(prior, false);
                t.apply();
            } finally {
                if (oldContainer != null) oldContainer.release();
                for (SurfaceControl prior : oldRetiring) prior.release();
            }
        };
    }
    void release() { retire().run(); }
    private void clampUntilKeyguardGone() {
        regionalBlur.clampAfterUnlock();
        if (!regionalBlur.clamped()) return;
        long token = ++clampToken, deadline = android.os.SystemClock.uptimeMillis() + 1500;
        Runnable check = new Runnable() {
            @Override public void run() {
                if (token != clampToken || !regionalBlur.clamped()) return;
                boolean gone = false;
                try { gone = keyguardGone.getAsBoolean(); } catch (Throwable ignored) {}
                boolean late = android.os.SystemClock.uptimeMillis() >= deadline;
                if (!gone && !late) { main.postDelayed(this, 16); return; }
                // Native may keep re-requesting during its fade; one more frame lets it settle.
                if (gone && !late && settle-- > 0) { main.postDelayed(this, 16); return; }
                trace.accept("blur:unlock-clamp end=" + (gone ? "keyguard-gone" : "deadline") + " nativeNonZero=" + regionalBlur.clampLog());
                regionalBlur.release(!gone);
            }
            private int settle = 2;
        };
        main.post(check);
    }
}

