package com.zhuanz.flymemotion.hook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import com.zhuanz.flymemotion.ConfigCodec;
import com.zhuanz.flymemotion.LockscreenPose;
import com.zhuanz.flymemotion.WallpaperLayerPose;
import com.zhuanz.flymemotion.TargetBuild;
import com.zhuanz.flymemotion.core.FlymeMotion;
import com.zhuanz.flymemotion.core.GestureSession;
import com.zhuanz.flymemotion.core.MotionConfig;
import com.zhuanz.flymemotion.core.SpringCurve;
import com.zhuanz.flymemotion.core.NativePanelHeight;
import com.zhuanz.flymemotion.core.MotionFrame;
import com.zhuanz.flymemotion.core.VisualHandoff;
import com.zhuanz.flymemotion.core.RevealGate;
import com.zhuanz.flymemotion.core.TouchSequence;
import com.zhuanz.flymemotion.core.TouchDispatch;
import com.zhuanz.flymemotion.core.DismissalHandoff;
import com.zhuanz.flymemotion.core.LockscreenRelease;
import com.zhuanz.flymemotion.core.GestureJournal;
import com.zhuanz.flymemotion.core.Members;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import io.github.libxposed.api.XposedInterface;

/** Target-specific adapter. All native touch dispatch, authentication and lifecycle callbacks proceed. */
final class SystemUiHooks {
    private static final String PANEL = "com.android.systemui.shade.NotificationPanelViewController";
    /**
     * The class carrying the keyguard-side panel state, in the order builds have provided it.
     * The inspected build has it as an object of its own; Android 15 HyperOS implements the same
     * role inside the panel itself, which is why the second name exists. Only "does this class
     * exist" is asked - no device and no version is consulted.
     */
    private static final String[] KEYGUARD = {
            "com.android.keyguard.panel.KeyguardPanelViewController",
            "com.android.systemui.shade.MiuiNotificationPanelViewController"};
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private final List<XposedInterface.HookHandle> installed = new ArrayList<>();
    private final GestureSession session = new GestureSession();
    private final TouchSequence input = new TouchSequence();
    private final TouchDispatch dispatch = new TouchDispatch();
    private final DismissalHandoff dismissal = new DismissalHandoff();
    private final LinkedHashSet<String> blockedReasons = new LinkedHashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GestureJournal journal = new GestureJournal();
    private final ThreadLocal<Fling> flingScope = new ThreadLocal<>();
    private final ThreadLocal<Long> releaseScope = new ThreadLocal<>();
    private final LockscreenPose pose = new LockscreenPose();
    private final WallpaperLayerPose wallpaperPose = new WallpaperLayerPose();
    private ViewTreeObserver frameObserver;
    private View frameWindow;
    private long pendingFinish = -1, renderedMirrorSession = -1, renderedMirrorGeneration = -1;
    private long finishCommit = -1, deferredFlingEnd = -1;
    private long recoveryFlingEnd = -1;
    private long presentedFinish = -1, nativeExitCommit = -1;
    private boolean nativeHideRequested;
    private MotionFrame closedFrame;
    private boolean warming, completingFling;
    /** Warm-pose retries spent waiting for the wallpaper mirror, and when the last one was issued. */
    private int warmUnbound;
    private long warmNudgeAt;
    /** Minimum gap between warm-pose frame requests; see applyWarmPose. */
    private static final long WARM_NUDGE_MS = 120;
    private long warmAppliedSession = -1, warmAppliedGeneration = -1;
    private boolean warmAppliedDismissible, warmReadyReported;
    private int warmWidth, warmHeight;
    private float renderedRawHeight = Float.NaN;
    // Which name this build's keyguard host uses for each member; resolved once in install().
    private String kgInteractive = "interactive", kgKeyguardShowing = "keyguardShowing",
            kgKeyguardBouncerShowing = "keyguardBouncerShowing", kgKeyguardOccluded = "keyguardOccluded",
            kgStatusBarState = "statusBarState", kgForegroundLayer = "keyguardForegroundLayer", kgInjector, kgShadeWindowView;
    /** The per-frame height writer install() resolved (a build may have either of its two names). */
    private Method heightWriter;
    /** The class the panel hooks were installed on; a MIUI subclass redeclares some of its fields. */
    private Class<?> panelType;

    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private Object panel, keyguard;
    private ValueAnimator ownedAnimator;
    private long ownedGeneration = -1;
    private boolean ready, failed, targetVersion, engaged, upwardDrag, lockGesture;
    private float lastProgress = 1;
    private float lastWallpaperProgress = 1;
    private float gestureMin = 1, gestureMax = 0;
    private String gestureReason = "等待原生拖动";
    private boolean gestureApplied;
    private int wallpaperObservations;
    private long verticalDownTime = -2;
    private View[] gestureLayers = new View[0];
    private Method releaseFling, releaseStop, releaseFalseTouch, showBouncer;
    private Method nativeFlingEnd, initDown;
    // Which shape of each native member this build has (see install()).
    private boolean initDownDirect, releaseDirect, falseTouchAosp, flingAosp, bouncerPosition;
    private NativeWallpaperComposition wallpaperMotion;
    private NativeSceneChannel liveScene;
    private LateDesktopAnimationHooks desktopAnimation;
    private boolean wallpaperFailed, holdForNativeExit, pendingAuthentication, waitingForScene;
    private boolean nativeDismissalRequested, nativeAuthenticationStarted;
    private boolean sleeping;
    private boolean restoringNativeExpansion;
    private long bottomDownTime = -1;
    /** Bounded so the region's own trace cannot flood the gesture journal. */
    private int bottomTrace;
    private boolean wakeVisualsTaken;
    private final RevealGate reveal = new RevealGate();
    private float rawHeight, frameVelocity, startProgress = 1, retouchOffset;
    private long sceneDeadline, sampleTime;
    private boolean renderScheduled;
    /** Panel position last seen after release, and when it last moved; see {@link #watchSettle}. */
    private float settleMark = -1;
    private long settleMarkAt;
    private long settleReported = -1;
    /** How long a released panel may sit part-way without moving before the gesture is recovered. */
    private static final long SETTLE_STALL_MS = 800;
    /**
     * How long the lock screen must stay ineligible before the warm scene is torn down.
     *
     * <p>mKeyguardInteractive flickers while the lock screen sits idle, so an immediate
     * {@code abandonPose()} on the first ineligible sample restarted the warm-up forever and the
     * mirror never committed: the receipt showed {@code scene=锁屏可接入} next to
     * {@code wallpaper=等待框架创建锁屏引擎的实时镜像}, {@code wallpaperBound=false}, and a
     * {@code TM:abandon ... scene=锁屏不可交互} every couple of seconds for minutes. Because the
     * abandon path also releases a gesture with no animator, the same flicker left a released sheet
     * parked mid-screen - the "fast swipe under half neither unlocks nor springs back" report.
     */
    private static final long WARM_ABANDON_MS = 600;
    /** When this process last saw the lock screen ineligible; 0 while it is eligible. */
    private long ineligibleSince;
    private final android.view.Choreographer.FrameCallback renderTick = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            renderScheduled = false;
            if(failed)return;
            if(holdForNativeExit){try{applyExitPose();if(holdForNativeExit)startRenderTick();}catch(Throwable t){fail(t);}return;}
            if (!session.active() || !(engaged || lockGesture && session.touching() || pendingFinish == session.generation())) return;
            try {
                boolean eligible = eligible();
                if (!eligible) {
                    // A flickering mKeyguardInteractive must not tear an active gesture: abandoning
                    // mid-gesture cancels the native fling ("no rebound"), and writing the height back
                    // corrupted progress() ("slow under-half unlocks"). Only a genuinely gone lock
                    // screen abandons. A transient sample must NOT skip this frame either - skipping
                    // re-parked the rebound animator on every flicker and read as a stutter - so the
                    // pose is still driven below and only the abandon is debounced.
                    if (trulyIneligible()) { abandonPose(); return; }
                } else {
                    ineligibleSince = 0;
                }
                // A released gesture that stops moving is stuck; see watchSettle. Checked before the
                // pose work so a parked sheet gets driven back instead of being re-presented as it is.
                if (!session.touching() && engaged && !nativeDismissalRequested) watchSettle();
                // Native hides the Shade root during exit; its pre-draw callback may stop.
                // Continue submitting the owned Surface transactions under the server cover.
                if(nativeDismissalRequested){applyPose();if(pendingFinish==session.generation())finish(session.generation());}
                else requestDraw();
            } catch (Throwable t) { fail(t); }
            if (!failed && session.active() && (engaged || lockGesture && session.touching() || pendingFinish == session.generation())) startRenderTick();
        }
    };
    private long heldGeneration = -1;
    /**
     * True only once the lock screen has stayed ineligible for {@link #WARM_ABANDON_MS}. Shared by the
     * warm watch and the render tick so that neither a warm scene nor an in-flight gesture is torn
     * down by a single flickering {@code mKeyguardInteractive} sample.
     */
    private boolean trulyIneligible() {
        long now = SystemClock.uptimeMillis();
        if (ineligibleSince == 0) { ineligibleSince = now; return false; }
        if (now - ineligibleSince >= WARM_ABANDON_MS) { ineligibleSince = 0; return true; }
        return false;
    }
    /**
     * Recover a released gesture whose panel has stopped part-way and is not moving.
     *
     * <p>{@code finish()} is the only caller of {@code recover()} and every one of its branches
     * requires {@code progress() <= .001f}, i.e. the panel has to reach a rest position before any of
     * this runs. The native height fling end is deliberately suppressed while the presented frame is
     * awaited and then completed later by {@code completeNativeFling}; when that completion does not
     * arrive, the sheet stays exactly where the finger left it - the half swipe that parked over the
     * desktop and never came back. Watching for "released, still engaged, part-way, no longer moving"
     * is independent of which of those paths failed, and recovering is the safe direction: an
     * unfinished swipe returns to the lock screen rather than sitting between the two.
     */
    private void watchSettle() {
        float p = progress();
        long now = SystemClock.uptimeMillis();
        if (Math.abs(p - settleMark) > .002f) { settleMark = p; settleMarkAt = now; return; }
        if (settleMarkAt == 0) { settleMarkAt = now; return; }
        // Only a genuinely mid-way panel is a stall. At either end the gesture is finishing on its
        // own and the position legitimately stops changing.
        boolean midway = p > .02f && p < .98f;
        if (!midway || now - settleMarkAt < SETTLE_STALL_MS) return;
        if (settleReported != session.generation()) {
            settleReported = session.generation();
            trace("stuck:no-move p=" + Math.round(p * 1000) / 1000f + " for=" + SETTLE_STALL_MS + "ms recover");
        }
        settleMarkAt = now;
        recover(true);
    }
    private final Runnable warmWatch = new Runnable() {
        @Override public void run() {
            if (failed) return;
            try {
                if (ready && panel != null && keyguard != null && !session.active() && !holdForNativeExit) {
                    if (eligible()) {
                        ineligibleSince = 0;
                        if (!warming) prepareScene(); else liveScene.update(canDismiss());
                    } else if (warming) {
                        // Debounced: see WARM_ABANDON_MS. Only a genuinely gone lock screen may
                        // release the warm scene; a flickering mKeyguardInteractive must not.
                        if (trulyIneligible()) abandonPose();
                    }
                }
            } catch (Throwable t) { fail(t); }
            if (!failed) main.postDelayed(this, 200);
        }
    };
    private final ViewTreeObserver.OnPreDrawListener frameDriver = () -> {
        if (holdForNativeExit) {
            try { applyExitPose(); } catch (Throwable t) { fail(t); }
            return true;
        }
        if (warming && !session.active()) {
            try { if (eligible()) applyWarmPose(); else abandonPose(); }
            catch (Throwable t) { fail(t); }
            return true;
        }
        if (failed || !session.active() || !(engaged || lockGesture && session.touching() || pendingFinish == session.generation())) return true;
        try {
            if (!eligible()) { abandonPose(); return true; }
            applyPose();
            if ((waitingForScene || pendingFinish == session.generation()) && finishCommit != session.generation()) {
                finish(session.generation());
            }
        } catch (Throwable t) { fail(t); }
        return true;
    };

    SystemUiHooks(MotionModule module, ClassLoader loader, RuntimeReporter reporter) {
        this.module = module; this.loader = loader; this.reporter = reporter;
    }

    void install() throws Throwable {
        Features features = new Features(module, installed, reporter.compat);
        try {
            // ---- Core: the panel's own drag, release and fling. Missing any of these keeps native. ----
            Class<?> panelClass = type(PANEL);
            panelType = panelClass;
            Class<?> keyguardClass = Members.load(loader, KEYGUARD);
            if (keyguardClass == null) throw new ClassNotFoundException(KEYGUARD[0]);
            if (!KEYGUARD[0].equals(keyguardClass.getName()))
                features.variant("面板拖动", "keyguard 宿主=" + keyguardClass.getName());
            Class<?> touchClass = type(PANEL + "$TouchHandler");
            Class<?> windowClass = type("com.android.systemui.shade.NotificationShadeWindowView");
            Method windowTouch = NativeReflection.method(windowClass, "dispatchTouchEvent", MotionEvent.class);
            wallpaperMotion = new NativeWallpaperComposition(loader, reporter);
            wallpaperMotion.report(reporter.compat);
            wallpaperMotion.unlockSignals(() -> {
                Object state = panel == null ? null : NativeReflection.get(panel, "mKeyguardStateController");
                return state != null && !NativeReflection.bool(state, "mShowing") && !NativeReflection.bool(state, "mKeyguardGoingAway")
                        && !NativeReflection.bool(state, "mKeyguardFadingAway");
            }, this::trace);
            desktopAnimation=new LateDesktopAnimationHooks(module,loader,reporter);desktopAnimation.install();
            if ("unsupported".equals(reporter.desktopAnimationState)) features.log.off("晚加载桌面", reporter.desktopAnimationDetail);
            else features.log.on("晚加载桌面", "");
            liveScene = new NativeSceneChannel(reporter, () -> {
                if (liveScene.consumeRevocation()) {
                    trace("scene:server-restored");
                    if (holdForNativeExit) { requestDraw(); return; }
                    if (warming || session.active()) {
                        if (!eligible()) { abandonPose(); return; }
                        // Keep finger physics and clock pose; only replace revoked composition.
                        //
                        // The revoked composition is what was driving the panel/wallpaper pose, so
                        // once the finger is up the pose must go back to rest here. Skipping it left
                        // the sheet parked at its last translated position - the partial-black
                        // lockscreen that followed a stuck slow swipe, where only the bottom strip of
                        // the wallpaper was still on screen. During a live drag the next pre-draw
                        // re-applies the pose, so this only fires after release.
                        if (!session.touching()) restorePose();
                        liveScene.end(wallpaperMotion.retire());
                        reporter.wallpaperBound = false;
                        warmAppliedSession = warmAppliedGeneration = renderedMirrorSession = renderedMirrorGeneration = -1;
                    }
                }
                if (warming || session.active() || holdForNativeExit) requestDraw();
            });
            // Compiler-generated names differ between builds: a $N-renamed method, a nest accessor
            // or the private method itself. Each lookup tries every shape this panel has had.
            Method touch = NativeReflection.method(touchClass, "onTouchEvent", MotionEvent.class);
            Method intercept = NativeReflection.method(touchClass, "onInterceptTouchEvent", MotionEvent.class);
            // MIUI's handler entry is handleMiuiTouch on HyperOS 2, handleTouch on HyperOS 4.
            Method drag = Members.suffixed(touchClass, "handleTouch", MotionEvent.class);
            if (drag == null) {
                drag = Members.suffixed(touchClass, "handleMiuiTouch", MotionEvent.class);
                if (drag != null) features.variant("面板拖动", "TouchHandler#handleMiuiTouch");
            }
            drag = required(drag, "TouchHandler#handleTouch");
            Method dragCall = drag;
            initDown = Members.accessor(panelClass, "initDownStates", MotionEvent.class);
            if (initDown == null) initDown = staticContaining(panelClass, "initDownStates", panelClass, MotionEvent.class);
            if (initDown == null) {
                initDown = required(Members.method(panelClass, Members.Sig.of("initDownStates", MotionEvent.class)), "initDownStates");
                initDownDirect = true; features.variant("面板拖动", "initDownStates 直接调用");
            }
            NativeReflection.field(panelClass, "mTouchHandler");
            Method listenerTouch = NativeReflection.methodOrNull(touchClass, "onTouch", View.class, MotionEvent.class);
            Class<?> injectorClass = Members.load(loader, "com.android.systemui.shade.NotificationPanelViewControllerInjector");
            Method externalTouch = injectorClass == null ? null : NativeReflection.methodOrNull(injectorClass, "handleExternalTouch", MotionEvent.class);
            Method shadeTouch = NativeReflection.methodOrNull(panelClass, "handleShadeTouch", MotionEvent.class);
            // MIUI renamed AOSP's setExpandedHeightInternal; both are the per-frame height writer.
            Method height = required(withBody(Members.method(panelClass, Members.Sig.of("setExpandedHeightInternalAosp", float.class),
                    Members.Sig.of("setExpandedHeightInternal", float.class)), keyguardClass,
                    Members.Sig.of("setExpandedHeightInternalAosp", float.class),
                    Members.Sig.of("setExpandedHeightInternal", float.class)), "setExpandedHeightInternal");
            heightWriter = height;
            Method fling = NativeReflection.method(panelClass, "flingToHeight", float.class, boolean.class, float.class, float.class, boolean.class);
            // MIUI isFalseTouch(int, float, float); AOSP isFalseTouch(float, float, int).
            releaseFalseTouch = withBody(NativeReflection.methodOrNull(panelClass, "isFalseTouch", int.class, float.class, float.class),
                    keyguardClass, Members.Sig.of("isFalseTouch", int.class, float.class, float.class));
            if (releaseFalseTouch == null) {
                releaseFalseTouch = required(withBody(NativeReflection.methodOrNull(panelClass, "isFalseTouch", float.class, float.class, int.class),
                        keyguardClass, Members.Sig.of("isFalseTouch", float.class, float.class, int.class)), "isFalseTouch");
                falseTouchAosp = true; features.variant("统一释放", "isFalseTouch(x,y,type)");
            }
            // MIUI fling$1(vel, collapseSpeedUp, expand, falsing); AOSP fling(vel, expand, collapseSpeedUp, falsing).
            releaseFling = withBody(Members.suffixed(panelClass, "fling", float.class, float.class, boolean.class, boolean.class),
                    keyguardClass, Members.Sig.of("fling", float.class, float.class, boolean.class, boolean.class));
            if (releaseFling == null) {
                releaseFling = required(withBody(Members.suffixed(panelClass, "fling", float.class, boolean.class, float.class, boolean.class),
                        keyguardClass, Members.Sig.of("fling", float.class, boolean.class, float.class, boolean.class)), "fling");
                flingAosp = true; features.variant("统一释放", "fling(vel,expand,speed,falsing)");
            }
            releaseStop = required(Members.suffixed(panelClass, "onTrackingStopped", boolean.class), "onTrackingStopped");
            nativeFlingEnd = NativeReflection.method(panelClass, "onFlingEnd", boolean.class);
            Class<?> keyguardViews = type("com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
            showBouncer = required(Members.method(keyguardViews, Members.Sig.of("showBouncer", String.class),
                    Members.Sig.of("showPrimaryBouncer", boolean.class), Members.Sig.of("showBouncer", boolean.class)), "showBouncer");
            Method nativeRelease = Members.accessor(panelClass, "endMotionEvent", MotionEvent.class, float.class, float.class, boolean.class);
            if (nativeRelease == null) nativeRelease = staticContaining(panelClass, "endMotionEvent", panelClass, MotionEvent.class, float.class, float.class, boolean.class);
            if (nativeRelease == null) {
                nativeRelease = required(Members.method(panelClass, Members.Sig.of("endMotionEvent", MotionEvent.class, float.class, float.class, boolean.class)),
                        "endMotionEvent");
                releaseDirect = true; features.variant("统一释放", "endMotionEvent 直接挂钩");
            }
            Method dozing = required(Members.suffixed(panelClass, "setDozing", boolean.class), "setDozing");
            Method elements = NativeReflection.method(keyguardClass, "updateKeyguardElementsExpansionInternal", float.class);
            NativeReflection.field(touchClass, "this$0");
            // Only the fields the gesture cannot run without. Others are read as optional below.
            for (String name : new String[]{"mView", "mBarState", "mDozing", "mExpandedHeight", "mHeightAnimator", "mVelocityTracker",
                    "mKeyguardStateController", "mInitialTouchFromKeyguard", "mInitialExpandX", "mInitialExpandY", "mTouchSlop",
                    "mStatusBarKeyguardViewManager"})
                NativeReflection.field(panelClass, name);
            for (String name : new String[]{"mShowing", "mKeyguardGoingAway", "mKeyguardFadingAway", "mCanDismissLockScreen"})
                NativeReflection.field(type("com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"), name);
            // The keyguard host spells these with an `m` prefix on HyperOS 2 and without one on
            // HyperOS 4; whichever shape this build has is resolved once, here.
            kgInteractive = keyguardField(keyguardClass, "interactive", "mInteractive");
            kgKeyguardShowing = keyguardField(keyguardClass, "keyguardShowing", "mKeyguardShowing");
            kgKeyguardBouncerShowing = keyguardField(keyguardClass, "keyguardBouncerShowing", "mKeyguardBouncerShowing");
            kgKeyguardOccluded = keyguardField(keyguardClass, "keyguardOccluded", "mKeyguardOccluded");
            kgStatusBarState = keyguardField(keyguardClass, "statusBarState", "mBarState");
            kgForegroundLayer = keyguardField(keyguardClass, "keyguardForegroundLayer", "mKeyguardForegroundLayer");
            kgInjector = keyguardFieldOrNull(keyguardClass, "keyguardPanelViewInjector", "mKeyguardPanelViewInjector");
            // Not every build keeps the window view as a field on this host; where it is absent the
            // root is taken from the panel view at call time (see shadeWindowView()).
            kgShadeWindowView = keyguardFieldOrNull(keyguardClass, "notificationShadeWindowView", "mNotificationShadeWindowView");
            NativeReflection.method(panelClass, "isTracking");
            NativeReflection.method(panelClass, "getMaxPanelTransitionDistance");
            features.log.on("面板拖动", "");
            features.log.on("统一释放", "");
            Method initDownCall = initDown;

            // ---- Optional parts: each one that cannot bind on this build is left native. ----
            features.optional("区域模糊", () -> {
                Class<?> rootType = type("android.view.ViewRootImpl");
                Class<?> blurUtils = type("com.android.systemui.statusbar.BlurUtils");
                // MIUI applyBlur(ViewRootImpl, int); AOSP applyBlur(ViewRootImpl, int, boolean opaque).
                Method fullBlur = required(Members.method(blurUtils, Members.Sig.of("applyBlur", rootType, int.class),
                        Members.Sig.of("applyBlur", rootType, int.class, boolean.class)), "BlurUtils#applyBlur");
                register(fullBlur, chain -> {
                    int requested = (int) chain.getArg(1);
                    int accepted = wallpaperMotion.fullWindowBlur(chain.getThisObject(), chain.getArg(0), requested);
                    if (accepted == requested) return chain.proceed();
                    Object[] args = chain.getArgs().toArray(); args[1] = accepted; return chain.proceed(args);
                });
                features.deopt("区域模糊", fullBlur);
                Method wallpaperBlur = NativeReflection.methodOrNull(rootType, "setWallpaperBlur", int.class);
                if (wallpaperBlur != null) {
                    register(wallpaperBlur, chain -> {
                        int requested = (int) chain.getArg(0);
                        int accepted = wallpaperMotion.wallpaperBlur(chain.getThisObject(), requested);
                        return accepted == requested ? chain.proceed() : chain.proceed(new Object[]{accepted});
                    });
                    features.deopt("区域模糊", wallpaperBlur);
                }
                Class<?> ratio = Members.load(loader, "com.miui.systemui.shade.blur.ShadeWindowBlurControllerImpl$updateBlurRatioRunnable$1");
                if (ratio != null) features.deopt("区域模糊", NativeReflection.methodOrNull(ratio, "run"));
                features.deopt("区域模糊", NativeReflection.methodOrNull(keyguardClass, "setWallpaperSyncBlur", float.class, View.class));
                Class<?> shadeBlur = Members.load(loader, "com.miui.systemui.shade.blur.ShadeWindowBlurControllerImpl");
                if (shadeBlur == null) features.variant("区域模糊", "无 MIUI 模糊控制器，解锁模糊层不着色");
                else for (var constructor : shadeBlur.getDeclaredConstructors())
                    register(constructor, chain -> {
                        Object result = chain.proceed(); wallpaperMotion.captureBlur(chain.getThisObject()); return result;
                    });
            });
            features.optional("模糊缩放修正", () -> {
                Method scale = required(NativeReflection.methodOrNull(type("android.view.ViewRootImpl"), "getReqSfScale"), "ViewRootImpl#getReqSfScale");
                register(scale, chain -> {
                    Object result = chain.proceed(); wallpaperMotion.alignBlurScale(chain.getThisObject()); return result;
                });
            });
            // The coordinated release sets fling-required exactly as native endMotionEvent does, but
            // native clears it in setPanelFlinging(false) only while still on keyguard; our fling
            // ends after unlock, so it stuck true and MIUI's KeyguardPanelViewController.updateVisibility
            // then showed the keyguard notification layer over every later shade fling. Native has
            // always cleared it by the time the stack becomes SHADE, so clear it there.
            features.optional("通知层修复", () -> {
                Class<?> stack = type("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
                NativeReflection.field(stack, "mAmbientState");
                NativeReflection.field(type("com.android.systemui.statusbar.notification.stack.AmbientState"), "mIsFlingRequiredAfterLockScreenSwipeUp");
                register(NativeReflection.method(stack, "setStatusBarState", int.class), chain -> {
                    Object result = chain.proceed();
                    if ((int) chain.getArg(0) == 0) {
                        Object ambient = NativeReflection.get(chain.getThisObject(), "mAmbientState");
                        if (ambient != null && NativeReflection.optBool(ambient, "mIsFlingRequiredAfterLockScreenSwipeUp", false)) {
                            NativeReflection.set(ambient, "mIsFlingRequiredAfterLockScreenSwipeUp", false);
                            trace("shade:cleared-fling-required");
                        }
                    }
                    return result;
                });
            });
            bouncerPosition = features.optional("认证页位置", () -> {
                NativeReflection.field(keyguardViews, "mPrimaryBouncerInteractor");
                NativeReflection.field(type("com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor"), "repository");
                NativeReflection.field(type("com.android.systemui.bouncer.data.repository.KeyguardBouncerRepositoryImpl"), "_keyguardPosition");
            });

            prefs = module.getRemotePreferences(ConfigCodec.GROUP);
            session.update(ConfigCodec.decode(prefs.getString(ConfigCodec.KEY, null)));
            prefsListener = (p, key) -> {
                if (key != null && !ConfigCodec.KEY.equals(key)) return;
                try {
                    MotionConfig next = ConfigCodec.decode(p.getString(ConfigCodec.KEY, null));
                    main.post(() -> {
                        session.update(next);
                        if (!next.enabled) { session.disable(); abandonPose(); }
                        reporter.configRevision = next.revision; reporter.enabled = next.enabled; reporter.publish();
                    });
                } catch (Throwable t) { main.post(() -> fail(t)); }
            };
            reporter.configRevision = session.config().revision; reporter.enabled = session.config().enabled;
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);

            for (var constructor : panelClass.getDeclaredConstructors()) register(constructor, chain -> {
                Object result = chain.proceed();
                try {
                    capturePanel(chain.getThisObject());
                } catch (Throwable t) { fail(t); }
                return result;
            });
            for (var constructor : keyguardClass.getDeclaredConstructors()) register(constructor, chain -> {
                Object result = chain.proceed(); keyguard = chain.getThisObject(); reporter.keyguardFound = keyguard != null; return result;
            });

            if (wallpaperMotion.nativeSetter() != null) register(wallpaperMotion.nativeSetter(), chain -> {
                if (wallpaperMotion.owns(chain.getThisObject())) ownsVisuals();
                if (!wallpaperMotion.owns(chain.getThisObject())) {
                    Object result = chain.proceed();
                    try { wallpaperMotion.observeNative((android.view.SurfaceControl) chain.getArg(0), (float[]) chain.getArg(1)); }
                    catch (Throwable t) { failWallpaper(t); }
                    return result;
                }
                Object[] args;
                try {
                    args = wallpaperMotion.nativeUpdate((android.view.SurfaceControl) chain.getArg(0), (float[]) chain.getArg(1));
                } catch (Throwable t) { failWallpaper(t); return chain.proceed(); }
                return args == null ? chain.proceed() : chain.proceed(args);
            });
            // Keep the final pose through the asynchronous native exit, instead of putting the
            // lock wallpaper back on screen in the gap between spring end and native dismissal.
            // MIUI's Kotlin callback objects are found by structure: their numbering shifts per build.
            features.optional("退场保持", () -> {
                Class<?> callback = required(Members.inner(loader, keyguardClass,
                        c -> Members.declares(c, "onKeyguardShowingChanged", boolean.class) && Members.enclosingInstance(c) == keyguardClass,
                        "keyguardUpdateMonitorInjectorCallback"), "onKeyguardShowingChanged callback");
                Method showing = NativeReflection.method(callback, "onKeyguardShowingChanged", boolean.class);
                register(showing, chain -> {
                    boolean owner = NativeReflection.get(chain.getThisObject(), "this$0") == keyguard;
                    if (owner && !(boolean) chain.getArg(0) && (holdForNativeExit || warming || engaged)) {
                        if (nativeDismissalRequested || holdForNativeExit && closedFrame != null && lastWallpaperProgress <= .001f) {
                            nativeHideRequested = true;
                            trace("native-exit:await-native-foreground-hide");
                        } else {
                            trace("native-exit:native-authentication-release"); abandonPose();
                        }
                    } else if (owner && (boolean) chain.getArg(0) && nativeHideRequested) {
                        trace("native-exit:cancelled-by-relock"); abandonPose();
                    }
                    Object result = chain.proceed();
                    if (owner && (holdForNativeExit || nativeDismissalRequested)) {requestDraw();startRenderTick();}
                    return result;
                });
                features.deopt("退场保持", showing);
                Method visibility = NativeReflection.methodOrNull(keyguardClass, "updateKeyguardElementsVisibility");
                if (visibility != null) {
                    register(visibility, chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() == keyguard && holdForNativeExit) requestDraw();
                        return result;
                    });
                    features.deopt("退场保持", visibility);
                }
            });
            features.optional("唤醒预备", () -> {
                Method wakeEnd = NativeReflection.methodOrNull(keyguardClass, "onWakeupAnimationEnd");
                if (wakeEnd != null) register(wakeEnd, chain -> {
                    Object result = chain.proceed();
                    if (chain.getThisObject() == keyguard) main.postDelayed(() -> {
                        if (!engaged && panel != null && eligible()) prepareScene();
                    }, 32);
                    return result;
                });
                // Prepare on native interactive wake, before its visual fade completes.
                Class<?> wake = required(Members.inner(loader, keyguardClass,
                        c -> Members.declares(c, "onStartedWakingUp") && Members.declares(c, "onStartedGoingToSleep")
                                && Members.enclosingInstance(c) == keyguardClass, "wakeObserver"), "wake observer");
                register(NativeReflection.method(wake, "onStartedWakingUp"), chain -> {
                    Object result = chain.proceed();
                    try {
                        sleeping = false;
                        keyguard = NativeReflection.get(chain.getThisObject(), "this$0");
                        Object injector = keyguardInjector();
                        Object owner = NativeReflection.optCall(injector, "getNotificationPanelViewController");
                        if (owner != null) capturePanel(owner);
                        reporter.keyguardFound = true;
                        if (eligible()) prepareScene();
                    } catch (Throwable t) { fail(t); }
                    return result;
                });
                register(NativeReflection.method(wake, "onStartedGoingToSleep"), chain -> {
                    try {
                        if (NativeReflection.get(chain.getThisObject(), "this$0") == keyguard) {
                            sleeping = true; trace("sleep:release-before-native-fade"); abandonPose();
                        }
                    } catch (Throwable t) { fail(t); }
                    return chain.proceed();
                });
            });
            features.optional("认证页让位", () -> {
                Class<?> monitor = required(Members.inner(loader, keyguardClass,
                        c -> Members.declares(c, "onKeyguardBouncerStateChanged", boolean.class) && Members.enclosingInstance(c) == keyguardClass,
                        "keyguardUpdateMonitorCallback"), "bouncer callback");
                Method bouncer = NativeReflection.method(monitor, "onKeyguardBouncerStateChanged", boolean.class);
                register(bouncer, chain -> {
                    if (NativeReflection.get(chain.getThisObject(), "this$0") == keyguard
                            && (boolean) chain.getArg(0) && (holdForNativeExit || warming)) abandonPose();
                    return chain.proceed();
                });
                features.deopt("认证页让位", bouncer);
            });
            features.optional("壁纸缩放接管", () -> {
                Method nativeScale = required(Members.unique(keyguardClass, "doDeductedImageScaleAnim"), "doDeductedImageScaleAnim");
                // This is the producer of THREE transformations: child view scales, home wallpaper
                // matrix and lock wallpaper matrix. Owning only the last setter leaves two racers.
                register(nativeScale, chain -> {
                    if (chain.getThisObject() == keyguard && ownsVisuals() && !holdForNativeExit) {
                        reporter.scaleSuppressed.incrementAndGet();
                        if (wallpaperObservations++ < 8) trace("scale:owned skip doDeductedImageScaleAnim value=" + chain.getArg(0));
                        return null;
                    }
                    return chain.proceed();
                });
                features.deopt("壁纸缩放接管", nativeScale);
                Class<?> anim = Members.load(loader, "com.miui.systemui.util.CustomValueAnim");
                if (anim != null) features.deopt("壁纸缩放接管", NativeReflection.methodOrNull(anim, "clearViewRootImplAddSurfaceControl", View.class));
                // Observe native changes independently of the removed mirror renderer.
                for (Members.Sig sig : new Members.Sig[]{Members.Sig.of("setWallpaperZoom", float.class),
                        Members.Sig.of("calculateWallpaperMatrixArray", float.class, float.class, float.class),
                        Members.Sig.of("initDeductedImageScale")}) {
                    String name = sig.name();
                    Method exact = Members.method(keyguardClass, sig);
                    Method observed = exact != null ? exact : Members.unique(keyguardClass, name);
                    if (observed == null) { features.variant("壁纸缩放接管", "无 " + name); continue; }
                    register(observed, chain -> {
                        if (!observed.getName().equals("calculateWallpaperMatrixArray")
                                && chain.getThisObject() == keyguard && ownsVisuals() && !holdForNativeExit) {
                            reporter.scaleSuppressed.incrementAndGet();
                            if (wallpaperObservations++ < 8) trace("scale:owned skip " + observed.getName());
                            return null;
                        }
                        if (chain.getThisObject() == keyguard && lockGesture && wallpaperObservations++ < 8)
                            trace("wallpaper:native " + observed.getName()
                                    + (observed.getParameterCount() == 0 ? "" : " value=" + chain.getArg(0))
                                    + " owned=" + engaged + " finger=" + session.touching() + " caller=" + nativeCaller());
                        Object result = chain.proceed();
                        if (observed.getName().equals("initDeductedImageScale") && chain.getThisObject() == keyguard)
                            try { wallpaperMotion.observeInitialized(keyguard); } catch (Throwable t) { failWallpaper(t); }
                        return result;
                    });
                    features.deopt("壁纸缩放接管", observed);
                }
                // The Folme listener invokes nativeScale directly; deoptimize the caller as well.
                Class<?> folme = Members.inner(loader, keyguardClass,
                        c -> java.util.Arrays.stream(c.getDeclaredMethods()).anyMatch(m -> m.getName().equals("onUpdate") || m.getName().equals("onComplete")),
                        "doWallpaperScaleAnim");
                if (folme != null) for (Method caller : folme.getDeclaredMethods())
                    if (caller.getName().equals("onUpdate") || caller.getName().equals("onComplete")) features.deopt("壁纸缩放接管", caller);
            });

            // Window dispatch is the single owner of finger lifetime. A ViewGroup may temporarily
            // turn this same event into child CANCEL; inner panel callbacks cannot end the finger.
            register(windowTouch, chain -> {
                MotionEvent event = (MotionEvent) chain.getArg(0);
                if (!ready || failed || keyguard == null
                        || chain.getThisObject() != shadeWindowView())
                    return chain.proceed();
                TouchDispatch.Frame frame = dispatch.enter(event.getActionMasked(), event.getDownTime(), event.getEventTime());
                try {
                    if (frame.outermost() && frame.action() == MotionEvent.ACTION_DOWN) {
                        beginGesture(event);
                        View window = (View) chain.getThisObject();
                        // The region is a hard reservation on the idle lockscreen: it no longer
                        // depends on this module's session being ready for the DOWN, so a busy
                        // or early-returned session can never hand the finger to a shortcut.
                        bottomDownTime = session.config().bottomRegion.contains(event.getX(), event.getY(), window.getWidth(), window.getHeight())
                                && regionCovers() ? event.getDownTime() : -1;
                        if (bottomDownTime >= 0) trace("bottom:reserved session=" + (session.touching() && eligible()));
                    }
                } catch (Throwable t) { fail(t); }
                long token = session.generation();
                if (lockGesture && frame.action() != MotionEvent.ACTION_MOVE)
                    trace("window:in a=" + frame.action() + " dt=" + frame.downTime() + " et=" + frame.eventTime());
                try { return chain.proceed(); }
                finally {
                    try {
                        if (frame.terminal() && token == session.generation() && session.touching()
                                && (frame.action() == MotionEvent.ACTION_CANCEL
                                    || (frame.outermost() && input.matches(frame.downTime())))) {
                            session.endInput(token);
                            summarizeGesture(frame.action() == MotionEvent.ACTION_CANCEL);
                            trace("window:end a=" + frame.action() + " spring=" + running());
                            if (frame.action() == MotionEvent.ACTION_CANCEL && engaged && !running()) recover(startProgress > .5f);
                            else if (!running()) queueFinish(token);
                            reporter.publish();
                        }
                    } catch (Throwable t) { fail(t); }
                    finally { if (frame.terminal() && bottomDownTime == frame.downTime()) bottomDownTime = -1; dispatch.exit(frame); }
                }
            });

            features.optional("底部解锁区域", () -> {
                // Native window gates and falsing already ran before this super dispatch. Reserve
                // only the original DOWN sequence; no child receives a partial stream or fake CANCEL.
                register(NativeReflection.method(ViewGroup.class, "dispatchTouchEvent", MotionEvent.class), chain -> {
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    if (bottomDownTime < 0 || bottomDownTime != event.getDownTime() || keyguard == null
                            || chain.getThisObject() != shadeWindowView()
                            || !dispatch.matches(event.getActionMasked(), event.getDownTime())) return chain.proceed();
                    // An unlock already in flight owns the screen; eat the finger instead.
                    if (holdForNativeExit || nativeAuthenticationStarted) return true;
                    // Start from the native panel's own down state so its drag sees a real DOWN ...
                    try {
                        nativeInitDown(initDownCall, event);
                        NativeReflection.invoke(dragCall, NativeReflection.get(panel, "mTouchHandler"), event);
                    } catch (Throwable t) { fail(t); return true; }
                    // ... then hand the rest of the finger to the module's own gesture. Returning
                    // true here used to keep every following MOVE out of NotificationShadeWindowView,
                    // and this module's follow-the-finger path is driven from that view's dispatch:
                    // the finger was reserved away from the shortcut and never reached the unlock.
                    if (bottomTrace++ < 4) trace("bottom:yield session=" + session.touching()
                            + " eligible=" + eligible() + " engaged=" + engaged);
                    return chain.proceed();
                });
                // Second guard: MIUI routes shortcut icons, left/right shortcut swipes, double-tap and
                Class<?> shortcuts = Members.load(loader, "com.android.keyguard.injector.KeyguardPanelViewInjector");
                Method exactTouch = shortcuts == null ? null : Members.method(shortcuts, Members.Sig.of("onTouchEvent",
                        MotionEvent.class, int.class, float.class, float.class, boolean.class, boolean.class, boolean.class));
                Method shortcutTouch = exactTouch != null || shortcuts == null ? exactTouch : Members.unique(shortcuts, "onTouchEvent");
                if (shortcutTouch == null || shortcutTouch.getParameterCount() == 0
                        || shortcutTouch.getParameterTypes()[0] != MotionEvent.class) {
                    features.variant("底部解锁区域", "无 MIUI 快捷方式入口");
                    return;
                }
                register(shortcutTouch, chain -> {
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    if (bottomDownTime >= 0 && event != null && event.getDownTime() == bottomDownTime) return false;
                    return chain.proceed();
                });
                features.deopt("底部解锁区域", shortcutTouch);
            });

            // These observe native acceptance, direction and routing only.
            register(intercept, chain -> {
                reporter.intercept.incrementAndGet();
                try {
                    captureTouchOwner(chain.getThisObject());
                    MotionEvent event = (MotionEvent) chain.getArg(0);
                    if (ready && !failed && event.getActionMasked() == MotionEvent.ACTION_DOWN) beginGesture(event);
                    observeDirection(event);
                } catch (Throwable t) { fail(t); }
                return chain.proceed();
            });
            register(touch, chain -> {
                reporter.touch.incrementAndGet();
                try { captureTouchOwner(chain.getThisObject()); } catch (Throwable t) { fail(t); }
                MotionEvent event = (MotionEvent) chain.getArg(0);
                try {
                    if (ready && !failed && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        beginGesture(event);
                        eligible();
                    }
                    observeDirection(event);
                } catch (Throwable t) { fail(t); }
                Object result = chain.proceed();
                if (dispatch.matches(MotionEvent.ACTION_UP, event.getDownTime())) {
                    if (lockGesture && verticalDownTime != event.getDownTime()) {
                        reporter.gesture = "外层收到锁屏触摸，原生垂直处理未进入；" + reporter.scene;
                    }
                    reporter.publish();
                }
                return result;
            });

            // The MIUI outer dispatcher can consume editor/shortcut events. Own motion only
            // inside the actual native vertical processor; observe the outer entry separately.
            register(drag, chain -> {
                reporter.drag.incrementAndGet();
                try { captureTouchOwner(chain.getThisObject()); } catch (Throwable t) { fail(t); }
                MotionEvent event = (MotionEvent) chain.getArg(0);
                int action = event.getActionMasked();
                if (!ready || failed) return chain.proceed();
                // The parent is still delivering MOVE/DOWN/UP. Do not let a temporary
                // child CANCEL clear native tracking/velocity or start a second fling.
                try { if (suppressChildCancel(event)) { trace("child-cancel:keep-window-finger"); return true; } }
                catch (Throwable t) { fail(t); return chain.proceed(); }
                try { if (action == MotionEvent.ACTION_DOWN) beginGesture(event); }
                catch (Throwable t) { fail(t); }
                // Recorded before the two match tests below, because those tests are themselves part
                // of why a finger can fail to take over. A recorder placed after them stayed empty
                // for exactly the gestures it was added to explain.
                try { noteDragGate(action == MotionEvent.ACTION_DOWN ? "down" : "move"); } catch (Throwable t) { fail(t); }
                if (!input.matches(event.getDownTime()) || !dispatch.matches(action, event.getDownTime()))
                    return chain.proceed();
                try {
                    verticalDownTime = event.getDownTime();
                    observeDirection(event);
                } catch (Throwable t) { fail(t); }
                Object result = chain.proceed();
                try {
                    if (action == MotionEvent.ACTION_MOVE) {
                        // Native establishes tracking DURING this call, including the first MOVE.
                        engageNativeDrag();
                        if (engaged && eligible()) requestDraw();
                        else if (lockGesture) gestureReason = reporter.scene;
                    }
                } catch (Throwable t) { fail(t); }
                return result;
            });

            register(height, chain -> {
                reporter.height.incrementAndGet();
                if (chain.getThisObject() != panel) return chain.proceed();
                try { engageNativeDrag(); } catch (Throwable t) { fail(t); }
                if (engaged && !running()) {
                    float next = (float) chain.getArg(0) + retouchOffset;
                    long now = SystemClock.uptimeMillis();
                    if (sampleTime > 0 && now > sampleTime) frameVelocity = (next - rawHeight) * 1000 / (now - sampleTime);
                    rawHeight = next; sampleTime = now;
                }
                Object result = chain.proceed();
                try { if (engaged && (session.touching() || running() || waitingForScene) && eligible()) requestDraw(); }
                catch (Throwable t) { fail(t); }
                return result;
            });
            register(dozing, chain -> {
                if (chain.getThisObject() == panel && (boolean) chain.getArg(0)) abandonPose();
                Object result = chain.proceed();
                if (chain.getThisObject() == panel && !(boolean) chain.getArg(0)
                        && keyguard != null && !session.active() && eligible()) prepareScene();
                return result;
            });
            register(elements, chain -> {
                boolean own = chain.getThisObject() == keyguard && ownsVisuals();
                if (chain.getThisObject() == keyguard && engaged && !own) abandonPose();
                Object result;
                if (own && !holdForNativeExit && !restoringNativeExpansion) {
                    // This method only updates visual scale/alpha flows and view properties.
                    // Keep the native layout intact while the complete roots follow the finger.
                    // Native panel height, blur, release and authentication still use actual progress.
                    Object[] args = chain.getArgs().toArray(); args[0] = 1f;
                    result = chain.proceed(args);
                } else result = chain.proceed();
                return result;
            });

            // Accessor form: (panel, event, x, y, cancel); direct form: this=panel, (event, x, y, cancel).
            int at = releaseDirect ? 0 : 1;
            register(nativeRelease, chain -> {
                reporter.release.incrementAndGet();
                Object owner = releaseDirect ? chain.getThisObject() : chain.getArg(0);
                MotionEvent event = (MotionEvent) chain.getArg(at);
                try {
                    if (owner == panel && suppressChildCancel(event)) {
                        trace("child-cancel:skip-native-release"); return null;
                    }
                    if (owner == panel && event.getActionMasked() == MotionEvent.ACTION_CANCEL
                            && dispatch.matches(MotionEvent.ACTION_CANCEL, event.getDownTime()) && engaged) {
                        dismissal.reset(); recover(startProgress > .5f); return null;
                    }
                    if (owner == panel && event.getActionMasked() == MotionEvent.ACTION_UP
                            && !(boolean) chain.getArg(at + 3) && session.touching() && input.matches(event.getDownTime())
                            && dispatch.matches(MotionEvent.ACTION_UP, event.getDownTime())) {
                        observeDirection(event);
                        // A fast DOWN -> UP can reach native release without a MOVE and without
                        // native tracking. Use its actual slop acceptance, rather than losing ownership.
                        if (!engaged && eligible() && LockscreenRelease.upwardReleasePastSlop(
                                (float) chain.getArg(at + 1) - NativeReflection.number(panel, "mInitialExpandX"),
                                (float) chain.getArg(at + 2) - NativeReflection.number(panel, "mInitialExpandY"),
                                NativeReflection.number(panel, "mTouchSlop"))) {
                            engaged = true; takeWakeVisuals(); stopRenderTick(); startRenderTick(); trace("release:owned-without-move"); captureLayers();                        }
                        if (engaged && eligible() && coordinateRelease((float) chain.getArg(at + 1), (float) chain.getArg(at + 2)))
                            return null;
                    }
                    if (lockGesture && owner == panel) {
                        reporter.nativeRelease.incrementAndGet();
                        trace("release:native action=" + event.getActionMasked() + " dt=" + event.getDownTime()
                                + " origin=" + dispatch.origin(event.getActionMasked(), event.getDownTime()) + " owned=" + engaged
                                + " tracking=" + NativeReflection.call(panel, "isTracking") + " scene=" + reporter.scene);
                    }
                } catch (Throwable t) { fail(t); }
                return chain.proceed();
            });
            features.deopt("统一释放", nativeRelease);
            // Observe the competing native unlock animation without changing it. Its caller and
            // release ownership show whether a gesture really escaped the coordinator.
            Method unlockAnimation = null;
            for (Method method : keyguardClass.getDeclaredMethods())
                if (method.getName().equals("startUnlockKeyguardDismissAnim") && method.getParameterCount() == 1)
                    unlockAnimation = unlockAnimation == null ? method : null;
            if (unlockAnimation != null) {
                register(unlockAnimation, chain -> {
                    if (chain.getThisObject() == keyguard && lockGesture)
                        trace("native-unlock touching=" + session.touching() + " owned=" + engaged + " spring=" + running() + " caller=" + nativeCaller());
                    return chain.proceed();
                });
                features.deopt("统一释放", unlockAnimation);
            }
            // ART may inline these direct callers and bypass an installed hook body.
            // Deoptimize the dispatchers, the vertical processor and its native height caller.
            for (Method caller : new Method[]{windowTouch, listenerTouch, shadeTouch, externalTouch, intercept, touch, drag, fling,
                    releaseFling,
                    injectorClass == null ? null : Members.suffixed(injectorClass, "setExpandedHeightInternal", float.class),
                    NativeReflection.methodOrNull(keyguardClass, "updateKeyguardElementsExpansion", boolean.class)})
                features.deopt("面板拖动", caller);

            register(fling, chain -> {
                if (lockGesture && chain.getThisObject() == panel)
                    trace("height-fling target=" + chain.getArg(2) + " start=" + NativeReflection.number(panel, "mExpandedHeight")
                            + " engaged=" + engaged + " permit=" + releaseScope.get() + " scene=" + reporter.scene);
                Long released = releaseScope.get();
                if (released == null || released != session.generation() || !engaged || !eligible()
                        || chain.getThisObject() != panel) return chain.proceed();
                reporter.fling.incrementAndGet();
                Fling f = new Fling(rawHeight, (float) chain.getArg(2),
                        (float) chain.getArg(0), (boolean) chain.getArg(1), session.config(), session.generation());
                flingScope.set(f);
                try { return chain.proceed(); } finally { flingScope.remove(); }
            });
            register(nativeFlingEnd, chain -> {
                if (!completingFling && !(boolean) chain.getArg(0) && chain.getThisObject() == panel
                        && engaged && session.active() && ownedGeneration == session.generation()) {
                    deferredFlingEnd = session.generation(); trace("native-fling-end:await-presented-frame"); return null;
                }
                return chain.proceed();
            });
            features.deopt("统一释放", nativeFlingEnd);
            // The panel's height animator listeners call onFlingEnd directly. Their anonymous class
            // numbers ($6/$8 on the inspected build) shift, so deoptimize every such listener.
            for (int i = 1; i <= 40; i++) {
                Class<?> listener = Members.load(loader, PANEL + "$" + i);
                if (listener != null && Animator.AnimatorListener.class.isAssignableFrom(listener))
                    features.deopt("统一释放", NativeReflection.methodOrNull(listener, "onAnimationEnd", Animator.class));
            }
            register(NativeReflection.method(ValueAnimator.class, "start"), chain -> {
                Fling f = flingScope.get();
                ValueAnimator animator = (ValueAnimator) chain.getThisObject();
                if (f != null && animator == NativeReflection.get(panel, "mHeightAnimator"))
                    trace("animator:start native-ms=" + animator.getDuration() + " scale-enabled=" + ValueAnimator.areAnimatorsEnabled());
                if (f != null && f.generation == session.generation() && session.active() && engaged
                        && animator == NativeReflection.get(panel, "mHeightAnimator") && !failed) {
                    long originalDuration = animator.getDuration();
                    var originalInterpolator = animator.getInterpolator();
                    try {
                        float delta = f.end - f.start;
                        if (Math.abs(delta) > 1) {
                            SpringCurve curve = new SpringCurve(f.config.stiffness, f.expand ? f.config.expandDamping : f.config.closeDamping,
                                    f.velocity / delta, delta);
                            animator.setFloatValues(f.start, f.end);
                            animator.setDuration(curve.durationMillis);
                            animator.setInterpolator(fraction -> {
                                rawHeight = f.start + delta * curve.interpolate(fraction);
                                frameVelocity = fraction >= 1 ? 0 : delta * (float) curve.velocityAtSeconds(fraction * curve.durationMillis / 1000.0);
                                float full = ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue();
                                return (NativePanelHeight.sample(rawHeight, full, !f.expand, fraction >= 1) - f.start) / delta;
                            });
                             ownedAnimator = animator; ownedGeneration = f.generation;
                            reporter.spring.incrementAndGet();
                            trace("spring:start delta=" + delta + " ms=" + curve.durationMillis);
                            animator.addListener(new AnimatorListenerAdapter() {
                                 private boolean cancelled;
                                 @Override public void onAnimationCancel(Animator animation) {
                                     cancelled = true; dismissal.cancel(f.generation);
                                     trace(f.generation, "spring:cancel current=" + session.generation());
                                 }
                                @Override public void onAnimationEnd(Animator animation) {
                                     if (ownedAnimator == animator && ownedGeneration == f.generation) ownedAnimator = null;
                                     trace(f.generation, "spring:end current=" + session.generation() + " finger=" + session.touching()
                                             + " cancelled=" + cancelled);
                                    queueFinish(f.generation);
                                }
                            });
                            reporter.motion.incrementAndGet(); reporter.publish();
                        }
                    } catch (Throwable t) {
                        animator.setDuration(originalDuration); animator.setInterpolator(originalInterpolator);
                        if (ownedAnimator == animator) ownedAnimator = null;
                        fail(t);
                    }
                }
                return chain.proceed();
            });

            ready = true;
            main.post(warmWatch);
        } catch (Throwable t) {
            failed = true;
            for (var handle : installed) try { handle.unhook(); } catch (Throwable ignored) {}
            if (prefs != null && prefsListener != null) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
            reporter.compat.off("面板拖动", com.zhuanz.flymemotion.core.FeatureGate.reason(t));
            throw t;
        }
    }
    private static <T> T required(T member, String what) {
        if (member == null) throw new IllegalStateException(what + " missing");
        return member;
    }
    /**
     * The same member, but one that has a body. An abstract declaration has nothing to hook and is
     * not the method an instance runs, so a build that declares the AOSP name abstract on the
     * panel and implements it on the subclass that is actually instantiated is asked there instead.
     */
    private static Method withBody(Method resolved, Class<?> keyguardClass, Members.Sig... candidates) {
        if (resolved != null && !Modifier.isAbstract(resolved.getModifiers())) return resolved;
        Method concrete = Members.concrete(keyguardClass, candidates);
        return concrete != null ? concrete : resolved;
    }
    /** The name this build uses for a keyguard member, out of the shapes it has had. */
    private static String keyguardField(Class<?> type, String... names) {        String found = keyguardFieldOrNull(type, names);
        if (found == null) throw new IllegalStateException(type.getName() + "#" + names[0] + " missing");
        return found;
    }
    private static String keyguardFieldOrNull(Class<?> type, String... names) {
        for (String name : names) if (NativeReflection.fieldOrNull(type, name) != null) return name;
        return null;
    }
    /**
     * The window the Shade lives in. Builds that keep it as a field on the keyguard host answer
     * from there; where that field does not exist the panel view's own root is used, which is the
     * same window. Null only when neither is available, which callers already read as "not ready".
     */
    private View shadeWindowView() {
        if (kgShadeWindowView != null && keyguard != null) {
            Object held = NativeReflection.optGet(keyguard, kgShadeWindowView);
            if (held instanceof View) return (View) held;
        }
        Object panelView = panel == null ? null : NativeReflection.optGet(panel, "mView");
        if (panelView instanceof View) {
            View root = ((View) panelView).getRootView();
            if (root != null) return root;
        }
        return keyguard instanceof View ? (View) keyguard : null;
    }
    private Object keyguardInjector() {
        return kgInjector == null || keyguard == null ? null : NativeReflection.optGet(keyguard, kgInjector);
    }
    /** A static method whose name contains {@code part}, with exactly these parameters (javac/D8 accessor names). */
    private static Method staticContaining(Class<?> owner, String part, Class<?>... params) {
        Method found = null;
        for (Method m : owner.getDeclaredMethods())
            if (m.getName().contains(part) && Modifier.isStatic(m.getModifiers()) && java.util.Arrays.equals(m.getParameterTypes(), params)) {
                if (found != null) return null;
                found = m;
            }
        if (found != null) found.setAccessible(true);
        return found;
    }
    private void nativeInitDown(Method method, MotionEvent event) {
        if (initDownDirect) NativeReflection.invoke(method, panel, event);
        else NativeReflection.invoke(method, null, panel, event);
    }
    private boolean nativeFalseTouch(int interaction, float x, float y) {
        return (boolean) (falseTouchAosp ? NativeReflection.invoke(releaseFalseTouch, panel, x, y, interaction)
                : NativeReflection.invoke(releaseFalseTouch, panel, interaction, x, y));
    }
    private void nativeFling(float vy, boolean expand, boolean falsing) {
        if (flingAosp) NativeReflection.invoke(releaseFling, panel, vy, expand, 1f, falsing);
        else NativeReflection.invoke(releaseFling, panel, vy, 1f, expand, falsing);
    }
    private void nativeShowBouncer() {
        Object manager = NativeReflection.get(panel, "mStatusBarKeyguardViewManager");
        NativeReflection.invoke(showBouncer, manager,
                showBouncer.getParameterTypes()[0] == String.class ? "FlymeMotion accepted swipe" : (Object) Boolean.TRUE);
    }

    boolean ownsWindow() { return warming || engaged || holdForNativeExit || session.active(); }
    private void startRenderTick() {
        if (!renderScheduled) { renderScheduled = true; android.view.Choreographer.getInstance().postFrameCallback(renderTick); }
    }
    private void stopRenderTick() { android.view.Choreographer.getInstance().removeFrameCallback(renderTick); renderScheduled = false; }
    private Context gestureContext() { return (shadeWindowView()).getContext(); }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private void register(java.lang.reflect.Executable method, XposedInterface.Hooker callback) {
        installed.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(callback));
        reporter.hooks = installed.size();
    }
    // Native end listeners can call visual updates after isStarted became false, before ours runs.
    private boolean running() { return session.active() && ownedAnimator != null && ownedGeneration == session.generation(); }
    private boolean ownsVisuals() {
        boolean own = !failed && ready && targetVersion && (holdForNativeExit
                ? !NativeReflection.bool(panel, "mDozing") && NativeReflection.bool(keyguard, kgInteractive)
                    && !sleeping && !NativeReflection.bool(keyguard, kgKeyguardOccluded)
                    && !NativeReflection.bool(keyguard, kgKeyguardBouncerShowing)
                : engaged && session.active() && eligible());
        // Release BEFORE a native wake/AOD/occlusion transform proceeds, so rollback cannot
        // overwrite the newer native transform after it has already been applied.
        if (!own && (engaged || holdForNativeExit)) abandonPose();
        return own;
    }
    private void capturePanel(Object owner) throws Exception {
        if (owner == null || owner == panel) return;
        panel = owner; reporter.panelFound = true;
        Context c = ((View) NativeReflection.get(panel, "mView")).getContext();
        SceneReceivers.revoke(c, main, reporter, () -> {
            if (ownedAnimator != null) ownedAnimator.cancel();
            abandonPose(); reporter.sceneSession = -1; reporter.publish();
            desktopAnimation.cancel();
        });
        // No SystemUI versionCode gate: missing classes/fields still fail install() and keep native.
        targetVersion = true;
    }
    private void captureTouchOwner(Object handler) throws Exception {
        if (!ready || failed) return;
        Object owner = NativeReflection.get(handler, "this$0");
        capturePanel(owner);
        // Resolve the controller from the LIVE injector after construction has completed.
        // Never request its Lazy value from the panel constructor (a DI dependency cycle).
        Object injector = NativeReflection.optGet(owner, "mKeyguardPanelViewInjector");
        Object live = NativeReflection.optCall(injector, "getKeyguardPanelViewController");
        // Without MIUI's injector the controller captured at construction stays in use.
        if (live != null) keyguard = live;
        reporter.keyguardFound = keyguard != null;
    }
    private void engageNativeDrag() {
        if (!session.touching() || engaged) return;
        if (!eligible()) return;
        if (!upwardDrag) { reporter.scene = "未达到上滑方向与触摸距离"; return; }
        if (!(boolean) NativeReflection.call(panel, "isTracking")) { reporter.scene = "等待原生接受垂直拖动"; return; }
        engaged = true; reporter.scene = "已接入原生垂直拖动";
        takeWakeVisuals();
        stopRenderTick(); startRenderTick();
        trace("drag:owned");
        captureLayers();
    }
    /**
     * Why a finger that is on the screen has not taken over the drag yet. The four gates above
     * decide whether the follow-the-finger path runs at all, and a gesture that "does nothing"
     * looks identical in the receipt whether it was never owned or owned and then reverted.
     */
    private void noteDragGate(String stage) {
        String gate = !session.touching() ? "not-touching" : engaged ? "owned"
                : !eligible() ? "not-eligible" : !upwardDrag ? "not-upward"
                : !(boolean) NativeReflection.call(panel, "isTracking") ? "native-not-tracking" : "unknown";
        if (gate.equals(lastDragGate)) return;
        lastDragGate = gate;
        if (dragJournal.size() >= 6) dragJournal.removeFirst();
        dragJournal.addLast((SystemClock.uptimeMillis() % 100000) + " " + stage + "=" + gate);
        // Self-contained, never appended to: a receipt that grows per frame is worse than no receipt.
        reporter.diag = "drag=" + dragJournal + " now=" + gate
                + " touching=" + session.touching() + " engaged=" + engaged
                + " eligible=" + eligible() + " upward=" + upwardDrag
                + " scene=" + reporter.scene + " wallpaperBound=" + reporter.wallpaperBound;
    }
    private String lastDragGate;
    /**
     * Why a DOWN never became a gesture. Kept separate from {@code blocked} because these gates run
     * before {@code eligible()} and the scene field is still at its reporter default; a gesture that
     * silently does nothing then leaves no trace anywhere else in the receipt.
     */
    private void downGate(String reason) {
        if (reason.equals(lastDownGate)) return;
        lastDownGate = reason;
        if (downJournal.size() >= 4) downJournal.removeFirst();
        downJournal.addLast((SystemClock.uptimeMillis() % 100000) + " " + reason);
        // Also into the append-only gesture journal. reporter.diag is a single line that the
        // self-contained "drag=" write in noteDragGate replaces wholesale, so a refused DOWN recorded
        // only there disappeared as soon as the next swipe succeeded - exactly the intermittent
        // failure this diagnostic exists to capture. The journal accumulates instead, and the field
        // has its own larger receipt budget.
        trace("down-blocked:" + reason);
        reporter.diag = "down=" + downJournal + " touched=" + session.touching()
                + " engaged=" + engaged + " | " + reporter.diag;
        if (reporter.diag.length() > 1900) reporter.diag = reporter.diag.substring(0, 1900);
    }
    private String lastDownGate;
    private final java.util.ArrayDeque<String> downJournal = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> dragJournal = new java.util.ArrayDeque<>();
    private void beginGesture(MotionEvent event) {
        // These four gates return before eligible(), so nothing else in the receipt records them:
        // blocked stays "" and scene keeps its RuntimeReporter default. A gesture that "sometimes
        // does not fire at all" then looks identical to one that never had a touch, which is
        // exactly the report this traces. Record which gate refused and why.
        if (!dispatch.matches(MotionEvent.ACTION_DOWN, event.getDownTime())) {
            // "not-a-down" on its own was a dead end: matches() also requires the tracked frame to be
            // the outermost one and to carry the same downTime, and the sub-case decides the fix.
            // origin() names it - outside-window (the hook never saw this DOWN), nested-window,
            // stale-stream, or child-cancel.
            downGate("not-a-down:" + dispatch.origin(MotionEvent.ACTION_DOWN, event.getDownTime())); return;
        }
        if (panel == null || keyguard == null) { downGate("no-controller"); return; }
        if (NativeReflection.integer(panel, "mBarState") != 1) {
            downGate("bar-state=" + NativeReflection.integer(panel, "mBarState")); return;
        }
        if (holdForNativeExit || nativeAuthenticationStarted || nativeDismissalRequested) {
            // A committed unlock is sticky. nativeDismissalRequested means the release decision was
            // "unlock" and the native exit is in flight; a DOWN in that window used to cancel the
            // exit (the line below) and then bounce the sheet back to the lock screen on a mere tap
            // before the desktop had appeared. Ignore it instead: recover()/abandon reset the flag
            // if the exit genuinely fails (the 5s scene deadline), so this cannot wedge the gesture.
            downGate("handed-over hold=" + holdForNativeExit + " auth=" + nativeAuthenticationStarted
                    + " dismiss=" + nativeDismissalRequested); return;
        }
        if(nativeDismissalRequested){liveScene.cancelNativeExit();nativeDismissalRequested=false;}
        if (!input.begin(event.getDownTime(), event.getRawX(), event.getRawY())) { downGate("duplicate-down"); return; }        boolean retouch = engaged && session.active() && (running() || waitingForScene || pendingFinish == session.generation()) && eligible();
        boolean prepared = warming && liveScene.nativeReady() && reporter.wallpaperBound;
        completeNativeFling(session.generation(), true);
        float carried = Float.isFinite(renderedRawHeight) && retouch ? renderedRawHeight : rawHeight;
        session.beginInput(); dismissal.reset(); pendingFinish = finishCommit = presentedFinish = -1; warming = false;
        settleMark = -1; settleMarkAt = 0;
        desktopAnimation.begin(session.generation(),session.config());
        liveScene.lateDesktopAnimation(session.config().enabled&&session.config().lateDesktopAnimation&&!desktopAnimation.nativeBound());
        engaged = retouch; lockGesture = true; upwardDrag = false;
        wakeVisualsTaken = retouch;
        pendingAuthentication = waitingForScene = false;
        wallpaperFailed = !wallpaperMotion.available(); reporter.wallpaperBound = false;
        if (ownedAnimator != null) ownedAnimator.cancel();
        float full = ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue();
        startProgress = progress(); rawHeight = retouch ? carried : full * startProgress;
        retouchOffset = retouch ? carried - NativeReflection.number(panel, "mExpandedHeight") : 0;
        if (!retouch) reveal.reset(full, SystemClock.uptimeMillis(), prepared);
        stopRenderTick(); startRenderTick();
        // Whether the scene was already usable before this finger arrived decides everything a fast
        // swipe can show: RevealGate.sample() forces a full opaque cover until sceneReady, so a quick
        // drag that outruns the mirror and the readiness receipt reveals nothing. Break the predicate
        // into its parts here, because "prepared=false" alone does not say which one was missing.
        trace("down retouch=" + retouch + " prepared=" + prepared + " warm=" + warming
                + " bound=" + reporter.wallpaperBound + " nativeReady=" + liveScene.nativeReady()
                + " everPresented=" + liveScene.everPresented() + " frameworkSilent=" + liveScene.frameworkSilent());
        verticalDownTime = -2;
        gestureMin = 1; gestureMax = 0; gestureApplied = false; blockedReasons.clear();
        wallpaperObservations = 0;
        gestureReason = retouch ? "从回弹中重新接手" : "等待原生拖动";
        reporter.blocked = "";
        gestureLayers = resolveNativeLayers();
        ensureFrameDriver(gestureLayers[0]);
        liveScene.begin(gestureLayers[0].getContext(),
                NativeReflection.bool(NativeReflection.get(panel, "mKeyguardStateController"), "mCanDismissLockScreen"));
        // Prepare HOME on DOWN, under the already-opaque cover, rather than after MOVE/slop.
        if (canDismiss()) liveScene.reveal();
        captureLayers();
    }
    private void observeDirection(MotionEvent event) {
        if (!session.touching() || !input.matches(event.getDownTime())
                || !dispatch.matches(event.getActionMasked(), event.getDownTime())) return;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE && event.getActionMasked() != MotionEvent.ACTION_UP) return;
        float slop = ViewConfiguration.get(((View) NativeReflection.get(panel, "mView")).getContext()).getScaledTouchSlop();
        upwardDrag = input.upward(event.getRawX(), event.getRawY(), slop);
        engageNativeDrag();
    }
    private View[] resolveNativeLayers() {
        View root = shadeWindowView();
        // ViewRootImpl dispatch does not apply the root RenderNode's translation to input.
        // A child/ancestor inside another ViewGroup would have different input semantics.
        if (root.getParent() instanceof View) throw new IllegalStateException("Shade window is not the ViewRoot root");
        return new View[]{root};
    }
    private void captureLayers() {
        reporter.layers = "canDismiss=" + NativeReflection.bool(NativeReflection.get(panel, "mKeyguardStateController"), "mCanDismissLockScreen")
                + " wake=" + NativeReflection.optGet(keyguard, "wakeupAnimState") + "; "
                + NativeSceneDiagnostics.layers(gestureLayers);
    }
    private void summarizeGesture(boolean cancelled) {
        if (!lockGesture) return;
        reporter.gesture = (cancelled ? "取消" : "释放") + "：" + (gestureApplied ? "已应用位移" : "未应用位移")
                + (gestureMax >= gestureMin ? "，进度 " + Math.round(gestureMin * 100) + "～" + Math.round(gestureMax * 100) + "%" : "")
                + "；" + gestureReason;
        reporter.publish();
    }
    private boolean blocked(String reason) {
        reporter.scene = reason;
        if (session.touching() && lockGesture && blockedReasons.size() < 8) {
            blockedReasons.add(reason); reporter.blocked = String.join(" / ", blockedReasons);
        }
        return false;
    }
    /** Lockscreen states in which the bottom region reserves a finger (motion ownership aside). */
    private boolean regionCovers() {
        try {
            if (!ready || failed || panel == null || keyguard == null || !session.config().enabled) return false;
            if (NativeReflection.integer(panel, "mBarState") != 1 || NativeReflection.bool(panel, "mDozing")) return false;
            if (!NativeReflection.bool(keyguard, kgInteractive) || !NativeReflection.bool(keyguard, kgKeyguardShowing)) return false;
            if (NativeReflection.bool(keyguard, kgKeyguardBouncerShowing) || NativeReflection.bool(keyguard, kgKeyguardOccluded)) return false;
            if (!editorIdle()) return false;
            if (expanded(NativeReflection.optGet(panel, "mQsController"))) return false;
            if (expanded(NativeReflection.optGet(keyguard, "controlCenter"))) return false;
            // A side page (camera/magazine) that is already open keeps its own touches.
            Object helper = moveHelper();
            return helper == null || NativeReflection.optCallBool(helper, "isInCenterScreen", true)
                    && !NativeReflection.optCallBool(helper, "isInMagazineAnimation", false);
        } catch (Throwable t) { fail(t); return false; }
    }
    private boolean eligible() {
        try { return eligibleChecked(); }
        catch (Throwable t) { fail(t); return false; }
    }
    private boolean eligibleChecked() {
        if (!ready || failed) return blocked("适配器未就绪或已回退");
        if (panel == null || keyguard == null) return blocked("未找到真实锁屏控制器");
        if (!targetVersion) return blocked("SystemUI 版本不匹配");
        if (sleeping) return blocked("原生熄屏交接中");
        if (!session.config().enabled) return blocked("配置已停用");
        if (liveScene != null && liveScene.incompatibleFramework()) return blocked("框架适配器未更新，需要重启手机");
        if(nativeDismissalRequested && !NativeReflection.bool(panel,"mDozing")
                && NativeReflection.bool(keyguard,kgInteractive)
                && !NativeReflection.bool(keyguard,kgKeyguardBouncerShowing)
                && !NativeReflection.bool(keyguard,kgKeyguardOccluded))return true;
        if (NativeReflection.integer(panel, "mBarState") != 1) return blocked("当前不是锁屏");
        if (NativeReflection.bool(panel, "mDozing")) return blocked("AOD 状态");
        if (!NativeReflection.bool(keyguard, kgInteractive)) return blocked("锁屏不可交互");
        if (!NativeReflection.bool(keyguard, kgKeyguardShowing)) return blocked("锁屏未显示");
        if (NativeReflection.bool(keyguard, kgKeyguardBouncerShowing)) return blocked("原生认证界面");
        if (NativeReflection.bool(keyguard, kgKeyguardOccluded)) return blocked("锁屏被其他界面遮挡");
        // Native wake fades are visual producers, not vertical-input exclusion states. Rejecting
        // them here lost the first DOWN and handed fast UP back to the original unlock path.
        if (!editorIdle()) return blocked("锁屏编辑状态：" + NativeReflection.optGet(keyguard, "editorState"));
        if (expanded(NativeReflection.optGet(panel, "mQsController"))) return blocked("QS 已展开");
        if (expanded(NativeReflection.optGet(keyguard, "controlCenter"))) return blocked("控制中心已展开");
        Object helper = moveHelper();
        boolean allowed = helper == null || (NativeReflection.optCallBool(helper, "isInCenterScreen", true)
                && !NativeReflection.optBool(helper, "mSwipingInProgress", false) && !NativeReflection.optBool(helper, "mIsShortcutMoving", false)
                && !NativeReflection.optCallBool(helper, "isInMagazineAnimation", false));
        if (!allowed && bottomDownTime != input.downTime()) return blocked("横向快捷页或杂志动画");
        reporter.scene = "锁屏可接入"; return true;
    }
    /** MIUI's lockscreen editor state (enum IDEL, sic); a build without an editor is always idle. */
    private boolean editorIdle() {
        if (!NativeReflection.has(keyguard, "editorState")) return true;
        Object editor = NativeReflection.optGet(keyguard, "editorState");
        return editor != null && (editor.toString().equals("IDEL") || editor.toString().equals("IDLE"));
    }
    private static boolean expanded(Object controller) {
        return NativeReflection.optCallBool(controller, "getExpanded", false);
    }
    private Object moveHelper() {
        return NativeReflection.optGet(keyguardInjector(), "keyguardMoveHelper");
    }
    private void takeWakeVisuals() {
        if (wakeVisualsTaken) return;
        wakeVisualsTaken = true;
        // Same native Folme cancellation as interactive swiping. Finish an existing alpha
        // producer through its own final update/listeners; cancellation alone leaves alpha < 1.
        if (NativeReflection.optBool(keyguard, "hideKeyguardViewFolmeRunning", false)) {
            Class<?> folme = Members.load(loader, "miuix.animation.Folme");
            Method useValue = folme == null ? null : NativeReflection.methodOrNull(folme, "useValue", Object[].class);
            if (useValue != null) NativeReflection.optCall(NativeReflection.invoke(useValue,
                    null, (Object) new Object[]{NativeReflection.optGet(keyguard, "translationAlphaValue")}), "cancel");
        }
        Object alpha = NativeReflection.optGet(keyguard, "viewAlphaAnimator");
        if (alpha instanceof ValueAnimator animator && animator.isRunning()) animator.end();
        NativeReflection.optCall(keyguard, "cancelWakeupAnimations");
        trace("wake:vertical-takeover");
    }
    private float progress() {
        float full = ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue();
        return FlymeMotion.progress(NativeReflection.number(panel, "mExpandedHeight"), full);
    }
    private boolean suppressChildCancel(MotionEvent event) {
        return dispatch.suppressChildCancel(event.getActionMasked(), event.getDownTime(), input.downTime(),
                session.touching(), ready && !failed && lockGesture)
                && NativeReflection.bool(panel, "mInitialTouchFromKeyguard") && eligible();
    }
    private boolean coordinateRelease(float x, float y) {
        Object state = NativeReflection.get(panel, "mKeyguardStateController");
        if (!NativeReflection.bool(panel, "mInitialTouchFromKeyguard") || !NativeReflection.bool(state, "mShowing")
                || NativeReflection.bool(state, "mKeyguardGoingAway") || NativeReflection.bool(state, "mKeyguardFadingAway")
                || NativeReflection.optBool(state, "mMiKeyguardFadingAway", false) || NativeReflection.optBool(panel, "mExpandingFromHeadsUp", false)) {
            reporter.releaseReason = "释放时原生认证/退场或非锁屏手势；保留原生";
            trace("release:native-auth-or-origin"); return false;
        }
        Object falsing = NativeReflection.optGet(panel, "mFalsingManager");
        if (NativeReflection.optCallBool(falsing, "isUnlockingDisabled", false)) {
            reporter.releaseReason = "原生禁止手势解锁；保留原生";
            trace("release:unlock-disabled"); return false;
        }
        VelocityTracker velocity = (VelocityTracker) NativeReflection.optGet(panel, "mVelocityTracker");
        // MIUI's panel subclass redeclares this field, and the tracker the driver above feeds is
        // the one on the class that declares it, so that one is read when the shadowing is empty.
        if (velocity == null) velocity = (VelocityTracker) NativeReflection.getOn(panel, panelType, "mVelocityTracker");
        if (velocity == null) throw new IllegalStateException("Native release VelocityTracker missing");
        velocity.computeCurrentVelocity(1000);
        float vy = velocity.getYVelocity(), vx = velocity.getXVelocity(), p = progress();
        View view = (View) NativeReflection.get(panel, "mView");
        LockscreenRelease.Decision decision = LockscreenRelease.decide(new LockscreenRelease.Input(p, vy, vx,
                view.getResources().getDisplayMetrics().density, y - NativeReflection.number(panel, "mInitialExpandY"),
                NativeReflection.bool(state, "mCanDismissLockScreen")), session.config(),
                interaction -> nativeFalseTouch(interaction, x, y));
        Object ambient = NativeReflection.optGet(panel, "mAmbientState");
        // Prepare the native bouncer position before committing this release. No auth flags are changed.
        Object position = null;
        if (!decision.expand() && bouncerPosition) {
            Object manager = NativeReflection.get(panel, "mStatusBarKeyguardViewManager");
            Object interactor = NativeReflection.optGet(manager, "mPrimaryBouncerInteractor");
            Object repository = NativeReflection.optGet(interactor, "repository");
            position = NativeReflection.optGet(repository, "_keyguardPosition");
            if (position != null && NativeReflection.methodOrNull(position.getClass(), "setValue", Object.class) == null) position = null;
        }
        if (!eligible()) return false;
        reporter.coordinatedRelease.incrementAndGet();
        pendingAuthentication = !decision.expand() && !NativeReflection.bool(state, "mCanDismissLockScreen");
        boolean springExpand = decision.expand() || pendingAuthentication;
        dismissal.commit(session.generation(), decision.expand());
        reporter.releaseReason = "统一释放：" + (decision.expand() ? "回弹" : "收起")
                + "，进度 " + Math.round(p * 100) + "%，vy=" + Math.round(vy)
                + "，原生误触=" + decision.falseTouch();
        trace("release:coordinated p=" + p + " vy=" + vy + " expand=" + decision.expand()
                + " falsing=" + decision.falseTouch() + " types=" + decision.decisionType() + "/" + decision.flingType());
        // Once committed, never execute the OEM endMotionEvent as well. Its immediate-dismiss
        // branch is precisely the second animation owner removed by this coordinator.
        try {
            // The same bookkeeping native endMotionEvent does; each field exists on some builds only.
            if (NativeReflection.has(panel, "mTrackingPointer")) NativeReflection.set(panel, "mTrackingPointer", -1);
            if (NativeReflection.has(panel, "mStatusBarLongPressDowntime")) NativeReflection.set(panel, "mStatusBarLongPressDowntime", -1L);
            if (NativeReflection.has(ambient, "mIsSwipingUp")) {
                if (NativeReflection.bool(ambient, "mIsSwipingUp") && NativeReflection.has(ambient, "mIsFlingRequiredAfterLockScreenSwipeUp"))
                    NativeReflection.set(ambient, "mIsFlingRequiredAfterLockScreenSwipeUp", true);
                NativeReflection.set(ambient, "mIsSwipingUp", false);
            }
            if (position != null && NativeReflection.optGet(panel, "mDownX") instanceof Number downX)
                NativeReflection.invoke(NativeReflection.method(position.getClass(), "setValue", Object.class),
                        position, downX.floatValue());
            releaseScope.set(session.generation());
            try { nativeFling(vy, springExpand, decision.falseTouch()); }
            finally { releaseScope.remove(); }
        } catch (Throwable t) { fail(t); }
        finally {
            try { NativeReflection.invoke(releaseStop, panel, springExpand); }
            catch (Throwable t) { fail(t); }
            try { velocity.clear(); } catch (Throwable t) { fail(t); }
            reporter.publish();
        }
        return true;
    }
    private void trace(String event) {
        trace(session.generation(), event);
    }
    private void trace(long token, String event) {
        TouchDispatch.Frame frame = dispatch.current();
        journal.add(token, "t=" + SystemClock.uptimeMillis() + " " + event
                + " w=" + (frame == null ? "-" : frame.action() + "/" + frame.downTime())
                + " input=" + input.downTime());
        reporter.gestureJournal = journal.snapshot();
    }
    private String nativeCaller() {
        var names = new ArrayList<String>();
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            if ((frame.getClassName().startsWith("com.android.systemui") || frame.getClassName().startsWith("com.android.keyguard"))
                    && !frame.getMethodName().equals("startUnlockKeyguardDismissAnim")) {
                names.add(frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName());
                if (names.size() == 3) break;
            }
        }
        return String.join("/", names);
    }
    private void ensureFrameDriver(View window) {
        ViewTreeObserver next = window.getViewTreeObserver();
        if (frameWindow == window && frameObserver == next && next.isAlive()) return;
        if (frameObserver != null && frameObserver.isAlive()) frameObserver.removeOnPreDrawListener(frameDriver);
        frameWindow = window; frameObserver = next; next.addOnPreDrawListener(frameDriver);
    }
    private void requestDraw() { if (frameWindow != null) frameWindow.invalidate(); }
    private void queueFinish(long generation) {
        if (generation != session.generation() || session.touching()) return;
        reporter.releaseReason = "TM:finish-queued p=" + Math.round(progress() * 1000) / 1000f
                + " engaged=" + engaged + " wallet=" + reporter.wallpaperBound;
        pendingFinish = generation; requestDraw(); startRenderTick();
    }
    private View[] wallpaperRoles() {
        Object depth = NativeReflection.optGet(keyguard, "keyguardDepthInteractor");
        return new View[]{(View) NativeReflection.get(keyguard, kgForegroundLayer),
                (View) NativeReflection.optGet(keyguard, "keyguardBackgroundLayer"),
                (View) NativeReflection.optGet(depth, "deductedImageView")};
    }
    private boolean canDismiss() {
        return NativeReflection.bool(NativeReflection.get(panel, "mKeyguardStateController"), "mCanDismissLockScreen");
    }
    /**
     * Whether the framework's "it cannot present a return window" answer ends this gesture by
     * handing the unlock to native.
     *
     * <p>There is deliberately no switch here. A previous attempt made this configurable and made
     * {@code holdForNativeExit} depend on it, which removed the escape hatch the author put in and
     * left the module holding the scene while native waited for the module: every gesture then paid
     * the full five second deadline before rebounding, and the reveal that the handover was
     * covering had already happened and was lost. The working behaviour is this one, so it is the
     * only behaviour.
     */
    private boolean frameworkHandsOver() {
        return liveScene.frameworkSilent();
    }
    private void prepareScene() {
        View window = shadeWindowView();
        if (!window.isAttachedToWindow() || window.getHeight() <= 0 || session.active() || holdForNativeExit) return;
        gestureLayers = resolveNativeLayers(); ensureFrameDriver(window);
        // The nudge budget is per warm period, not per process. Resetting only on a successful bind
        // made the 30 nudges a lifetime allowance: one idle period that spent them without the mirror
        // committing left every later period with a budget of zero, so the wait-for-mirror remedy
        // silently stopped working exactly in the case it was added for.
        warmUnbound = 0;
        warming = true; wallpaperFailed = !wallpaperMotion.available();
        liveScene.lateDesktopAnimation(session.config().enabled&&session.config().lateDesktopAnimation&&!desktopAnimation.nativeBound());
        liveScene.begin(window.getContext(), canDismiss()); requestDraw();
    }
    private void applyWarmPose() {
        View window = gestureLayers[0];
        // Nothing to warm once the wallpaper path has handed back to native: begin() would return
        // false on every tick and the nudge budget below would be spent waiting for a mirror that
        // is not going to be composed. prepareScene() clears this when the warm loop restarts.
        if (wallpaperFailed) { reporter.wallpaperBound = false; return; }
        float full = ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue();
        liveScene.begin(window.getContext(), canDismiss());
        boolean bound = wallpaperMotion.begin(window, liveScene, session.config().cornerRadiusDp); reporter.wallpaperBound = bound;
        if (!bound) {
            // Keep asking for frames until the mirror appears, throttled instead of counted.
            //
            // The previous version spent a budget of 30 nudges and then stopped. The warm period does
            // not end while the mirror is missing, so `warming` stayed true, `prepareScene` never ran
            // again, and the reset added there never applied: after ~0.5 s nothing requested another
            // frame at all. This device's receipt shows exactly that dead end - "warm=unbound
            // waiting-mirror 等待框架创建锁屏引擎的实时镜像" repeated, followed by a real finger's
            // "down ... warm=false bound=false nativeReady=false", i.e. the swipe arrived with no
            // mirror bound and therefore showed no see-through whatsoever.
            //
            // A rate limit is what the budget was reaching for: one nudge per WARM_NUDGE_MS cannot
            // become an invalidate -> preDraw -> invalidate loop, while still noticing a mirror that
            // commits seconds later.
            long now = SystemClock.uptimeMillis();
            if (now - warmNudgeAt >= WARM_NUDGE_MS) {
                warmNudgeAt = now;
                if (warmUnbound == 0) {
                    reporter.diag = "warm=unbound waiting-mirror " + reporter.wallpaper + " | " + reporter.diag;
                    if (reporter.diag.length() > 1900) reporter.diag = reporter.diag.substring(0, 1900);
                }
                warmUnbound++;
                requestDraw();
            }
            return;
        }
        warmUnbound = 0; warmNudgeAt = 0;
        NativeSceneChannel.MirrorState binding = liveScene.mirror();
        // applyPose checks this too. begin() proved the mirror existed, but this runs on a later
        // frame, and a null here would be an NPE that the pre-draw catch turns into a disable.
        if (binding == null) return;
        if (renderedMirrorSession != binding.session() || renderedMirrorGeneration != binding.generation()) {
            reveal.reset(full, SystemClock.uptimeMillis());
            renderedMirrorSession = binding.session(); renderedMirrorGeneration = binding.generation();
        }
        MotionFrame motion = MotionFrame.sample(full, 0, full, window.getHeight(), canDismiss(), session.config().blurStrength);
        MotionFrame frame = reveal.compose(motion, full, liveScene.nativeReady() || liveScene.backdropReady(), SystemClock.uptimeMillis(), session.config().blurStrength);
        boolean dismissible = canDismiss();
        if (warmAppliedSession != binding.session() || warmAppliedGeneration != binding.generation()
                || warmAppliedDismissible != dismissible || warmWidth != window.getWidth() || warmHeight != window.getHeight()) {
            // This was the last unguarded wallpaperMotion.apply(). A composition that refuses to draw
            // - the framework retires the mirror between begin() above and this frame - threw out to
            // the pre-draw listener, whose catch sets failed=true; that flag is only ever set, never
            // cleared in this class, so the whole adaptation stayed off until SystemUI restarted.
            // composeExit degrades exactly like applyPose and applyExitPose do.
            composeExit(frame, null);
            if (wallpaperFailed) {
                // The wallpaper is now native-owned and warming has nothing left to compose. Drop the
                // flag so warmWatch can re-prepare (which clears wallpaperFailed) instead of idling
                // until the next touch.
                warming = false;
                return;
            }
            warmAppliedSession = binding.session(); warmAppliedGeneration = binding.generation();
            warmAppliedDismissible = dismissible; warmWidth = window.getWidth(); warmHeight = window.getHeight();
        }
        reporter.motionFrame = "warm=true; p=1; wallpaperP=1; ready=" + liveScene.nativeReady() + "; driver=preDraw";
        if (warmReadyReported != liveScene.nativeReady()) { warmReadyReported = liveScene.nativeReady(); reporter.publish(); }
        else reporter.publishFrame();
    }
    private void applyPose() {
        // Retain the presented pose between height callbacks and pre-draw. The role helpers
        // adopt genuine native property writes without publishing a baseline-only frame.
        if (failed) return;
        float full = ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue();
        View window = gestureLayers[0];
        // Once native authentication accepted the swipe, changing keyguard state
        // must not switch this same animation back into the password branch.
        boolean dismissible = nativeAuthenticationStarted || canDismiss();
        liveScene.begin(window.getContext(), dismissible);
        if (engaged) liveScene.reveal();
        // Keep finger motion immediate; only wallpaper coverage waits for scene readiness.
        MotionFrame motion = MotionFrame.sample(engaged ? rawHeight : full, engaged ? frameVelocity : 0, full, window.getHeight(), dismissible, session.config().blurStrength);
        boolean bound = false;
        if (!wallpaperFailed) try { bound = wallpaperMotion.begin(window, liveScene, session.config().cornerRadiusDp); }
        catch (Throwable t) { failWallpaper(t); }
        reporter.wallpaperBound = bound;
        NativeSceneChannel.MirrorState binding = liveScene.mirror();
        if (bound && binding != null && (renderedMirrorSession != binding.session() || renderedMirrorGeneration != binding.generation())) {
            reveal.reset(full, SystemClock.uptimeMillis());
            renderedMirrorSession = binding.session(); renderedMirrorGeneration = binding.generation();
        }
        boolean sceneReady = bound && liveScene.nativeReady();
        boolean backdropReady = bound && (sceneReady || liveScene.backdropReady());
        MotionFrame presented = reveal.compose(motion, full, backdropReady, SystemClock.uptimeMillis(), session.config().blurStrength);
        float p = presented.progress();
        gestureMin = Math.min(gestureMin, p); gestureMax = Math.max(gestureMax, p);
        gestureReason = !bound ? "内容跟手；" + reporter.wallpaper
                : dismissible && !sceneReady ? (backdropReady ? "内容跟手；桌面壁纸过渡，等待返回首帧：" : "内容跟手；保持锁屏覆盖：") + reporter.liveScene
                : "统一运动帧：内容位移 / 壁纸裁切淡出 / 原生场景模糊";
        gestureApplied |= pose.apply(presented.contentY(), gestureLayers);
        MotionFrame frame = presented.coveringContent(window.getTranslationY());
        int wallpaperRoles = wallpaperPose.apply(frame, pose, wallpaperRoles());
        renderedRawHeight = full * frame.progress() + frame.springHeight();
        long token = session.generation();
        boolean finalFrame = (waitingForScene || pendingFinish == token) && !session.touching() && !running()
                && VisualHandoff.settled(progress(), frame, sceneReady, engaged, pendingAuthentication);
        Runnable completion = null;
        if (finalFrame && finishCommit != token && bound && !wallpaperFailed) {
            finishCommit = token;
            long bindingSession = binding.session(), bindingGeneration = binding.generation();
            completion = () -> {
                try {
                    if (finishCommit != token) return;
                    finishCommit = -1;
                    NativeSceneChannel.MirrorState current = liveScene.mirror();
                    if (session.generation() != token || !session.active() || session.touching() || running()
                            || current == null || current.session() != bindingSession || current.generation() != bindingGeneration) return;
                    trace("final-frame:committed p=" + frame.progress() + " ready=" + liveScene.nativeReady());
                    presentedFinish = token;
                    dismissal.presented(token); finish(token);
                } catch (Throwable t) { fail(t); }
            };
        }
        if (bound) try {
            wallpaperMotion.apply(frame, completion);
        } catch (Throwable t) { failWallpaper(t); }
        lastWallpaperProgress = !bound || wallpaperFailed ? 1 : frame.wallpaperProgress();
        closedFrame = bound && frame.progress() <= .001f && frame.wallpaperAlpha() == 0 ? frame : null;
        reporter.motionFrame = "y=" + Math.round(frame.contentY()) + "px; raw=" + Math.round(rawHeight)
                + "; p=" + Math.round(p * 1000) / 1000f + "; wallpaperP=" + Math.round(lastWallpaperProgress * 1000) / 1000f
                + "; wallH=" + Math.round(wallpaperFailed ? window.getHeight() : frame.wallpaperHeight())
                + "; blurY=" + Math.round(frame.blurY())
                + "; nativeH=" + Math.round(NativeReflection.number(panel, "mExpandedHeight"))
                + "; rootY=" + Math.round(window.getTranslationY()) + "; finger=" + session.touching()
                + "; ready=" + sceneReady + "; backdrop=" + backdropReady + "; wallpaperFailed=" + wallpaperFailed
                + "; wallpaperRoles=" + wallpaperRoles + "; driver=preDraw";
        reporter.publishFrame();
        if (Math.abs(p - lastProgress) > .00001f) { reporter.motion.incrementAndGet(); lastProgress = p; }
    }
    private void recover(boolean expand) {
        reporter.releaseReason = "TM:recover expand=" + expand + " p=" + Math.round(progress() * 1000) / 1000f;
        if(nativeAuthenticationStarted){startRenderTick();return;}
        if(nativeDismissalRequested){liveScene.cancelNativeExit();nativeDismissalRequested=false;}
        dismissal.reset(); pendingAuthentication = waitingForScene = false;
        long token = session.generation();
        if (ownedAnimator != null) ownedAnimator.cancel();
        // Keep the native closing fling deferred while recovering from zero height.
        // Its completion recomputes native panel visibility; doing that at zero can
        // start native dismissal before the recovery has restored the locked panel.
        recoveryFlingEnd = expand ? token : -1;
        retouchOffset = 0; pendingFinish = finishCommit = presentedFinish = -1;
        float full = ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue();
        float start = rawHeight, end = expand ? full : 0, delta = end - start;
        if (Math.abs(delta) < .01f) {
            rawHeight = end;
            NativeReflection.invoke(heightWriter, panel, end);
            queueFinish(token); return;
        }
        SpringCurve curve = new SpringCurve(session.config().stiffness,
                expand ? session.config().expandDamping : session.config().closeDamping, frameVelocity / delta, delta);
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(curve.durationMillis);
        animator.setInterpolator(input -> input); ownedAnimator = animator; ownedGeneration = token;
        animator.addUpdateListener(value -> {
            if (session.generation() != token) return;
            float f = (float) value.getAnimatedValue(); rawHeight = start + delta * curve.interpolate(f);
            frameVelocity = f >= 1 ? 0 : delta * (float) curve.velocityAtSeconds(f * curve.durationMillis / 1000.0);
            NativeReflection.invoke(heightWriter, panel, FlymeMotion.clamp(rawHeight, 0, full));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (ownedAnimator == animator) ownedAnimator = null;
                if (!cancelled) queueFinish(token);
            }
        });
        engaged = true; stopRenderTick(); startRenderTick(); animator.start();
    }
    private void finish(long generation) {
        if (generation != session.generation() || session.touching() || running()) return;
        if(!failed && engaged && !pendingAuthentication && progress()<=.001f){
            if(!nativeDismissalRequested && !frameworkHandsOver() && canDismiss() && dismissal.consumeNativeRequest(generation,progress())){
                nativeDismissalRequested=true;waitingForScene=true;
                sceneDeadline=SystemClock.uptimeMillis()+5000;
                trace("native-auth:request-under-opaque-cover");liveScene.requestNativeExit(generation);
            }
            if(nativeDismissalRequested && !nativeAuthenticationStarted){
                if(liveScene.nativeExitPrepared()){
                    if(!canDismiss()){recover(true);return;}
                    nativeAuthenticationStarted=true;
                    trace("native-auth:prepared-ack:invoke");
                    nativeShowBouncer();
                }else if(liveScene.frameworkSilent()){
                    // The framework answered and then stood still: this build will not present the
                    // return window, so native owns the unlock below instead of a rebound here.
                    trace("native-auth:no-presentable-scene");
                }else{
                    if(SystemClock.uptimeMillis()>=sceneDeadline){reporter.releaseReason="框架覆盖准备失败，尚未交出原生控制权";recover(true);return;}
                    startRenderTick();return;
                }
            }
        }
        if (nativeAuthenticationStarted && nativeHideRequested && closedFrame != null && progress() <= .001f) {
            // Native auth has hidden keyguard: transfer the closed sheet to its exit owner.
            // Waiting for a preview fence here strands native HOME's resumed drawing behind us.
            waitingForScene = false; completeNativeFling(generation, false);
            handOffAuthentication(generation); session.finish(generation);
            engaged = false; input.reset(); trace("native-exit:closed-sheet-handoff");
            startRenderTick(); return;
        }
        if (!failed && engaged && !pendingAuthentication && progress() <= .001f
                && (nativeDismissalRequested || NativeReflection.bool(NativeReflection.get(panel, "mKeyguardStateController"), "mCanDismissLockScreen"))
                && (wallpaperFailed || !liveScene.nativeReady() || lastWallpaperProgress > .001f)
                // No framework scene will ever be ready; native authentication takes over below.
                && !frameworkHandsOver()) {
            if (!waitingForScene) { waitingForScene = true; sceneDeadline = SystemClock.uptimeMillis() + 5000; }
            if (nativeAuthenticationStarted || SystemClock.uptimeMillis() < sceneDeadline) {
                stopRenderTick(); startRenderTick(); return;
            }
            reporter.releaseReason = "真实场景未就绪，已回弹覆盖；" + reporter.liveScene;
            recover(true); return;
        }
        // A rebound can reach native height=full before readiness has caught the presented
        // frame up. Do not let this direct finish path bypass the on-draw commit callback.
        if (!failed && engaged && pendingFinish == generation && reporter.wallpaperBound
                && !wallpaperFailed && presentedFinish != generation) {
            // Durable record: this is the one branch that can leave a released sheet parked
            // mid-screen ("fast swipe under half neither unlocks nor springs back"), and it is
            // invisible in the 48-event journal once the next gesture arrives.
            reporter.releaseReason = "TM:await-wallpaper p=" + Math.round(progress() * 1000) / 1000f
                    + " presented=" + presentedFinish + " finger=" + session.touching() + " run=" + running();
            return;
        }
        // Retain the same transform owner across native end listeners and authentication
        // handoff. Clearing engaged first lets the native zoom producer run in this gap.
        waitingForScene = false; stopRenderTick();
        completeNativeFling(generation, recoveryFlingEnd == generation);
        try { restoreNativeExpansion(); }
        catch (Throwable t) { fail(t); }
        try { handOffAuthentication(generation); }
        catch (Throwable t) { fail(t); }
        if (!session.finish(generation)) return;
        trace("finish token=" + generation);
        if (!holdForNativeExit) {
            // Keep a full opaque prepared sheet after a rebound/tap, so the next fast gesture
            // does not pay IPC, wallpaper swap and START-draw latency again.
            if (!wallpaperFailed && (!engaged || rawHeight >= ((Number) NativeReflection.call(panel, "getMaxPanelTransitionDistance")).floatValue() - .1f)
                    && eligible() && reporter.wallpaperBound) { restorePose(); warming = true; }
            else releaseVisuals();
        }
        engaged = false; input.reset(); reporter.publish();
        if(holdForNativeExit)startRenderTick();
    }
    private void completeNativeFling(long generation, boolean cancelled) {
        if (deferredFlingEnd != generation || panel == null) return;
        deferredFlingEnd = -1; completingFling = true;
        if (recoveryFlingEnd == generation) recoveryFlingEnd = -1;
        try {
            trace("native-fling-end:complete cancelled=" + cancelled);
            NativeReflection.invoke(nativeFlingEnd, panel, cancelled);
        } finally { completingFling = false; }
    }
    private void handOffAuthentication(long generation) {
        if(nativeDismissalRequested && nativeAuthenticationStarted){
            holdForNativeExit=true;heldGeneration=generation;liveScene.handoff();requestDraw();return;
        }
        if (!failed && targetVersion && panel != null && keyguard != null && (pendingAuthentication || dismissal.consume(generation, progress()))) {
            Object state = NativeReflection.get(panel, "mKeyguardStateController");
            if (NativeReflection.bool(state, "mShowing") && !NativeReflection.bool(state, "mKeyguardGoingAway")
                    && !NativeReflection.bool(state, "mKeyguardFadingAway") && NativeReflection.bool(keyguard, kgInteractive)
                    && !NativeReflection.bool(keyguard, kgKeyguardOccluded) && !NativeReflection.bool(keyguard, kgKeyguardBouncerShowing)) {
                try {
                    boolean canDismiss = NativeReflection.bool(state, "mCanDismissLockScreen");
                    // Without the framework scene there is no cover to hold; native animates its own exit.
                    holdForNativeExit = canDismiss && !liveScene.frameworkSilent(); heldGeneration = generation;
                    trace("native-auth:handoff canDismiss=" + canDismiss + " hold=" + holdForNativeExit);
                    if (holdForNativeExit) liveScene.handoff();
                    nativeShowBouncer();
                    // A native exit timer is not evidence that the returned window owns a frame.
                    if (holdForNativeExit) requestDraw();
                } catch (Throwable t) { fail(t); }
            }
        }
    }
    private void abandonPose() {
        float parked = progress();
        reporter.releaseReason = "TM:abandon p=" + Math.round(parked * 1000) / 1000f
                + " finger=" + session.touching() + " engaged=" + engaged + " scene=" + reporter.scene;
        if (session.touching() || engaged) trace("abandon:" + reporter.scene);
        warmUnbound = 0; ineligibleSince = 0;
        session.endInput(session.generation()); session.finish(session.generation());
        dismissal.reset(); pendingAuthentication = waitingForScene = false;
        ValueAnimator oldAnimator = ownedAnimator; ownedAnimator = null;
        if (oldAnimator != null && oldAnimator.isRunning()) oldAnimator.cancel();
        completeNativeFling(session.generation(), true);
        stopRenderTick(); releaseVisuals(); engaged = false; input.reset();
        // No height write-back here. Writing mExpandedHeight back to full corrupted the release
        // decision: progress() reads the native mExpandedHeight, so a slow under-half swipe whose
        // gesture was abandoned mid-way then released at p=1.0 and unlocked. The parked panel is
        // instead returned by not abandoning a gesture on a transient ineligibility (see renderTick),
        // and by the release coordinator's own rebound decision.
    }
    private void restoreNativeExpansion() {
        if (!ready || failed || !targetVersion || panel == null || keyguard == null) return;
        if (NativeReflection.integer(panel, "mBarState") == 1 && !NativeReflection.bool(panel, "mDozing")
                && NativeReflection.bool(keyguard, kgInteractive)) {
            // Our f=1 visual override must not survive the final native animation callback.
            // Native guards, actual panel fraction and bouncer state determine the restored values.
            restoringNativeExpansion = true;
            Method restore = NativeReflection.methodOrNull(keyguard.getClass(), "updateKeyguardElementsExpansion", boolean.class);
            try { if (restore != null) NativeReflection.invoke(restore, keyguard, false); }
            finally { restoringNativeExpansion = false; }
        }
    }
    private void restorePose() { try { wallpaperPose.restore(); pose.restore(); } catch (Throwable t) { failed = true; reporter.fail(t); } }
    private boolean nativeForegroundHidden() {
        View foreground = (View) NativeReflection.get(keyguard, kgForegroundLayer);
        View depth = (View) NativeReflection.optGet(NativeReflection.optGet(keyguard, "keyguardDepthInteractor"), "deductedImageView");
        return VisualHandoff.nativeHidden(NativeReflection.bool(keyguard, kgKeyguardShowing),
                NativeReflection.integer(keyguard, kgStatusBarState),
                frameWindow != null && frameWindow.getWindowVisibility() == View.VISIBLE
                        && (foreground != null && foreground.isShown() || depth != null && depth.isShown()));
    }
    private void applyExitPose() {
        if (!ownsVisuals()) return;
        if (closedFrame == null) { abandonPose(); return; }
        if (nativeExitCommit == heldGeneration) return;
        if (failed) return;
        if (nativeHideRequested && nativeForegroundHidden() && liveScene.nativeOwned()) {
            restorePose();
            long generation = heldGeneration;
            nativeExitCommit = generation;
            trace("native-exit:hidden-frame:queued");
            composeExit(closedFrame, () -> {
                if (!holdForNativeExit || heldGeneration != generation || nativeExitCommit != generation) return;
                nativeExitCommit = -1;
                if (!nativeForegroundHidden()) { requestDraw(); return; }
                trace("native-exit:hidden-frame:committed");
                releaseVisuals(); reporter.publish();
            });
        } else {
            if (nativeHideRequested && nativeForegroundHidden() && nativeExitCommit != heldGeneration) {
                long token = heldGeneration;
                nativeExitCommit = token;
                composeExit(closedFrame, () -> {
                    if (!holdForNativeExit || heldGeneration != token) return;
                    nativeExitCommit = -1;
                    if (nativeForegroundHidden()) { liveScene.foregroundHidden(); trace("native-exit:foreground-proof"); }
                });
                return;
            }
            // Native callbacks may write alpha=1 during the logical-hide/actual-hide gap.
            // Keep the closed foreground cropped/faded until their hidden draw is queued.
            pose.apply(closedFrame.contentY(), gestureLayers);
            wallpaperPose.apply(closedFrame, pose, wallpaperRoles());
            composeExit(closedFrame, null);
        }
    }
    /**
     * Compose one frame for the native exit.
     *
     * <p>The exit path must degrade like {@link #applyPose} does. Its calls were unguarded, so a
     * composition that refused to draw - a stale or retired mirror, which happens once the mirror
     * generation moves on or a rebuild has failed - threw out of this method into the pre-draw
     * listener's catch, and that catch disables the entire SystemUI adaptation. A dead mirror is a
     * wallpaper problem: hand the wallpaper back to native and let the gesture finish.
     */
    private void composeExit(MotionFrame frame, Runnable committed) {
        try { wallpaperMotion.apply(frame, committed); }
        catch (Throwable t) { failWallpaper(t); }
    }
    private void releaseVisuals() {
        boolean completed=holdForNativeExit&&liveScene!=null&&liveScene.nativeOwned();
        long completedGeneration=heldGeneration;
        if(!completed&&desktopAnimation!=null)desktopAnimation.cancel();
        if(liveScene!=null&&liveScene.homeDeferred()){
            // Only the withheld early RESUME is proven here; the launcher's visuals are its own.
            reporter.desktopAnimationState=completed?"released":"cancelled";
            reporter.desktopAnimationDetail=completed?"native unlock resumed HOME after the swipe; gesture="+completedGeneration
                :"gesture ended without unlock; HOME stays native";
        }
        warming = false; holdForNativeExit = nativeHideRequested = false;
        nativeDismissalRequested=nativeAuthenticationStarted=false;
        heldGeneration = nativeExitCommit = -1; closedFrame = null;
        pendingFinish = finishCommit = presentedFinish = -1;
        warmAppliedSession = warmAppliedGeneration = -1; warmReadyReported = false;
        restorePose();
        try {
            // A completed unlock must not re-apply lock-time wallpaper/window blur to the desktop.
            Runnable nativeCleanup = wallpaperMotion == null ? () -> {} : wallpaperMotion.retire(completed);
            Runnable cleanup=()->{try{nativeCleanup.run();}finally{if(completed&&desktopAnimation!=null)desktopAnimation.complete(completedGeneration);}};
            if (liveScene != null) liveScene.end(cleanup); else cleanup.run();
        }
        catch (Throwable t) { wallpaperFailed = true; reporter.wallpaper = "锁屏壁纸恢复失败：" + t.getMessage(); }
    }
    private void failWallpaper(Throwable t) {
        if(desktopAnimation!=null)desktopAnimation.cancel();
        wallpaperFailed = true;
        reporter.wallpaperBound = false;
        try { liveScene.end(wallpaperMotion.retire()); } catch (Throwable ignored) {}
        reporter.wallpaper = "壁纸功能已交回原生：" + t.getClass().getSimpleName() + ": " + t.getMessage();
        reporter.publish();
    }
    private void fail(Throwable t) { failed = true; dismissal.reset(); releaseVisuals(); reporter.fail(t); }
    private record Fling(float start, float end, float velocity, boolean expand, MotionConfig config, long generation) {}

}
