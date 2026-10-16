package com.zhuanz.flymemotion.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.BroadcastOptions;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.WindowManager;
import com.zhuanz.flymemotion.ConfigCodec;
import com.zhuanz.flymemotion.core.Members;
import com.zhuanz.flymemotion.core.SceneLease;
import com.zhuanz.flymemotion.core.SceneReadiness;
import com.zhuanz.flymemotion.core.NativeVisibilityLease;
import com.zhuanz.flymemotion.core.ReturnWindowPolicy;
import com.zhuanz.flymemotion.core.SceneActivation;
import com.zhuanz.flymemotion.core.SceneExitPolicy;
import com.zhuanz.flymemotion.SceneProtocol;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Current-build scene lease. Native authentication and keyguard/sleep flags are untouched. */
final class SystemServerSceneHooks {
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private final SceneLease lease = new SceneLease();
    private final ThreadLocal<Object> visibilityDisplay = new ThreadLocal<>();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final List<Object> homeTokens = new ArrayList<>();
    private Object keyguard, wm, root, target, candidate, lockWindow, lockToken, shadeWindow, clientActivity;
    private Object endedHomeLock; private long endedHomeAt, endedAt = -10_000;
    private final NativeVisibilityLease clientLease = new NativeVisibilityLease();
    private final ThreadLocal<Boolean> clientWriting = new ThreadLocal<>();
    private Context context;
    private Handler handler;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private volatile boolean enabled, failed, clientCanDismiss;
    private boolean lateDesktopRequested;
    private LateHomeLockedStateHooks lateHome;
    private boolean registered;
    private volatile boolean interactive;
    private int systemUiUid = -1;
    private String lastStatus = "";
    private boolean lastReady;
    private boolean homeEngineDrawn, lockEngineDrawn;
    private long homeGeneration, lockGeneration;
    /** How long the scene must stay un-presentable before the cover re-protects; see publishScene. */
    private static final long COVER_RECOVER_MS = 400;
    /** When the scene was last seen not ready; 0 while ready. */
    private long unreadySince;
    private final com.zhuanz.flymemotion.core.SceneStatusOrder producerOrder = new com.zhuanz.flymemotion.core.SceneStatusOrder();
    private List<android.os.Bundle> producerFrames = List.of();
    // The wallpaper's frame receipt hop, counted so a silent producer is told apart from a
    // producer whose frames arrive but are filtered out by the orders/liveness checks above.
    private long producerStatusSeen, producerStatusDropped;
    private SceneSurfaceState surfaces;
    private ServerWallpaperMirror mirror;
    private ServerSceneCover cover;
    private NativePresentationFence fences;
    private boolean nativeExitRequested;
    private long nativeExitRequest=-1;
    /** Why {@link #ensureReturnProof} last refused to request a draw, or null when it did. */
    private volatile String drawGate;
    /** Which wallpaper clients the tokens belong to; see bindWallpapers. */
    private volatile String wallpaperClients = "none";
    private final com.zhuanz.flymemotion.core.SceneRequestOrder nativeExitOrder=new com.zhuanz.flymemotion.core.SceneRequestOrder();
    private TargetDrawDispatcher drawDispatcher;
    private final com.zhuanz.flymemotion.core.ScenePresentation presentation = new com.zhuanz.flymemotion.core.ScenePresentation();
    private volatile Object drawWindow;
    private final java.util.Set<SurfaceControl.Transaction> hazards = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long drawLayer = -1, drawProducerLayer=-1, statusSequence;
    private final ThreadLocal<com.zhuanz.flymemotion.core.DrawDelivery> clientDraw = new ThreadLocal<>();
    private long drawRequests, clientDrawDeliveries, drawHandlers, drawTimeouts, drawNullBuffers, drawRejected, drawCompletions;
    private long queuedDrawDispatches;
    private long preservedBufferRequests, retriedDrawRequests;
    private long retrySession = -1, retryWindow, retrySurface;
    private int retryCount;
    private final java.util.ArrayDeque<String> drawJournal = new java.util.ArrayDeque<>();
    private int expectedDrawSequence, lastDeliveredSequence = -1;
    private String lastDrawReceipt = "none";
    private long lastStartAttempt;
    private final ThreadLocal<Object> activatingActivity = new ThreadLocal<>();
    private Object resumedByScene;
    private boolean resumeAcknowledged;
    /** When {@link #startClientIfStopped} forced this target's resume; see {@link #rendererReady}. */
    private long resumedBySceneAt;
    private boolean revealing, sleepingBarrier;
    private Method checkVisibility, wallpaperWindows, ensureVisible, adjustWallpaper, relativeLayer, clientVisibility;
    private boolean nativeTakeoverConfirmed;
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            guarded(() -> {
                if (lease.id() < 0) return;
                if (nativeTakeoverConfirmed) {
                    // Native focus/lifecycle now own the scene. Only finish our cover's commit.
                    publishScene(); handler.postDelayed(this,150); return;
                }
                // Native auth may switch ActivityRecord (login/launch/recovery), not just the
                // main WindowState. Follow only the actual native focused task after unlock.
                if (presentation.handoff() && nativeUnlockStarted() && !safeActivity(target)) {
                    Object area = NativeReflection.call(NativeReflection.call(root,"getDefaultDisplay"),"getDefaultTaskDisplayArea");
                    Object task = NativeReflection.call(area,"getFocusedRootTask");
                    Object next = task == null ? null : NativeReflection.call(task,"topRunningActivityLocked");
                    if (safeActivity(next)) { protectAndInvalidate(); restoreClient(true); target = candidate = next; }
                }
                // A peer lease is liveness, never presentation proof or a handoff deadline.
                if (!lease.active(now()) && presentation.handoff() && nativeUnlockStarted() && !sleepingBarrier && interactive) lease.renewHandoff(now());
                if (!lease.active(now()) || !allowed()) { stop("场景已回滚：超时或原生状态改变 " + allowedDetail()); return; }
                if (!presentation.handoff() && resumedByScene != null && !inputOwnedByShade()) { stop("场景已回滚：SystemUI 输入焦点已交接"); return; }
                // The handoff hold must not outlive its bound even while the lease keeps renewing.
                // Without this, a handoff that never completes (no native takeover / exposure on this
                // build) keeps the scene alive with the cover opaque forever - the desktop showing
                // only its wallpaper, the control panel invisible behind the cover, and the swipe
                // unresponsive - until a sleep forces the cancel path. stop() itself only re-checks
                // the bound when it is re-invoked, and this loop re-invokes it only on lease expiry
                // or ineligibility, both of which the renew above forestalls.
                if (presentation.handoff() && nativeUnlockStarted() && handoffHoldExpired()) {
                    stop("handoff 超时未取得完整回执，交回原生并退役覆盖"); return;
                }
                Object next = appAllowed() && safeActivity(candidate) ? candidate : null;
                if (presentation.handoff() && safeActivity(target)) next = target;
                if (target != next) {
                    cover.requireLockProtection(); nativeTakeoverConfirmed = false;
                    protectAndInvalidate(); target = next; refresh(); restoreClient(); surfaces.restore();
                }
                bindWallpapers();
                prepareClient();
                startClientIfStopped();
                if (target != null) { prepareSurfaces(); prepareHomeWallpaperSurfaces(); prepareLockProducerSurfaces(); }
                // Wallpaper/app clients draw asynchronously. Report the actual drawn state later.
                publishScene(); handler.postDelayed(this, 150);
            });
        }
    };

    SystemServerSceneHooks(MotionModule module, ClassLoader loader, RuntimeReporter reporter) {
        this.module = module; this.loader = loader; this.reporter = reporter;
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName("com.android.server.wm." + name, false, loader); }
    void install() throws Throwable {
        Features features = new Features(module, handles, reporter.compat);
        try {
            lateHome = new LateHomeLockedStateHooks(module,loader,reporter); lateHome.install();
            fences = new NativePresentationFence(loader);
            if (!fences.presentFence) features.variant("框架场景", "呈现回执改用提交回调");
            drawDispatcher = new TargetDrawDispatcher(loader);
            features.variant("框架场景", "绘制派发=" + drawDispatcher.mode());
            surfaces = new SceneSurfaceState(module, loader, () -> allowed() && appAllowed() && coverCommitted());
            surfaces.onHazard(this::surfaceHazard, () -> { if (handler != null) handler.post(() -> guarded(this::publishScene)); });
            surfaces.install();
            // Inlined Builder.build callers must execute the visibility/parent observer too.
            for (String name : new String[]{"WindowContainer", "WindowStateAnimator", "DisplayContent", "Task", "SurfaceAnimator"})
                for (Method method : type(name).getDeclaredMethods())
                    if (method.getName().contains("Surface") || method.getName().contains("Leash")) module.deoptimize(method);
            Class<?> kc = type("KeyguardController"), wms = type("WindowManagerService"), activity = type("ActivityRecord");
            Class<?> token = type("WallpaperWindowToken"), container = type("WindowContainer"), window = type("WindowState");
            // This caller writes native LOCK visibility during deferred transitions.
            // Deoptimize it so inlining cannot bypass the selected-source visibility guard.
            for (Method caller : Members.overloads(token, "updateSurfaceVisibility")) features.deopt("框架场景", caller);
            NativeReflection.method(window, "applyWithNextDraw", java.util.function.Consumer.class);
            NativeReflection.field(window, "mSyncSeqId");
            Method deliveredDraw = NativeReflection.method(wms,"finishDrawingWindow",type("Session"),
                    Class.forName("android.view.IWindow",false,loader),SurfaceControl.Transaction.class,int.class);
            register(deliveredDraw, chain -> {
                com.zhuanz.flymemotion.core.DrawDelivery delivery = null;
                if (chain.getThisObject() == wm && drawWindow != null) synchronized (NativeReflection.get(wm,"mGlobalLock")) {
                    Object selectedWindow = drawWindow;
                    android.os.IBinder incoming = ((android.os.IInterface)chain.getArg(1)).asBinder();
                    if (selectedWindow != null && incoming.equals(((android.os.IInterface)NativeReflection.get(selectedWindow,"mClient")).asBinder())) {
                        delivery = new com.zhuanz.flymemotion.core.DrawDelivery(lease.id(),presentation.epoch(),identity(selectedWindow),
                                layer(bufferSurface(selectedWindow)),(int)chain.getArg(3),chain.getArg(2)!=null);
                        clientDrawDeliveries++; lastDeliveredSequence=delivery.sequence();
                        if (!delivery.bufferTransaction()) drawNullBuffers++;
                    }
                }
                var previous = clientDraw.get();
                if (delivery == null) clientDraw.remove(); else clientDraw.set(delivery);
                try {
                    return chain.proceed();
                } finally { if (previous == null) clientDraw.remove(); else clientDraw.set(previous); }
            });
            // Authenticity comes from the public client delivery, not an inner WindowState
            // hook that may be bypassed by optimized callers or a synthetic draw timeout.
            features.deopt("框架场景", deliveredDraw);
            for (Method caller : Members.overloads(type("Session"), "finishDrawing")) features.deopt("框架场景", caller);
            for (Method caller : Members.overloads(window, "finishDrawing")) features.deopt("框架场景", caller);
            // Resize/draw ACKs are metadata on this build. Observe the actual selected
            // producer's completed buffer instead of continually changing its buffer sequence.
            NativeReflection.method(window,"sendAppVisibilityToClients");
            Method unsetBuffer = NativeReflection.methodOrNull(SurfaceControl.Transaction.class,"unsetBuffer",SurfaceControl.class);
            if (unsetBuffer == null) features.variant("框架场景", "无 unsetBuffer");
            else register(unsetBuffer,chain->{
                SurfaceControl removed=(SurfaceControl)chain.getArg(0);
                if(wm!=null) synchronized(NativeReflection.get(wm,"mGlobalLock")){
                    Object selected=targetWindow();
                    if(selected!=null&&allowed()&&appAllowed()&&layer(removed)>0&&layer(removed)==layer(clientSurface(selected)))
                        surfaceHazard((SurfaceControl.Transaction)chain.getThisObject());
                }
                return chain.proceed();
            });
            for(String name:new String[]{"dropBufferFrom","prepareSync","finishSync","reportResized"})
                for(Method caller:window.getDeclaredMethods())if(caller.getName().equals(name))module.deoptimize(caller);
            checkVisibility = NativeReflection.method(kc, "checkKeyguardVisibility", activity);
            Method visible = NativeReflection.method(activity, "shouldBeVisibleUnchecked");
            Method sleeping = NativeReflection.method(type("DisplayContent"), "isSleeping");
            // Android 17 added a third (group-interactive) argument; Android 15/16 have two.
            // Every overload is hooked; the display group is always the first argument.
            Class<?> policy = Class.forName("com.android.server.policy.PhoneWindowManager", false, loader);
            List<Method> goingToSleep = requiredAll(Members.overloads(policy, "startedGoingToSleep"), "startedGoingToSleep");
            List<Method> wakingUp = requiredAll(Members.overloads(policy, "startedWakingUp"), "startedWakingUp");
            Method keyguardShown = required(Members.method(kc, Members.Sig.of("setKeyguardShown", int.class, boolean.class, boolean.class),
                    Members.Sig.of("setKeyguardShown", boolean.class, boolean.class)), "setKeyguardShown");
            keyguardShownDisplay = keyguardShown.getParameterCount() == 3;
            // Android 17 keyguardGoingAway(flags); Android 15/16 keyguardGoingAway(displayId, flags).
            Method goingAway=required(Members.method(kc,Members.Sig.of("keyguardGoingAway",int.class),
                    Members.Sig.of("keyguardGoingAway",int.class,int.class)),"keyguardGoingAway");
            if (goingAway.getParameterCount() == 2) features.variant("框架场景", "keyguardGoingAway(display,flags)");
            register(goingAway,chain->{
                // Authenticated HOME exit: hide the retired LOCK producer before WM builds the
                // keyguard transition that would otherwise show it above the desktop wallpaper.
                // A swipe can end the scene (or not yet report canDismiss) before this point, so
                // also cover a HOME scene that ended moments ago; otherwise MIUI re-shows and fades
                // the blurred LOCK buffer.
                // NOTE: going-away alone is *not* proof of authentication on every build - MIUI
                // raises it mid-drag - so the retirement decision inside retireLockForHomeExit()
                // additionally requires a real handoff. See the comment there.
                if(chain.getThisObject()==keyguard && mirror!=null) synchronized(NativeReflection.get(wm,"mGlobalLock")){
                    exitTrace("goingAway lease="+lease.id()+" hidden="+(retireLockForHomeExit("native-going-away")!=null));
                }
                // Pre-unlock null deliveries must not consume the native exit's draw request.
                // Arm before the original method resumes and redraws the returned client.
                if(chain.getThisObject()==keyguard && nativeExitRequested && lease.id()>=0 && !presentation.ready()){
                    synchronized(NativeReflection.get(wm,"mGlobalLock")){
                        retryCount=0;protectAndInvalidate();ensureReturnProof(targetWindow());
                        recordDrawReceipt("native-going-away:rearm-real-window");
                    }
                }
                return chain.proceed();
            });
            features.deopt("框架场景", goingAway);
            // Some unlocks build the keyguard transition before keyguardGoingAway reaches us (the
            // flash trace showed the LOCK token reparented and shown in that same frame). Hide it
            // inside the transition's own start transaction so no frame can present it.
            features.optional("解锁不闪锁屏壁纸", () -> {
            Class<?> transition=type("Transition");
            Method transactionReady=required(Members.method(transition,Members.Sig.of("onTransactionReady",int.class,SurfaceControl.Transaction.class)),
                    "Transition#onTransactionReady");
            NativeReflection.field(transition,"mFlags");NativeReflection.field(transition,"mParticipants");
            register(transactionReady,chain->{
                if(mirror!=null&&wm!=null&&!failed) synchronized(NativeReflection.get(wm,"mGlobalLock")){
                    boolean exiting=(NativeReflection.integer(chain.getThisObject(),"mFlags")&0x100)!=0
                            ||keyguard!=null&&(boolean)invokeKeyguard("isKeyguardGoingAway");
                    Object lock=exitLockToken();
                    if(exiting){
                        boolean participant=lock!=null&&((java.util.Collection<?>)NativeReflection.get(chain.getThisObject(),"mParticipants")).contains(lock);
                        SurfaceControl hidden=participant?retireLockForHomeExit("transition-ready"):null;
                        if(hidden!=null)for(SurfaceControl held:mirror.exitSurfaces())if(held.isValid())((SurfaceControl.Transaction)chain.getArg(1)).setVisibility(held,false);
                        exitTrace("ready flags=0x"+Integer.toHexString(NativeReflection.integer(chain.getThisObject(),"mFlags"))+" lease="+lease.id()
                                +" lock="+(lock!=null)+" participant="+participant+" hidden="+(hidden!=null));
                    }
                }
                return chain.proceed();
            });
            });
            Method resumeAck = NativeReflection.method(activity, "activityResumedLocked", android.os.IBinder.class, boolean.class);
            NativeReflection.field(type("TaskFragment"), "mResumedActivity");
            NativeReflection.field(type("DisplayContent"), "mCurrentFocus");
            NativeReflection.field(activity, "token");
            wallpaperWindows = NativeReflection.method(token, "updateWallpaperWindows", boolean.class);
            // Declared on WindowToken (Android 17) or only on ActivityRecord (older builds).
            clientVisibility = required(Members.declaredIn("setClientVisible", new Class<?>[]{boolean.class}, type("WindowToken"), activity),
                    "setClientVisible");
            required(Members.declaredIn("isClientVisible", new Class<?>[0], type("WindowToken"), activity), "isClientVisible");
            NativeReflection.method(activity, "getUid");
            // Android 15+ ensureActivitiesVisible(); Android 14 took (starting, configChanges, preserveWindows, notifyClients).
            ensureVisible = required(Members.method(type("RootWindowContainer"), Members.Sig.of("ensureActivitiesVisible"),
                    Members.Sig.of("ensureActivitiesVisible", activity, int.class, boolean.class, boolean.class),
                    Members.Sig.of("ensureActivitiesVisible", activity, boolean.class),
                    Members.Sig.of("ensureActivitiesVisible", activity)), "ensureActivitiesVisible");
            adjustWallpaper = NativeReflection.method(type("WallpaperController"), "adjustWallpaperWindows");
            relativeLayer = NativeReflection.method(SurfaceControl.Transaction.class, "setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
            for (String name : new String[]{"mLastLayer", "mLastRelativeToLayer"}) NativeReflection.field(container, name);
            Method bind = NativeReflection.method(kc, "setWindowManager", wms);
            register(bind, chain -> {
                Object value = chain.proceed();
                try {
                    keyguard = chain.getThisObject(); wm = chain.getArg(0);
                    root = NativeReflection.get(keyguard, "mRootWindowContainer");
                } catch (Throwable t) { disable(t); }
                return value;
            });
            register(NativeReflection.method(wms, "systemReady"), chain -> {
                Object value = chain.proceed();
                try { attach(chain.getThisObject()); } catch (Throwable t) { disable(t); }
                return value;
            });
            // The default-group policy callback precedes its SystemUI AOD/fade notification.
            // Do not query PowerManager here or wait for SF while acquiring WM's lock.
            for (Method sleepCall : goingToSleep) register(sleepCall, chain -> {
                if ((int) chain.getArg(0) == 0 && wm != null && !failed) synchronized (NativeReflection.get(wm, "mGlobalLock")) {
                    sleepingBarrier = true;
                    if (mirror != null) mirror.clearExitSources();
                    try { if (lease.id() >= 0) stop("熄屏前原子恢复锁屏场景", true); }
                    catch (Throwable t) { disable(t); }
                    reconcileWallpapers("sleep", 8);
                }
                return chain.proceed();
            });
            for (Method wakeCall : wakingUp) register(wakeCall, chain -> {
                if ((int) chain.getArg(0) == 0 && wm != null && !failed) synchronized (NativeReflection.get(wm, "mGlobalLock")) {
                    sleepingBarrier = false; reconcileWallpapers("wake", 8);
                }
                return chain.proceed();
            });
            register(keyguardShown, chain -> {
                // (displayId, keyguardShowing, aodShowing), or (keyguardShowing, aodShowing) for the default display.
                int o = keyguardShownDisplay ? 1 : 0;
                boolean defaultDisplay = !keyguardShownDisplay || (int) chain.getArg(0) == 0;
                if (chain.getThisObject() == keyguard && defaultDisplay && mirror != null) {
                    boolean[] state = keyguardState();
                    boolean showing = (boolean) chain.getArg(o), aod = (boolean) chain.getArg(o + 1);
                    if (com.zhuanz.flymemotion.core.LockCycle.newLock(state[0], state[1], state[2], showing, aod)) {
                        mirror.clearExitSources();
                        if (presentation.handoff()) stop("新的原生锁屏周期", true);
                        reconcileWallpapers("lock", 8);
                    } else if (showing && !(boolean) invokeKeyguard("isKeyguardGoingAway")) {
                        // A lockscreen that is simply up again must have its wallpaper back. The
                        // hold below is for the exit that produced it, and this build does not
                        // always report the boundary newLock() looks for, so relying on that alone
                        // left the LOCK source hidden: black lockscreen with only the clock, until
                        // something forced a real lock cycle.
                        mirror.clearExitSources();
                        // Nothing this module held may survive into a new lock either. A visibility
                        // lease or a held source left over from the previous cycle is what made the
                        // next gesture fail to trigger at all, and what kept the lockscreen black on
                        // wake until a real boundary happened to reset it. Both calls are no-ops
                        // unless something really did leak.
                        if (clientLease.active()) restoreClient();
                        reconcileWallpapers("lock", 8);
                    }
                }
                if (chain.getThisObject() == keyguard && defaultDisplay && (boolean) chain.getArg(o + 1)
                    && lease.id() >= 0) {
                    sleepingBarrier = true;
                    try { stop("AOD 原生遍历前恢复场景", true); } catch (Throwable t) { disable(t); }
                }
                return chain.proceed();
            });
            register(checkVisibility, chain -> {
                Object nativeResult = chain.proceed();
                if (Boolean.TRUE.equals(nativeResult) || failed || target == null || chain.getArg(0) != target) return nativeResult;
                try {
                    if (chain.getThisObject() == keyguard && allowed() && appAllowed() && coverCommitted() && safeActivity(target)) {
                        reporter.motion.incrementAndGet(); return true;
                    }
                } catch (Throwable t) { disable(t); }
                return nativeResult;
            });
            register(visible, chain -> {
                boolean own = false;
                try { own = !failed && chain.getThisObject() == target && allowed() && appAllowed() && coverCommitted() && safeActivity(target); }
                catch (Throwable t) { disable(t); }
                if (!own) return chain.proceed();
                Object previous = visibilityDisplay.get();
                visibilityDisplay.set(NativeReflection.get(target, "mDisplayContent"));
                try { return chain.proceed(); }
                finally { if (previous == null) visibilityDisplay.remove(); else visibilityDisplay.set(previous); }
            });
            register(sleeping, chain -> {
                // Only selected-target visibility or explicit native activation sees this query
                // override; the actual sleep tokens, power and keyguard flags stay native.
                return !failed && (visibilityDisplay.get() == chain.getThisObject()
                        || activatingActivity.get() == target && activationPermitted()
                            && NativeReflection.get(target, "mDisplayContent") == chain.getThisObject()) ? false : chain.proceed();
            });
            register(NativeReflection.method(activity, "shouldResumeActivity", activity), chain -> {
                // Visibility traversal alone must not resume below a sleeping input shade.
                // Only explicit activation retains the native resume/user/AppLock checks.
                //
                // Once the gesture has asked to reveal, the hold is dropped. The hold exists to keep
                // the app from coming up beneath a cover that is not there yet; by then the cover IS
                // committed, and holding on longer deadlocks the reveal against itself - the device
                // measured activity=STARTED with startOnly climbing, rendererReady() false, so no
                // frame was ever requested and the cover had nothing to undraw. The app resuming
                // behind the committed cover is exactly what gives the reveal its frame.
                //
                // It must also stay dropped after the handover, which is where the state leaked:
                // `revealing` is only true while the finger is down, so it went false at release
                // and the hold came straight back, leaving the return target unreachable. On this
                // device the desktop wallpaper is a live wallpaper in its own process, so a launcher
                // that cannot resume still shows its wallpaper - the phone reports that as a desktop
                // with every icon missing, and it stayed that way until a real lock cycle. Handing
                // the unlock to native means native decides the resume now.
                if (chain.getThisObject() == target && allowed() && appAllowed() && coverCommitted() && safeActivity(target)
                        && !revealing && !presentation.handoff()
                        && SceneExitPolicy.deferNativeResume((boolean)invokeKeyguard("isKeyguardShowing"),
                            (boolean)invokeKeyguard("isKeyguardLocked"), (boolean)invokeKeyguard("isKeyguardGoingAway"),
                            activatingActivity.get() == target)) {
                    reporter.startOnly.incrementAndGet(); return false;
                }
                return chain.proceed();
            });
            register(resumeAck, chain -> {
                Object result = chain.proceed();
                if (resumedByScene != null && chain.getArg(0) == NativeReflection.get(resumedByScene, "token")) {
                    resumeAcknowledged = true;
                    handler.post(() -> guarded(this::publishScene));
                }
                return result;
            });
            register(clientVisibility, chain -> {
                if (Boolean.TRUE.equals(clientWriting.get()) || chain.getThisObject() != clientActivity || !clientLease.active())
                    return chain.proceed();
                boolean wanted = (boolean) chain.getArg(0);
                boolean qualified = allowed() && appAllowed() && coverCommitted() && clientActivity == target && safeActivity(target);
                boolean actual = clientLease.nativeChange(wanted, qualified);
                return actual == wanted ? chain.proceed() : chain.proceed(new Object[]{actual});
            });
            register(wallpaperWindows, chain -> {
                Object[] args = null;
                if (!failed && (homeTokens.contains(chain.getThisObject()) || chain.getThisObject() == lockToken) && lease.active(now())) {
                    try {
                        if (allowed() && (chain.getThisObject() == lockToken || coverCommitted())) {
                            args = chain.getArgs().toArray(); args[0] = true;
                        }
                    } catch (Throwable t) { disable(t); }
                }
                return args == null ? chain.proceed() : chain.proceed(args);
            });
            // Callers ART may inline past the hooks above. Names only: overloads differ per release.
            for (Method method : new Method[]{bind, checkVisibility, wallpaperWindows, clientVisibility, visible, sleeping, keyguardShown, resumeAck})
                features.deopt("框架场景", method);
            for (Method method : goingToSleep) features.deopt("框架场景", method);
            for (Method method : wakingUp) features.deopt("框架场景", method);
            for (String name : new String[]{"setVisibility", "setVisibilityInner", "commitVisibility", "shouldResumeActivity",
                    "makeActiveIfNeeded", "makeVisibleIfNeeded", "activityStopped", "transferStartingWindow"})
                for (Method method : Members.overloads(activity, name)) features.deopt("框架场景", method);
            for (Method method : Members.overloads(type("EnsureActivitiesVisibleHelper"), "setActivityVisibilityState")) features.deopt("框架场景", method);
            for (Method method : Members.overloads(token, "setVisibility")) features.deopt("框架场景", method);
            for (Method method : Members.overloads(window, "assignLayer")) features.deopt("框架场景", method);
            for (Method method : Members.overloads(type("TaskFragment"), "shouldSleepActivities")) features.deopt("框架场景", method);
            features.log.on("框架场景", "");
            reporter.state = "loaded";
        } catch (Throwable t) {
            failed = true;
            for (var handle : handles) try { handle.unhook(); } catch (Throwable ignored) {}
            if (surfaces != null) surfaces.uninstall();
            reporter.compat.off("框架场景", com.zhuanz.flymemotion.core.FeatureGate.reason(t));
            throw t;
        }
    }
    private boolean keyguardShownDisplay;
    private static <T> T required(T member, String what) {
        if (member == null) throw new IllegalStateException(what + " missing");
        return member;
    }
    private static <T> List<T> requiredAll(List<T> members, String what) {
        if (members.isEmpty()) throw new IllegalStateException(what + " missing");
        return members;
    }
    /** Native keyguard (showing, goingAway, aodShowing) for the default display, on any build. */
    private boolean[] keyguardState() {
        Method perDisplay = NativeReflection.methodOrNull(keyguard.getClass(), "getDisplayState", int.class);
        Object state = perDisplay == null ? null : NativeReflection.invoke(perDisplay, keyguard, 0);
        if (state != null && NativeReflection.has(state, "mKeyguardShowing"))
            return new boolean[]{NativeReflection.bool(state, "mKeyguardShowing"), NativeReflection.optBool(state, "mKeyguardGoingAway", false),
                    NativeReflection.optBool(state, "mAodShowing", false)};
        return new boolean[]{(boolean) invokeKeyguard("isKeyguardShowing"), (boolean) invokeKeyguard("isKeyguardGoingAway"),
                (boolean) invokeKeyguard("isAodShowing")};
    }
    private void register(java.lang.reflect.Executable method, XposedInterface.Hooker callback) {
        handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(callback));
        reporter.hooks = handles.size();
    }
    private void attach(Object service) {
        if (registered || failed || wm != service || keyguard == null) return;
        context = (Context) NativeReflection.get(wm, "mContext");
        systemUiUid = SceneReceivers.uid(context, "com.android.systemui");
        if (systemUiUid < 0) throw new IllegalStateException("SystemUI package UID unavailable");
        handler = (Handler) NativeReflection.get(wm, "mH"); reporter.attach(context);
        surfaces.attach(handler);
        mirror = new ServerWallpaperMirror(module, handler); mirror.exclusiveRole(this::exclusiveLockToken); mirror.install(); cover = new ServerSceneCover(handler);
        cover.onChanged(() -> guarded(this::publishScene));
        cover.onFailure(error -> guarded(() -> {
            reporter.error = "桌面壁纸渐变已停用，保留锁屏保护：" + error.getClass().getSimpleName();
            reporter.publish(); publishScene();
        }));
        surfaces.filterExitVisibility(mirror::visibility, mirror::exitSurface);
        surfaces.enforceRetiredSources(mirror::enforceExitVisibility);
        prefs = module.getRemotePreferences(ConfigCodec.GROUP);
        readConfig(); listener = (p, key) -> handler.post(() -> guarded(() -> {
            readConfig(); if (!enabled) {
                if (lease.id() >= 0) stop("管理器已停用，场景已回滚", true);
                if (mirror != null) mirror.clearExitSources();
            }
        }));
        prefs.registerOnSharedPreferenceChangeListener(listener);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                // Channel explicitly shares sender identity; require both privileged permission and UID.
                if (!SceneReceivers.fromPackage(c, this, "com.android.systemui")) return;
                guarded(() -> request(intent));
            }
        }, new IntentFilter(NativeSceneChannel.ACTION), NativeSceneChannel.PERMISSION, handler, Context.RECEIVER_EXPORTED);
        registered = true; reporter.liveScene = "系统框架场景入口就绪，SystemUI uid=" + systemUiUid + "，等待锁屏手势"; reporter.publish();
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!SceneReceivers.fromPackage(c, this, SceneProtocol.WALLPAPER)) return;
                guarded(() -> {
                    if (intent.getLongExtra("token", -1) != lease.id() || !lease.active(now())) { producerStatusDropped++; return; }
                    if (!producerOrder.accept(lease.id(),intent.getLongExtra("statusSequence",-1))) { producerStatusDropped++; return; }
                    var frames = intent.<android.os.Bundle>getParcelableArrayListExtra("frames");
                    producerFrames = frames == null ? List.of() : frames;
                    producerStatusSeen++;
                    publishScene();
                });
            }
        }, new IntentFilter(SceneProtocol.WALLPAPER_STATUS), null, handler, Context.RECEIVER_EXPORTED);
        SceneReceivers.revoke(context, handler, reporter, () -> guarded(() -> stop("管理器重载前撤销场景", true)));
    }
    private void readConfig() {
        var config = ConfigCodec.decode(prefs.getString(ConfigCodec.KEY, null));
        enabled = config.enabled; reporter.enabled = enabled; reporter.configRevision = config.revision;
    }
    private void request(Intent intent) {
        if (failed) return;
        long id = intent.getLongExtra("token", -1); String command = intent.getStringExtra("command");
        if ("end".equals(command)) { if (id == lease.id()) stop("手势结束，场景已交回原生"); return; }
        if ("reveal".equals(command)) {
            if (id != lease.id() || !allowed()) return;
            revealing = true; prepareClient(); refresh(); startClientIfStopped(); publishScene();
            return;
        }
        if ("nativeExit".equals(command)) {
            long generation=intent.getLongExtra("exitRequest",-1);
            if(id==lease.id() && generation>=0 && clientCanDismiss && !sleepingBarrier && nativeExitOrder.begin(id,generation)){nativeExitRequested=true;nativeExitRequest=generation;publishScene();}
            return;
        }
        if("cancelNativeExit".equals(command)) {
            if(id==lease.id() && !nativeUnlockStarted() && nativeExitOrder.end(id,intent.getLongExtra("exitRequest",-1))){
                nativeExitRequested=false;nativeExitRequest=intent.getLongExtra("exitRequest",-1);presentation.cancelNativeExit(id);publishScene();
            }
            return;
        }
        if ("handoff".equals(command) || "foregroundHidden".equals(command)) {
            if (id != lease.id() || intent.getLongExtra("bindingEpoch",-1) != presentation.epoch()) return;
            if ("handoff".equals(command)) presentation.beginHandoff(presentation.epoch());
            else presentation.foregroundHidden(presentation.epoch());
            publishScene(); return;
        }
        if ("mirrorCommitted".equals(command)) {
            SurfaceControl parent = intent.getParcelableExtra("coverParent",SurfaceControl.class);
            try {
                long generation = intent.getLongExtra("mirrorGeneration",-1);
                if (id == lease.id() && lease.active(now()) && generation == mirror.generation() && parent != null
                        && producerGeneration(lockWindow,2) > 0) {
                    Object anchor = shadeWindow, area = NativeReflection.call(anchor,"getParent");
                    while (area != null && !typeUnchecked("DisplayArea").isInstance(area)) { anchor = area; area = NativeReflection.call(area,"getParent"); }
                    if (area == null || NativeReflection.get(anchor,"mLastRelativeToLayer") != null)
                        throw new IllegalStateException("Stable Shade display-area layer unavailable");
                    SurfaceControl areaSurface = (SurfaceControl)NativeReflection.call(area,"getSurfaceControl");
                    // Relative layers inherit anchor visibility too. Use the native absolute
                    // sibling order in the stable policy area, independently of Shade hiding.
                    cover.bind(mirror.source(), areaSurface, NativeReflection.integer(anchor,"mLastLayer")-1, intent.getIntExtra("coverWidth",0), intent.getIntExtra("coverHeight",0),
                        () -> guarded(() -> {
                            if (id != lease.id() || generation != mirror.generation()) return;
                            if (!mirror.acknowledge(id,generation,intent.getLongExtra("mirrorLayer",-1))) return;
                            prepareClient(); refresh(); startClientIfStopped();
                            if (target != null) { prepareSurfaces(); prepareHomeWallpaperSurfaces(); prepareLockProducerSurfaces(); }
                            publishScene();
                        }));
                }
            } finally { if (parent != null) parent.release(); }
            return;
        }
        if ("renew".equals(command)) {
            if (!lease.renew(id, now())) return;
            boolean before = clientCanDismiss;
            clientCanDismiss = intent.getBooleanExtra("canDismiss", false);
            lateDesktopRequested = intent.getBooleanExtra("lateDesktopAnimation", false);
            if (before && !clientCanDismiss && !nativeUnlockStarted()) { stop("原生认证资格撤销，恢复完整锁屏",true); return; }
            if (!allowed()) stop("原生状态不允许场景，已回滚");
            return;
        }
        if (!"begin".equals(command) || id <= lease.id()) return;
        if (lease.id() >= 0) { stop("新手势替换旧场景"); if (lease.id() >= 0) return; }
        if (!lease.begin(id, now())) return;
        presentation.open(id); nativeExitRequested=false; nativeTakeoverConfirmed=false;
        // A new scene may use the protection cover again; see ServerSceneCover.rearm.
        if (cover != null) cover.rearm();
        revealing = false; drawWindow = null; drawLayer = -1;
        producerOrder.begin(id);
        reporter.sceneSession = id;
        if (!allowed()) { stop("锁屏/AOD/遮挡状态不允许场景 " + allowedDetail()); return; }
        clientCanDismiss = intent.getBooleanExtra("canDismiss", false);
            lateDesktopRequested = intent.getBooleanExtra("lateDesktopAnimation", false);
        Object display = NativeReflection.call(root, "getDefaultDisplay");
        shadeWindow = NativeReflection.invoke(NativeReflection.method(root.getClass(), "getWindow", Predicate.class), root,
                (Predicate<Object>) win -> {
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NativeReflection.get(win, "mAttrs");
                    return attrs.type == 2040 && "NotificationShade".contentEquals(attrs.getTitle())
                            && NativeReflection.integer(win, "mOwnerUid") == systemUiUid
                            && (int) NativeReflection.call(win, "getDisplayId") == 0;
                });
        bindWallpapers();
        Object area = NativeReflection.call(display, "getDefaultTaskDisplayArea");
        Object task = NativeReflection.call(area, "getFocusedRootTask");
        candidate = task == null ? null : NativeReflection.call(task, "topRunningActivityLocked");
        target = appAllowed() && safeActivity(candidate) ? candidate : null;
        prepareClient(); refresh(); if (target != null) prepareSurfaces();
        handler.removeCallbacks(watch); handler.post(watch); reporter.entry.incrementAndGet();
    }
    private boolean allowed() {
        return !nativeTakeoverConfirmed && !failed && enabled && keyguard != null && lease.active(now())
                && context != null && interactive && !sleepingBarrier
                && ((boolean) invokeKeyguard("isKeyguardShowing") || authenticatedExit()) && !(boolean) invokeKeyguard("isAodShowing")
                && !(boolean) invokeKeyguard("isKeyguardOccluded")
                && (!(boolean) invokeKeyguard("isKeyguardGoingAway") || (clientCanDismiss || presentation.handoff()) && target != null && safeActivity(target));
    }
    /** KeyguardController queries for display 0; a few were renamed or lost the display argument. */
    private Object invokeKeyguard(String name) {
        Method m = NativeReflection.methodOrNull(keyguard.getClass(), name, int.class);
        if (m != null) return NativeReflection.invoke(m, keyguard, 0);
        if (name.equals("isKeyguardOccluded")) {
            m = NativeReflection.methodOrNull(keyguard.getClass(), "isDisplayOccluded", int.class);
            if (m != null) return NativeReflection.invoke(m, keyguard, 0);
        }
        m = NativeReflection.methodOrNull(keyguard.getClass(), name);
        if (m != null) return NativeReflection.invoke(m, keyguard);
        throw new IllegalStateException("KeyguardController#" + name + " missing");
    }
    private boolean coverCommitted() { return mirror != null && mirror.committed(); }
    private boolean appAllowed() {
        // KeyguardController.canDismissKeyguard only covers insecure/trust-agent state; it omits
        // SystemUI's accepted biometric result. The authenticated SystemUI channel carries the
        // real mCanDismissLockScreen snapshot, including fingerprint/face, and never sets it.
        return clientCanDismiss || presentation.handoff() && !(boolean)invokeKeyguard("isKeyguardLocked");
    }
    /** Which term of allowed() is false on this build; diagnostics for a port to an unseen build. */
    private String allowedDetail() {
        try {
            return "lease=" + lease.active(now()) + " takeover=" + nativeTakeoverConfirmed + " enabled=" + enabled
                    + " kg=" + (keyguard != null) + " ctx=" + (context != null) + " interactive=" + interactive
                    + " barrier=" + sleepingBarrier + " showing=" + invokeKeyguard("isKeyguardShowing")
                    + " authExit=" + authenticatedExit() + " aod=" + invokeKeyguard("isAodShowing")
                    + " occluded=" + invokeKeyguard("isKeyguardOccluded")
                    + " goingAway=" + invokeKeyguard("isKeyguardGoingAway")
                    + " target=" + (target != null) + " safe=" + safeActivity(target);
        } catch (Throwable t) { return "detail failed: " + t; }
    }
    private boolean safeActivity(Object activity) {
        if (activity == null || !(boolean) NativeReflection.call(activity, "attachedToProcess")
                || NativeReflection.bool(activity, "finishing") || (int) NativeReflection.call(activity, "getDisplayId") != 0) return false;
        Object atm = NativeReflection.get(keyguard, "mService");
        if (NativeReflection.integer(activity, "mUserId") != (int) NativeReflection.call(atm, "getCurrentUserId")) return false;
        Method find = NativeReflection.method(activity.getClass(), "getWindow", Predicate.class);
        Object secure = NativeReflection.invoke(find, activity, (Predicate<Object>) win -> (boolean) NativeReflection.call(win, "isSecureLocked"));
        Object main = mainWindow(activity);
        // A secure window is excluded from pre-auth preview. Once native auth has actually
        // unlocked it, it remains a valid native return target; never strand an exit cover.
        return (secure == null || presentation.handoff() && !(boolean)invokeKeyguard("isKeyguardLocked")) && main != null;
    }
    private SurfaceControl surface(Object win) { return (SurfaceControl) NativeReflection.call(win, "getSurfaceControl"); }
    private boolean exclusiveLockToken(Object token) {
        if (token == null || !(boolean)NativeReflection.call(token,"canShowWhenLocked")) return false;
        for (Object win : (List<?>)NativeReflection.get(token,"mChildren"))
            for (var frame : producerFrames)
                if ((frame.getInt("which") & 1) != 0 && ((android.os.IInterface)NativeReflection.get(win,"mClient")).asBinder().equals(frame.getBinder("client"))) return false;
        Object controller = NativeReflection.get(NativeReflection.call(root,"getDefaultDisplay"),"mWallpaperController");
        long selected = wallpaperLayer(controller, false);
        for (Object win : (List<?>)NativeReflection.get(token,"mChildren")) if (selected > 0 && layer(surface(win)) == selected) return false;
        return true;
    }
    private void bindWallpapers() {
        // Native going-away changes the wallpaper selection to HOME. The retained live LOCK
        // source belongs to this exit until native ownership is proven; never rebind it to HOME.
        if (presentation.handoff() && lockWindow != null && mirror.source() != null && mirror.source().isValid()) return;
        Object display = NativeReflection.call(root, "getDefaultDisplay");
        Object controller = NativeReflection.get(display, "mWallpaperController");
        long selectedLayer = wallpaperLayer(controller, true);
        Object nextWindow = null, nextToken = null;
        homeTokens.clear();
        StringBuilder tokenJournal = new StringBuilder();
        for (Object token : (List<?>) NativeReflection.get(controller, "mWallpaperTokens")) {
            boolean lockable = (boolean) NativeReflection.call(token, "canShowWhenLocked");
            Object client = null;
            try { client = NativeReflection.call(token, "getWallpaperClient"); } catch (Throwable ignored) { }
            if (client == null) try {
                for (Object win : (List<?>) NativeReflection.get(token, "mChildren")) { client = NativeReflection.get(win, "mClient"); if (client != null) break; }
            } catch (Throwable ignored) { }
            String pkg = "?";
            try { if (client != null) pkg = String.valueOf(NativeReflection.call(NativeReflection.call(client, "getSession"), "getPackage")); } catch (Throwable ignored) { }
            // Which wallpaper clients this module is taking visibility decisions for. This device runs
            // a third-party live wallpaper for HOME, so a HOME token here is not MIUI's engine and
            // can never produce the producer frames the scene waits for.
            if (tokenJournal.length() < 240) tokenJournal.append(pkg).append(lockable ? ":lock" : ":home").append(' ');
            if (!lockable) homeTokens.add(token);
            else for (Object window : (List<?>) NativeReflection.get(token, "mChildren"))
                if (selectedLayer > 0 && layer(surface(window)) == selectedLayer) { nextWindow = window; nextToken = token; }
        }
        wallpaperClients = tokenJournal.length() == 0 ? "none" : tokenJournal.toString().trim();
        if (nextWindow != lockWindow) { protectAndInvalidate(); lockWindow = nextWindow; lockToken = nextToken; }
        boolean exclusive = true;
        if (lockWindow != null) for (var frame : producerFrames)
            if (frame.getInt("which") == 3 && ((android.os.IInterface)NativeReflection.get(lockWindow,"mClient")).asBinder().equals(frame.getBinder("client"))) {
                exclusive = false; if (!homeTokens.contains(lockToken)) homeTokens.add(lockToken);
            }
        long previousMirror = mirror.generation();
        mirror.ensure(lockToken, lease.id(), exclusive);
        if (previousMirror != mirror.generation()) protectAndInvalidate();
        if (lockToken != null) NativeReflection.invoke(wallpaperWindows, lockToken, true);
        if (coverCommitted()) for (Object token : homeTokens) NativeReflection.invoke(wallpaperWindows, token, true);

    }
    /**
     * Layer of the wallpaper the controller presents for lock ({@code true}) or home. MIUI's
     * getWallpaperSurface(boolean) names it; elsewhere the first visible window of a token that
     * does (lock) or does not (home) show when locked.
     */
    private long wallpaperLayer(Object controller, boolean lock) {
        Method named = NativeReflection.methodOrNull(controller.getClass(), "getWallpaperSurface", boolean.class);
        if (named != null) {
            Object target = NativeReflection.invoke(named, controller, lock);
            if (target == null) return -1;
            // The animator's leash is a method on builds that added an accessor and the field
            // itself on builds that did not; either answers the same surface.
            SurfaceControl leash = (SurfaceControl) NativeReflection.optCall(target, "getLeash");
            if (leash == null) leash = (SurfaceControl) NativeReflection.optGet(target, "mLeash");
            return layer(leash);
        }
        for (Object token : (List<?>) NativeReflection.get(controller, "mWallpaperTokens")) {
            if ((boolean) NativeReflection.call(token, "canShowWhenLocked") != lock) continue;
            for (Object window : (List<?>) NativeReflection.get(token, "mChildren")) {
                long id = layer(surface(window));
                if (id > 0) return id;
            }
        }
        return -1;
    }
    private static long layer(SurfaceControl surface) {
        return surface == null || !surface.isValid() ? -1 : ((Number) NativeReflection.call(surface, "getLayerId")).longValue();
    }
    private float[] wallpaperMatrix() {
        if (lockWindow == null || layer(surface(lockWindow)) < 0) return null;
        float sx = NativeReflection.number(lockWindow, "mLastHScale"), sy = NativeReflection.number(lockWindow, "mLastVScale");
        android.graphics.Point position = (android.graphics.Point) NativeReflection.get(lockWindow, "mLastSurfacePosition");
        if (!Float.isFinite(sx) || !Float.isFinite(sy) || sx <= 0 || sy <= 0 || position == null) return null;
        return new float[]{sx, sy, position.x, position.y, 1};
    }
    private void prepareClient() {
        if (target == null || !allowed() || !appAllowed() || !coverCommitted() || !safeActivity(target)) return;
        if (clientActivity != target) {
            restoreClient(); clientActivity = target;
            clientLease.begin((boolean) NativeReflection.call(target, "isClientVisible"));
        }
        clientWriting.set(true);
        try { NativeReflection.invoke(clientVisibility, clientActivity, true); }
        finally { clientWriting.remove(); }
    }
    private void startClientIfStopped() {
        if (!activationPermitted() || now() - lastStartAttempt < 450) return;
        if (!SceneExitPolicy.activateRenderer((boolean) NativeReflection.call(target, "isActivityTypeHome"), revealing)) return;
        String state = String.valueOf(NativeReflection.call(target, "getState"));
        if (state.equals("RESUMED")) return;
        lastStartAttempt = now();
        refresh();
        Object selected = target;
        // The real native lifecycle restores Flutter/app rendering. Override only this selected
        // target's drawing-related sleep query inside its synchronous resume call. Never remove
        // sleep tokens, set keyguard flags, move tasks, or synthesize USER_PRESENT.
        activatingActivity.set(selected);
        String before = state, after = state;
        boolean made = false;
        try {
            Object result = NativeReflection.invoke(NativeReflection.method(selected.getClass(), "makeActiveIfNeeded", selected.getClass()), selected, (Object) null);
            made = Boolean.TRUE.equals(result);
            after = String.valueOf(NativeReflection.call(selected, "getState"));
            if (after.equals("RESUMED")) {
                resumedByScene = selected; resumeAcknowledged = false; resumedBySceneAt = now();
            }
        } finally { activatingActivity.remove(); }
        // Diagnostics for a port to a build nobody here owns: whether forcing the client awake
        // reaches the target at all, and what it changed. Deduplicated by CompatLog.
        try {
            reporter.compat.note("框架场景", "client " + before + "->" + after + " made=" + made
                    + " attached=" + NativeReflection.call(selected, "attachedToProcess")
                    + " visible=" + NativeReflection.call(selected, "isVisible")
                    + " clientVisible=" + NativeReflection.call(selected, "isClientVisible"));
        } catch (Throwable ignored) { }
    }
    private boolean inputOwnedByShade() {
        if (shadeWindow == null || target == null) return false;
        Object display = NativeReflection.get(target, "mDisplayContent");
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NativeReflection.get(shadeWindow, "mAttrs");
        return display != null && NativeReflection.get(display, "mCurrentFocus") == shadeWindow
                && (attrs.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
                && (boolean) NativeReflection.call(shadeWindow, "isVisibleRequested");
    }
    private boolean activationPermitted() {
        return SceneActivation.permitted(allowed(), appAllowed(), coverCommitted(), inputOwnedByShade(), safeActivity(target));
    }
    /**
     * Whether the return target can be asked for a frame.
     *
     * <p>A resume this module forced itself must be acknowledged by the build's own
     * {@code activityResumedLocked} before it counts, which is what {@code resumeAcknowledged}
     * carries. Some builds resume through a path that never raises that signal: measured on OS2,
     * where the forced target reads {@code RESUMED}, is visible and is drawn while
     * {@code resumeAck} stays false. There the acknowledgement gate blocked the draw request
     * forever - {@code drawGate=renderer} - so the scene never became ready, the cover stayed
     * opaque instead of revealing, and a swipe that followed the finger perfectly still showed
     * nothing underneath. The gate is therefore given a grace period: what it protects is only
     * whether a frame may be *asked for*, while the frame's authenticity is decided further down by
     * the client delivery and the presentation fence, which this does not touch.
     */
    private boolean rendererReady() {
        if (target == null || !String.valueOf(NativeReflection.call(target, "getState")).equals("RESUMED"))
            return false;
        if (resumedByScene != target || resumeAcknowledged) return true;
        return now() - resumedBySceneAt >= RESUME_ACK_GRACE_MS;
    }
    /**
     * Whether the draw request may be issued against this return window.
     *
     * <p>{@link #rendererReady()} alone is too strict for a gesture that is still on its way up. This
     * build resumes the return target itself only once the keyguard starts going away, which happens
     * after release, so during the drag the target reads STARTED - and requiring RESUMED there meant
     * no frame was ever requested, {@code drawGate=renderer}, the scene never became ready, and the
     * cover stayed opaque for the whole gesture: the desktop could only appear at the very end. A
     * STARTED activity is still visible and drawing, and nothing here is taken on faith - the request
     * still has to come back through the client delivery and the presentation fence, and a request
     * that is never answered is retried and finally timed out.
     */
    private boolean rendererReady(Object window) {
        if (rendererReady()) return true;
        return revealing && window != null
                && (boolean) NativeReflection.call(window, "isDrawn")
                && (boolean) NativeReflection.call(window, "isVisibleRequested");
    }
    /** How long a forced resume may wait for this build's acknowledgement before being trusted. */
    private static final long RESUME_ACK_GRACE_MS = 300;
    private void restoreLifecycle() {
        Object old = resumedByScene; resumedByScene = null; resumeAcknowledged = false;
        if (old == null) return;
        boolean resumed = String.valueOf(NativeReflection.call(old, "getState")).equals("RESUMED");
        if (!SceneActivation.pauseOnRelease(true, resumed, (boolean) invokeKeyguard("isKeyguardShowing"),
                (boolean) invokeKeyguard("isKeyguardGoingAway"))) return;
        Object fragment = NativeReflection.call(old, "getTaskFragment");
        if (fragment != null && NativeReflection.get(fragment, "mResumedActivity") == old)
            NativeReflection.invoke(NativeReflection.method(typeUnchecked("TaskFragment"), "startPausing", boolean.class, boolean.class,
                    typeUnchecked("ActivityRecord"), String.class), fragment, false, true, null, "Flyme scene cancelled");
    }
    private Class<?> typeUnchecked(String name) {
        try { return type(name); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
    }
    private void restoreClient() { restoreClient(false); }
    private void restoreClient(boolean handoff) {
        restoreLifecycle();
        Object old = clientActivity; clientActivity = null;
        if (!clientLease.active()) return;
        if (handoff) { clientLease.handoff(); return; }
        boolean wanted = clientLease.end();
        clientWriting.set(true);
        // Actual unlock already owns this visible client. Replaying an earlier hidden intent
        // here pauses Flutter and consumes USER_PRESENT while HOME is temporarily invisible.
        try { if (old != null) NativeReflection.invoke(clientVisibility, old, wanted); }
        finally { clientWriting.remove(); }
    }
    private void refresh() {
        Class<?>[] params = ensureVisible.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) args[i] = params[i] == int.class ? 0 : params[i] == boolean.class ? (Object) Boolean.FALSE : null;
        // Older overloads: (starting=null, configChanges=0, preserveWindows=false, notifyClients=true).
        if (params.length == 4) args[3] = Boolean.TRUE;
        NativeReflection.invoke(ensureVisible, root, args); NativeReflection.call(wm, "requestTraversal");
    }
    private Object targetWindow() {
        return target == null ? null : mainWindow(target);
    }
    private Object mainWindow(Object activity) {
        int uid = ((Number) NativeReflection.call(activity, "getUid")).intValue();
        return NativeReflection.invoke(NativeReflection.method(activity.getClass(), "getWindow", Predicate.class), activity,
                (Predicate<Object>) win -> {
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) NativeReflection.get(win, "mAttrs");
                    return ReturnWindowPolicy.main(attrs.type, NativeReflection.integer(win, "mOwnerUid"), uid, attrs.alpha);
                });
    }
    private SurfaceControl clientSurface(Object window){return window==null?null:(SurfaceControl)NativeReflection.optCall(window,"getClientViewRootSurface");}
    /** Which route the last buffer lookup took, appended to the readiness line. */
    private volatile String surfacePath = " path=unresolved";
    private SurfaceControl bufferSurface(Object window) {
        if(window==null){ surfacePath=" path=no-window"; return null; }
        // Android 17 may put the client BLAST buffer beneath the server WindowState surface.
        // WMS hides the server container in that mode. Never lease or restore client-owned
        // buffer visibility: the app retains authority over its child surface lifecycle.
        // Android 17's client-surface mode; absent on older builds, where the server owns the buffer layer.
        Method clientSurface = NativeReflection.methodOrNull(WindowManager.class, "useClientSurface");
        boolean client = clientSurface != null && (boolean) NativeReflection.invoke(clientSurface, null);
        if (client) {
            SurfaceControl held = surface(window);
            // Reported only: which route this build takes and whether it produced a usable layer.
            surfacePath = " path=client method=" + (clientSurface != null)
                    + " surface=" + (held != null) + " valid=" + (held != null && held.isValid());
            return held;
        }
        Object animator = NativeReflection.get(window, "mWinAnimator");
        SurfaceControl held = animatorSurface(animator);
        // A build where the client owns its surface root leaves the animator without a buffer
        // layer, and the window itself holds the client's view root instead. Only consulted when
        // the animator produced nothing, so a build that has one is unaffected.
        if (held == null) held = clientRootSurface(window);
        surfacePath = " path=animator method=" + (clientSurface != null)
                + " animator=" + (animator != null) + " surface=" + (held != null)
                + " valid=" + (held != null && held.isValid());
        return held;
    }
    /**
     * The SurfaceControl a window's animator owns. Builds with the client-surface refactor keep it
     * in a field of its own; in the shape before that the animator holds a WindowSurfaceController
     * and exposes the same control through itself, which is what is used when the field is absent.
     */
    private static SurfaceControl animatorSurface(Object animator) {
        if (animator == null) return null;
        SurfaceControl held = (SurfaceControl) NativeReflection.optGet(animator, "mSurfaceControl");
        return held != null ? held : (SurfaceControl) NativeReflection.optCall(animator, "getSurfaceControl");
    }
    /**
     * The client's own view-root layer. Builds after the client-surface refactor keep the app's
     * buffer here rather than on the server-side animator, which then has nothing to hand out.
     */
    private static SurfaceControl clientRootSurface(Object window) {
        for (String name : new String[]{"getClientViewRootSurface", "getClientSurface", "getSurfaceControl"}) {
            Object held = NativeReflection.optCall(window, name);
            if (held instanceof SurfaceControl && ((SurfaceControl) held).isValid()) return (SurfaceControl) held;
        }
        return null;
    }
    private void prepareSurfaces() {
        if (!allowed() || !appAllowed() || !coverCommitted() || !safeActivity(target)) return;
        Object window = targetWindow(); if (window == null) return;
        adoptWindowChain(window);
        // Reveal only this authenticated return window. Native task focus, auth and input flags
        // are unchanged. Container/leash visibility is leased and restored to native intent.
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            surfaces.promoteChain(bufferSurface(window),
                    (boolean) NativeReflection.call(NativeReflection.get(window, "mWinAnimator"), "getShown"), t);
            t.apply();
        }
    }
    private void prepareLockProducerSurfaces() {
        if (!allowed() || !appAllowed() || !coverCommitted() || lockWindow == null) return;
        adoptWindowChain(lockWindow);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            surfaces.promoteChain(bufferSurface(lockWindow),
                    (boolean)NativeReflection.call(NativeReflection.get(lockWindow,"mWinAnimator"),"getShown"),t);
            t.apply();
        }
    }
    private void prepareHomeWallpaperSurfaces() {
        if (!allowed() || !appAllowed() || !coverCommitted() || target == null) return;
        // Engine swap alone is insufficient: the actual home wallpaper containers must be
        // composited below the return task. Borrow their latest native visibility, no geometry.
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            for (Object token : homeTokens) for (Object window : (List<?>) NativeReflection.get(token, "mChildren")) {
                adoptWindowChain(window);
                surfaces.promoteChain(bufferSurface(window),
                        (boolean) NativeReflection.call(NativeReflection.get(window, "mWinAnimator"), "getShown"), t);
            }
            t.apply();
        }
    }
    private void adoptWindowChain(Object window) {
        // WindowContainer may replace a Java handle with copyFrom while the layer ID stays live.
        // Refresh handle ownership only; visibility is still established by committed transactions.
        surfaces.adopt(bufferSurface(window));
        for (Object current = window; current != null; current = NativeReflection.call(current, "getParent")) {
            if (current.getClass().getSimpleName().equals("RootWindowContainer")) break;
            surfaces.adopt((SurfaceControl) NativeReflection.call(current, "getSurfaceControl"));
        }
    }
    private void publishScene() {
        Object window = targetWindow();
        // Native control must not wait for the frame that native unlock will produce.
        if(nativeExitRequested && clientCanDismiss && coverCommitted()) presentation.prepareNativeExit(lease.id());
        if (!nativeTakeoverConfirmed) ensureReturnProof(window);
        boolean home = target != null && (boolean) NativeReflection.call(target, "isActivityTypeHome");
        if(lateHome!=null)lateHome.arm(lease.id(),home?((Number)NativeReflection.call(target,"getUid")).intValue():-1,lateDesktopRequested&&clientCanDismiss&&home);
        boolean drawn = window != null && (boolean) NativeReflection.call(window, "isDrawn");
        boolean currentFrame = window != null && presentation.readyFor(lease.id(), identity(window), layer(bufferSurface(window)));
        boolean visible = window != null && surfaces.shown(bufferSurface(window))
                && (boolean) NativeReflection.call(window, "isVisibleRequested");
        boolean parents = window != null && ancestorsVisible(window);
        lockGeneration = producerGeneration(lockWindow, 2); lockEngineDrawn = lockGeneration > 0;
        homeGeneration = 0; homeEngineDrawn = false;
        mirror.detachWhenReady(appAllowed(), lockEngineDrawn, () -> guarded(this::publishScene));
        boolean valid = mirror.ready() && lockWindow != null && surface(lockWindow) != null && surface(lockWindow).isValid()
                && lockEngineDrawn;
        boolean homeWindowDrawn = false;
        SurfaceControl fallbackSource = null;
        long fallbackGeneration = -1;
        for (Object token : homeTokens) {
            for (Object win : (List<?>) NativeReflection.get(token, "mChildren")) {
                long producer = producerGeneration(win, 1);
                homeGeneration = Math.max(homeGeneration, producer); homeEngineDrawn |= producer > 0;
                // The current engine's actual EGL draw proves wallpaper content. Its WMS
                // animator may still wait for native unlock; it is not the engine producer.
                homeWindowDrawn |= producer > 0 && surfaces.shown(bufferSurface(win))
                        && ancestorsVisible(win);
                if (producer > fallbackGeneration && producer > 0 && surfaces.shown(bufferSurface(win)) && ancestorsVisible(win)) {
                    SurfaceControl candidate = (SurfaceControl) NativeReflection.call(token, "getSurfaceControl");
                    if (candidate != null && candidate.isValid()) { fallbackSource = candidate; fallbackGeneration = producer; }
                }
            }
        }
        // Only a real HOME producer layer may serve as the desktop backdrop. The lock producer's
        // layer is NOT usable here: the mirror leases it through copyFrom and releases it on
        // restore, so handing it over as a long-lived backdrop left the cover believing the desktop
        // was covered while the underlying layer had already gone - the desktop came up blank.
        // A backdrop must be a layer that really sits BELOW the return window. The lock wallpaper
        // token does not: it belongs to the keyguard container, which is above the launcher, so
        // handing it to useNativeHome left the desktop showing nothing but wallpaper with every icon
        // hidden, and a black screen after the display had been off a while. The HOME producer frame
        // is the only layer this may use, and this build has none while the keyguard covers it, so
        // there is deliberately no fallback here.
        // This proves only a live HOME wallpaper, never the selected app/launcher's frame.
        if (appAllowed() && coverCommitted() && fallbackSource != null && cover != null) {
            if (home) cover.useNativeHome(fallbackSource, fallbackGeneration);
            else {
                cover.requireLockProtection();
                if (!(currentFrame && rendererReady(window) && visible && parents)) cover.prepareHome(fallbackSource, fallbackGeneration);
            }
        }
        SceneReadiness.Target kind = target == null ? SceneReadiness.Target.NONE
                : home ? SceneReadiness.Target.HOME : SceneReadiness.Target.APP;
        // A HOME return needs a wallpaper behind it. The desktop engine cannot have drawn while the
        // keyguard still covers it - the device measures DesktopImageEngineImpl valid=false for the
        // whole gesture - so the live lock producer is accepted as that evidence instead. On this
        // build both roles share one wallpaper client (roles=3), so it is the same image. Requiring
        // the desktop engine here made the quorum unsatisfiable for an entire slow drag, which is
        // what turned "swipe up to unlock" into a five second wait followed by a rebound.
        boolean homeWallpaper = homeEngineDrawn && homeWindowDrawn
                || kind == SceneReadiness.Target.HOME && lockEngineDrawn;
        String reason = !appAllowed() ? "等待原生认证资格" : target == null ? "返回目标未允许（安全窗口/进程/用户限制）"
                : !visible ? "返回窗口未显示" : !parents ? "返回窗口祖先层未显示"
                : !rendererReady(window) ? "等待返回目标原生 RESUME 回执" : !currentFrame ? "等待本绑定新帧呈现 fence" : !presentation.handoff() && !inputOwnedByShade() ? "等待 SystemUI 保持输入焦点"
                : !mirror.committed() ? "等待锁屏镜像首帧提交" : !mirror.ready() ? "等待原始锁屏容器脱离合成"
                : !valid ? "等待锁屏引擎本会话 EGL swap" : !homeWallpaper
                ? "等待桌面引擎与窗口绘制" : "返回目标与绘制链就绪";
        long windowGeneration = window == null || bufferSurface(window) == null ? 0
                : ((Number) NativeReflection.call(bufferSurface(window), "getLayerId")).longValue();
        SceneReadiness state = new SceneReadiness(lease.id(), kind, windowGeneration,
                appAllowed(), visible, parents, currentFrame && rendererReady(window) && (presentation.handoff() || inputOwnedByShade()), homeWallpaper, valid, reason,
                identity(window), presentation.epoch(), presentation.drawSequence(), currentFrame);
        boolean ready = state.ready();
        boolean nativeFocused = window != null && NativeReflection.get(NativeReflection.get(window, "mDisplayContent"), "mCurrentFocus") == window;
        nativeTakeoverConfirmed |= SceneExitPolicy.nativeTakeover(presentation.handoff(),
                (boolean)invokeKeyguard("isKeyguardShowing"), (boolean)invokeKeyguard("isKeyguardLocked"),
                (boolean)invokeKeyguard("isKeyguardGoingAway"), presentation.foregroundHiddenCommitted(), rendererReady(),
                nativeFocused, drawn, window != null && (boolean)NativeReflection.call(window,"isVisibleRequested")
                    && surfaces.nativeAncestorsShown(bufferSurface(window)), parents);
        // The cover belongs to a live scene. publishScene is also invoked from late callbacks and
        // refreshes after the lease has been cleared, and the "else protect" branch below used to
        // light the cover there - one more way a wallpaper mirror ended up at alpha 1 over the
        // desktop (only-wallpaper, control panel hidden, swipe unresponsive) long after the unlock.
        // A cleared lease must never touch the cover; retirement is the only remaining actor.
        if (lease.id() < 0) {
            unreadySince = 0;
        } else if (ready || nativeTakeoverConfirmed) {
            unreadySince = 0;
            cover.reveal(true);
            if (ready && cover.exposureComplete() && presentation.handoff() && !(boolean)invokeKeyguard("isKeyguardLocked"))
                presentation.nativeOwned(surfaces.nativeAncestorsShown(bufferSurface(window)), parents);
        } else if (cover != null) {
            // Debounced re-cover. A transient readiness dip (the return window's fence and surface
            // change while the finger is down) must not flash the square cover back over the sheet's
            // rounded edge - that was the "corner turns square 2-3 times" flicker. But a scene that
            // stays un-presentable must re-cover, or the transparent cover leaves the blank return
            // window showing "only the wallpaper" until a sleep/wake resets it. Only a persistent
            // !ready crosses the threshold.
            long now = SystemClock.uptimeMillis();
            if (unreadySince == 0) unreadySince = now;
            else if (now - unreadySince >= COVER_RECOVER_MS) { unreadySince = 0; cover.protect(); }
        }
        // The cover's own timeline. "透图没出来" and "透图出来得太晚" are different defects with
        // different fixes, and the readiness line alone cannot tell them apart because it only
        // ever shows the state at the moment it was sampled.
        noteCover(ready, visible, parents, window != null && (boolean)NativeReflection.call(window, "isVisibleRequested"));
        reporter.returnTarget = kind.name(); reporter.surfaceGeneration = state.surfaceGeneration();
        reporter.sceneReadiness = "session=" + lease.id() + " target=" + kind + " generation=" + state.surfaceGeneration()
                + " phase=" + presentation.phase() + " binding=" + presentation.epoch() + " drawSeq=" + presentation.drawSequence() + " presented=" + presentation.ready() + " proof=" + presentation.proofSource()
                + " visible=" + visible + " ancestors=" + parents + " drawn=" + drawn
                + " currentFrame=" + currentFrame + " homeComposited=" + homeWindowDrawn
                + " backdrop=" + (cover != null && cover.backdropReady()) + " blended=" + (cover != null && cover.exposureComplete())
                + " nativeTakeover=" + nativeTakeoverConfirmed + " focused=" + nativeFocused
                + " blocker=" + (window == null ? "no-window" : surfaces.blocker(bufferSurface(window))) + surfacePath
                + " homeSwap=" + homeEngineDrawn + " lockSwap=" + lockEngineDrawn + " ready=" + ready
                + " client=" + (target != null && (boolean) NativeReflection.call(target, "isClientVisible"))
                + " activity=" + (target == null ? "NONE" : NativeReflection.call(target, "getState"))
                + " startOnly=" + reporter.startOnly.get()
                + " rendererResumed=" + rendererReady() + " resumeAck=" + resumeAcknowledged
                + " ackWait=" + (resumedByScene != null && !resumeAcknowledged ? now() - resumedBySceneAt : -1)
                + " inputShade=" + inputOwnedByShade()
                + " revealing=" + revealing
                + " coverCommitted=" + coverCommitted()
                + " mirrorGeneration=" + mirror.generation() + " mirrorCommitted=" + mirror.committed() + " sourceDetached=" + mirror.ready()
                + " windowVis=" + (window == null ? -1 : NativeReflection.integer(window, "mViewVisibility"))
                + " lockLayer=" + (lockWindow == null ? -1 : layer(surface(lockWindow)))
                + " lockBinding=" + producerBindingDetail(lockWindow,2)
                + " draw=" + drawDiagnostics()
                + " baseline=" + java.util.Arrays.toString(wallpaperMatrix());
        reporter.liveScene = reason;
        // The readiness line is capped at 2048 chars on the way out, so the fields this scene is
        // actually decided by are also published on their own short line.
        reporter.diag = "phase=" + presentation.phase() + " drawSeq=" + presentation.drawSequence()
                + " ready=" + ready + " missing=" + state.missing()
                + " " + (cover == null ? "cover=absent" : cover.revealState())
                + " backdrop=" + (cover == null ? "absent" : cover.backdropState())
                + " cover=[" + coverJournal + "]"
+ " clients=" + wallpaperClients
                + " deferHold=" + (!revealing && !presentation.handoff())
                + " drawGate=" + (drawGate == null ? "requested" : drawGate)
                + " fence=" + fences.receipt()
                + " drawReq=" + drawRequests + " complete=" + drawCompletions
                + " mirror=" + mirror.generation() + "/" + mirror.committed() + "/" + mirror.ready()
                + " lockGen=" + lockGeneration
                + " binding=" + presentation.epoch() + " presented=" + presentation.ready()
                + " proof=" + presentation.proofSource()
                + " drawnRefusal=" + presentation.drawnRefusal()
                + " hazards=" + hazards.size() + " lastDraw=" + lastDrawReceipt
                + " (" + reason + ")";
        // Preserve HOME's actual blocker after a later APP scene or END replaces the live row.
        if (home) reporter.lastHomeReadiness = reporter.sceneReadiness + " reason=" + reason;
        if (!reporter.sceneReadiness.equals(lastStatus) || ready != lastReady) {
            lastStatus = reporter.sceneReadiness; lastReady = ready;
            reply(lease.id(), ready, reporter.liveScene); reporter.publish();
        }
    }
    private boolean nativeUnlockStarted() {
        return keyguard != null && ((boolean)invokeKeyguard("isKeyguardGoingAway") || !(boolean)invokeKeyguard("isKeyguardLocked"));
    }
    private boolean authenticatedExit() {
        if ((!clientCanDismiss && !presentation.handoff()) || target == null || !safeActivity(target)) return false;
        boolean nativeExit = (boolean)invokeKeyguard("isKeyguardGoingAway") || !(boolean)invokeKeyguard("isKeyguardLocked");
        if (nativeExit && presentation.ready()) presentation.beginHandoff(presentation.epoch());
        return nativeExit && presentation.handoff();
    }
    private static long identity(Object window) { return window == null ? 0 : Integer.toUnsignedLong(System.identityHashCode(window)); }
    private void surfaceHazard(SurfaceControl.Transaction transaction) {
        if (nativeTakeoverConfirmed) return;
        if (cover == null || !cover.bound() || lease.id() < 0) return;
        cover.protect(transaction);
        // A hazard means the proof in hand is stale and a new one must be taken. Taking that decision
        // again while the replacement is still in flight destroys the replacement too. This device
        // measured exactly that: 468 requests with 461 completions and a binding epoch of 1439, where
        // a fence landed and the epoch was bumped in the same breath, so presented() never saw a
        // matching binding, the scene never became ready during the gesture, and the cover - which
        // only reveals on ready - stayed opaque until the churn stopped at release. That is why the
        // desktop appeared only at the end of the swipe. One invalidation per quiet window is enough;
        // a proof taken across a real topology change is still refused downstream, because
        // presentation.matches re-checks the selected window identity and its layer.
        if (now() - lastDrawRequestAt < HAZARD_QUIET_MS) return;
        // Do not issue the next draw against the pre-commit topology of this destructive txn.
        synchronized (hazards) { if (!hazards.add(transaction)) return; }
        presentation.invalidate(); drawWindow = null;
        long id = lease.id();
        transaction.addTransactionCommittedListener(handler::post, () -> guarded(() -> {
            synchronized (hazards) { hazards.remove(transaction); }
            if (id != lease.id()) return;
            if (now() - lastDrawRequestAt < HAZARD_QUIET_MS) { publishScene(); return; }
            presentation.invalidate(); drawWindow = null; publishScene();
        }));
    }
    /** How long an issued draw request is left alone so its fence can land; see surfaceHazard. */
    private static final long HAZARD_QUIET_MS = 150;
    private long lastDrawRequestAt;
    private void protectAndInvalidate() {
        if (cover != null) cover.protect(); presentation.invalidate(); drawWindow = null;
    }
    /**
     * True once a handoff has been held longer than any terminal receipt is worth waiting for.
     * See {@link #stop(String, boolean)}: the refusal it guards would otherwise never lift.
     */
    private boolean handoffHoldExpired() {
        long now = now();
        if (handoffHoldSince == 0) { handoffHoldSince = now; return false; }
        return now - handoffHoldSince >= HANDOFF_HOLD_MAX_MS;
    }
    private static final long HANDOFF_HOLD_MAX_MS = 1200;
    private long handoffHoldSince;
    private void ensureReturnProof(Object window) {
        synchronized (hazards) { if (!hazards.isEmpty()) { drawGate="hazard"; return; } }
        // The gates are named rather than inferred: this is where the scene's draw proof starts,
        // and a miss here leaves drawSeq=-1, which reads as "waiting" everywhere downstream.
        drawGate = window == null ? "window"
                : !allowed() ? "allowed"
                : !appAllowed() ? "appAllowed"
                : !coverCommitted() ? "cover"
                : !rendererReady(window) ? "renderer"
                : cover == null ? "no-cover"
                : !cover.bound() ? "cover-unbound" : null;
        if (drawGate != null) return;
        SurfaceControl surface = bufferSurface(window); long layer = layer(surface),producerLayer=layer(clientSurface(window));
        if (producerLayer <= 0) { drawGate="no-producer-layer"; return; }
        if (layer <= 0) { drawGate="no-buffer-layer"; return; }
        if (!(boolean)NativeReflection.call(window,"isVisibleRequested")) { drawGate="not-visible-requested"; return; }
        if (drawWindow == window && drawLayer == layer && drawProducerLayer==producerLayer) { drawGate="already-requested"; return; }
        if (cover != null) cover.protect();
        drawWindow = window; drawLayer = layer; drawProducerLayer=producerLayer;
        long id = lease.id(), windowId = identity(window);
        if (retrySession!=id || retryWindow!=windowId || retrySurface!=layer) {
            retrySession=id;retryWindow=windowId;retrySurface=layer;retryCount=0;
        }
        // Draw-handler consumption runs inside the *real* finishDrawing call; the native 5s
        // timeout consumes the same handler with a temporary transaction and must not qualify.
        long epoch = presentation.bind(id,windowId,layer,0);
        // WindowToken.setClientVisible(true) may no-op when the server cache is already
        // true. Reassert visibility for this one real IWindow before requesting its frame.
        NativeReflection.call(window,"sendAppVisibilityToClients");
        java.util.function.Consumer<SurfaceControl.Transaction> draw = t -> {
            drawHandlers++;
            var delivery = clientDraw.get();
            // Consume the draw proof at this exact handler. Native finishDrawing may drop
            // stale buffers or change topology before it reaches executeDrawHandlers.
            if (delivery == null) { drawTimeouts++; recordDrawReceipt("handler-without-client-delivery"); retryDraw(id,epoch,windowId,layer); return; }
            if (!delivery.consume(presentation,id,epoch,windowId,layer)) {
                drawRejected++; recordDrawReceipt("rejected seq="+delivery.sequence()+" buffer="+delivery.bufferTransaction()); retryDraw(id,epoch,windowId,layer); return;
            }
            recordDrawReceipt("awaiting-completion seq="+delivery.sequence());
            long sequence = delivery.sequence();
            fences.listen(t,(latched,signalled) -> handler.post(() -> guarded(() -> {
                if (!presentation.matches(id,epoch,identity(targetWindow()),layer(bufferSurface(window)))) return;
                drawCompletions++;
                if(presentation.ready())return;
                recordDrawReceipt("seq="+sequence+" latch="+latched+" fence="+signalled);
                if (presentation.presented(epoch,sequence,latched,signalled)) publishScene();
                else {
                    reporter.sceneReadiness = "新绘制未取得有效呈现回执，保持完整覆盖：latch=" + latched + " fence=" + signalled;
                    reporter.liveScene = reporter.sceneReadiness; reporter.publish();
                    retryDraw(id,epoch,windowId,layer);
                }
            })));
        };
        NativeReflection.invoke(NativeReflection.method(window.getClass(),"applyWithNextDraw",java.util.function.Consumer.class),window,draw);
        expectedDrawSequence=NativeReflection.integer(window,NativeReflection.optBool(wm,"mAlwaysSeqId",false) && NativeReflection.has(window,"mBufferSeqId") ? "mBufferSeqId" : "mSyncSeqId");
        presentation.expect(epoch,expectedDrawSequence); drawRequests++;
        // From here until this attempt resolves, hazards leave it alone; see surfaceHazard.
        lastDrawRequestAt = now();
        // requestRedrawForSync only clears its reporting marker; reportResized drives the real client request.
        NativeReflection.optCall(window,"requestRedrawForSync"); NativeReflection.optCall(window,"forceReportingResized");
        NativeReflection.call(window,"reportResized");
        // WindowState.reportResized only queues WindowStateResizeItem while native layout
        // is scheduled/deferred. This independent, already-covered preparation runs outside
        // the native placement batch. Send the selected client's queued request now rather
        // than waiting on the very first draw that request is supposed to produce.
        Object lifecycle = NativeReflection.call(NativeReflection.get(wm,"mAtmService"),"getLifecycleManager");
        Object client = NativeReflection.call(NativeReflection.call(window,"getProcess"),"getThread");
        if (drawDispatcher.dispatch(lifecycle,client)) queuedDrawDispatches++;
        NativeReflection.call(wm,"requestTraversal");
    }
    private String drawDiagnostics() {
        return "requests:"+drawRequests+"/queuedSent:"+queuedDrawDispatches+"/clients:"+clientDrawDeliveries+"/handlers:"+drawHandlers+"/timeouts:"+drawTimeouts
                +"/null:"+drawNullBuffers+"/reject:"+drawRejected+"/complete:"+drawCompletions+"/expected:"+expectedDrawSequence
                +"/clientLayer:"+drawProducerLayer+"/received:"+lastDeliveredSequence+"/preserved:"+preservedBufferRequests+"/retry:"+retriedDrawRequests+"/last:"+lastDrawReceipt+"/journal:"+drawJournal
                +"/"+fences.receipt();
    }
    private void recordDrawReceipt(String receipt) {
        lastDrawReceipt=receipt;
        if(drawJournal.size()==4)drawJournal.removeFirst();drawJournal.addLast("b"+presentation.epoch()+":"+receipt);
    }
    private void retryDraw(long id,long epoch,long windowId,long surfaceId) {
        // The handler is one-shot even for null/no-buffer deliveries. Leaving drawWindow
        // set would turn that failure into a permanent wait. Retry a bounded number of
        // times on the WM handler, after native finishDrawing has returned, never recurse
        // into it and never relax the real-buffer/presentation proof.
        handler.post(() -> guarded(() -> {
            Object selected=targetWindow();
            if(!presentation.matches(id,epoch,windowId,surfaceId) || presentation.ready()
                    || identity(selected)!=windowId || layer(bufferSurface(selected))!=surfaceId || retryCount>=2) return;
            retryCount++;retriedDrawRequests++;presentation.invalidate();drawWindow=null;
            ensureReturnProof(selected);publishScene();
        }));
    }
    private boolean ancestorsVisible(Object window) {
        // Check the whole native container chain, not just WindowStateAnimator.getShown().
        if (!surfaces.ancestorsShown(bufferSurface(window))) return false;
        for (Object parent = NativeReflection.call(window, "getParent"); parent != null;
                parent = NativeReflection.call(parent, "getParent")) {
            if (parent.getClass().getSimpleName().equals("RootWindowContainer")) break;
            SurfaceControl control = (SurfaceControl) NativeReflection.call(parent, "getSurfaceControl");
            if (control != null && !control.isValid()) return false;
            if (control != null && !surfaces.shown(control)) return false;
        }
        return true;
    }
    private long producerGeneration(Object window, int which) {
        if (window == null) return 0;
        android.os.IBinder client = ((android.os.IInterface) NativeReflection.get(window, "mClient")).asBinder();
        // With useClientSurface, Engine.mSurfaceControl is a distinct client-owned root
        // registered by WindowState.setClientSurface in mWinAnimator.mSurfaceControl.
        // WindowState.getSurfaceControl is its server parent; mBbqSurfaceControl is its
        // BLAST child. Neither equals the engine root. Matching those two different
        // levels prevented the mirror being sent at all and deadlocked scene preparation.
        SurfaceControl registeredRoot = animatorSurface(NativeReflection.get(window,"mWinAnimator"));
        long generation = 0;
        for (var frame : producerFrames)
            if (com.zhuanz.flymemotion.core.WallpaperFrameBinding.matches(frame.getInt("which"),which,
                    client.equals(frame.getBinder("client")),frame.getBoolean("drawn"),frame.getLong("drawSequence",0),
                    frame.getLong("generation"),frame.getInt("containerLayer",-1),frame.getInt("bufferLayer",-1),layer(registeredRoot)))
                generation = Math.max(generation, frame.getLong("generation"));
        return generation;
    }
    private String producerBindingDetail(Object window, int which) {
        if (window == null) return "no-window";
        SurfaceControl clientRoot = animatorSurface(NativeReflection.get(window,"mWinAnimator"));
        android.os.IBinder client = ((android.os.IInterface)NativeReflection.get(window,"mClient")).asBinder();
        StringBuilder detail = new StringBuilder("server:").append(layer(surface(window))).append("/client:").append(layer(clientRoot))
                .append("/clientParent:").append(parentLayer(clientRoot))
                .append("/status:").append(producerStatusSeen).append("+").append(producerStatusDropped)
                .append("/frames:").append(producerFrames.size());
        for (var frame : producerFrames) if ((frame.getInt("which") & which) != 0)
            detail.append("/engine:").append(frame.getInt("containerLayer",-1)).append("/sameWindow:")
                    .append(client.equals(frame.getBinder("client"))).append("/fresh:").append(frame.getBoolean("drawn"))
                    .append("/raw:seq=").append(frame.getLong("drawSequence",0)).append("/base=").append(frame.getLong("baseline",-1))
                    .append("/gen=").append(frame.getLong("generation")).append("/vis=").append(frame.getBoolean("visible"))
                    .append("/why:")
                    .append(com.zhuanz.flymemotion.core.WallpaperFrameBinding.explain(frame.getInt("which"),which,
                            client.equals(frame.getBinder("client")),frame.getBoolean("drawn"),frame.getLong("drawSequence",0),
                            frame.getLong("generation"),frame.getInt("containerLayer",-1),frame.getInt("bufferLayer",-1),layer(clientRoot)));
        return detail.toString();
    }
    /** The layer a surface sits under, for reading a client root's real place in the tree. */
    private static long parentLayer(SurfaceControl surface) {
        return surface == null ? -1 : layer((SurfaceControl) NativeReflection.optCall(surface, "getParent"));
    }
    /** Timeline of the cover's reveal decision, newest last; see the call site. */
    private void noteCover(boolean ready, boolean visible, boolean parents, boolean visibleRequested) {
        String state = (ready ? "ready" : "waiting") + "/visible=" + visible + "/parents=" + parents
                + "/visReq=" + visibleRequested + "/blend=" + (cover != null && cover.exposureComplete())
                + "/backdrop=" + (cover != null && cover.backdropReady());
        if (state.equals(lastCoverState)) return;
        lastCoverState = state;
        if (coverJournal.size() >= 6) coverJournal.removeFirst();
        coverJournal.addLast((now() % 100000) + " " + state);
    }
    private String lastCoverState;
    private final java.util.ArrayDeque<String> coverJournal = new java.util.ArrayDeque<>();
    private void reply(long id, boolean ready, String status) {
        if (context == null || !registered || id < 0) return;
        String returnTarget = reporter.returnTarget, readiness = reporter.sceneReadiness;
        String animationState=reporter.desktopAnimationState, animationDetail=reporter.desktopAnimationDetail;
        boolean animationSupported=lateHome!=null&&lateHome.supported();
        long generation = reporter.surfaceGeneration;
        long wallpaperLayer = lockWindow == null ? -1 : layer(surface(lockWindow));
        float[] matrix = wallpaperMatrix();
        // Never replace the old full guard/UI mirror with a new producer that has no current
        // buffer. Surface-generation and fresh engine draw are checked before delivery.
        SurfaceControl liveMirror = mirror == null || producerGeneration(lockWindow,2) <= 0 ? null : mirror.surface();
        long mirrorGeneration = mirror == null ? -1 : mirror.generation();
        boolean restored = lease.id() != id;
        long sequence = ++statusSequence, epoch = presentation.epoch(), draw = presentation.drawSequence();
        long windowId = identity(targetWindow());
        String phase = presentation.phase().name(); boolean presented = presentation.ready();
        long exitRequest=nativeExitRequest;
        boolean exitPrepared=nativeExitRequested && presentation.handoff() && coverCommitted() && clientCanDismiss;
        boolean nativeTakeover = nativeTakeoverConfirmed && cover != null && cover.exposureComplete();
        // Replacing the independent LOCK mirror does not destroy a committed HOME backdrop.
        boolean backdropReady = !restored && appAllowed() && cover != null && cover.bound() && cover.backdropReady();
        long backdropGeneration = backdropReady ? cover.backdropGeneration() : -1;
        long backdropLayer = backdropReady ? cover.backdropLayer() : -1;
        // Leave the WM global lock before entering ActivityManager's broadcast service.
        handler.post(() -> {
            if (!restored && (!lease.active(now()) || id != lease.id())) return;
            try {
                context.sendBroadcast(new Intent(NativeSceneChannel.STATUS).setPackage("com.android.systemui")
                        .putExtra("lateDesktopSupported",animationSupported).putExtra("desktopAnimationState",animationState).putExtra("desktopAnimationDetail",animationDetail)
                        .putExtra("frameworkVersion", com.zhuanz.flymemotion.TargetBuild.MODULE_VERSION)
                        .putExtra("token", id).putExtra("ready", ready).putExtra("status", status)
                        .putExtra("target", returnTarget).putExtra("generation", generation)
                        .putExtra("statusSequence",sequence).putExtra("bindingEpoch",epoch).putExtra("windowIdentity",windowId)
                        .putExtra("drawSequence",draw).putExtra("framePresented",presented).putExtra("phase",phase)
                        .putExtra("exitRequest",exitRequest).putExtra("nativeExitPrepared",exitPrepared)
                        .putExtra("nativeTakeover", nativeTakeover)
                        .putExtra("backdropReady", backdropReady).putExtra("backdropGeneration", backdropGeneration).putExtra("backdropLayer", backdropLayer)
                        .putExtra("wallpaperLayer", wallpaperLayer).putExtra("wallpaperMatrix", matrix)
                        .putExtra("readiness", readiness).putExtra("restored", restored)
                        .putExtra("mirrorGeneration", mirrorGeneration)
                        .putExtra("mirror", !restored && liveMirror != null && liveMirror.isValid() ? liveMirror : null), null,
                        BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            } catch (RuntimeException e) { android.util.Log.w("FlymeMotion", "Scene status delivery unavailable", e); }
        });
    }
    /**
     * Going-away to HOME proves authentication. Hide the LOCK wallpaper for the scene in flight,
     * or for a HOME scene that a fast swipe ended moments ago. Returns the hidden token surface.
     */
    private SurfaceControl retireLockForHomeExit(String tag) {
        boolean live=lease.id()>=0;
        // A swipe can be rolled back (target not yet allowed, LOCK not yet bound) moments before
        // native unlock; still ours to cover if it ended within 1.5 s.
        if(!live && now()-endedAt>1500) return null;
        // Going-away is not by itself proof that the unlock happened. On this build MIUI raises it
        // during the drag, before release, so retiring here kills the LOCK producer the live scene
        // is still mirroring: lockSwap/mirrorCommitted stay false, phase never leaves PREPARING and
        // nothing can be revealed for the rest of the gesture -- the "slow swipe past 50 releases
        // with no unlock and no see-through" report, whose receipt shows exactly this journal entry
        // (b9:native-going-away:lock-retired) with revealing=false. Retiring is only correct once
        // the scene has actually handed off, so require the same predicate stop() uses. The scene
        // still in flight is retired by stop() itself, which already knows the real exit state.
        if (live && !(presentation.handoff() || authenticatedExit())) return null;
        boolean home=live&&target!=null ? (boolean)NativeReflection.call(target,"isActivityTypeHome") : focusedHome();
        Object lock=exitLockToken();
        if(!home || lock==null) return null;
        boolean retired=(live && mirror.retireForNativeExit()) | mirror.retireTokenForNativeExit(lock);
        if(!retired) return null;
        recordDrawReceipt(tag+(live?":lock-retired":":lock-retired-after-end"));
        SurfaceControl surface=(SurfaceControl)NativeReflection.call(lock,"getSurfaceControl");
        return surface!=null&&surface.isValid()?surface:null;
    }
    /** The bound LOCK token, else the one WallpaperController holds (a scene can end before binding). */
    private Object exitLockToken() {
        if(lease.id()>=0 && lockToken!=null) return lockToken;
        if(endedHomeLock!=null) return endedHomeLock;
        if(root==null) return null;
        Object controller=NativeReflection.get(NativeReflection.call(root,"getDefaultDisplay"),"mWallpaperController");
        for(Object token:(List<?>)NativeReflection.get(controller,"mWallpaperTokens"))
            if(exclusiveLockToken(token)) return token;
        return null;
    }
    private boolean focusedHome() {
        if(root==null) return false;
        Object area=NativeReflection.call(NativeReflection.call(root,"getDefaultDisplay"),"getDefaultTaskDisplayArea");
        Object task=area==null?null:NativeReflection.call(area,"getFocusedRootTask");
        Object top=task==null?null:NativeReflection.call(task,"topRunningActivityLocked");
        return top!=null&&(boolean)NativeReflection.call(top,"isActivityTypeHome");
    }
    private final java.util.ArrayDeque<String> exitJournal = new java.util.ArrayDeque<>();
    /**
     * Delay before a wallpaper reconcile runs after a sleep/wake/lock callback. miuix22 posted it at
     * 250ms, which left the lock wallpaper off-screen (black) for the first ~300ms of a wake - the
     * "partial black then normal" flash the author's own README says was never confirmed on device.
     * reconcile() already skips any token still in a native transition ("busy") and retries, so a
     * shorter delay cannot fight the animation; it just re-mounts the wallpaper sooner.
     */
    private static final long RECONCILE_DELAY_MS = 40;
    /** Lock-wallpaper exit decisions, published as the framework's gestureJournal. */
    /**
     * Outside a scene, every wallpaper token back under the parent and visibility WM believes it
     * has (see ServerWallpaperMirror#reconcile). Runs after sleep, wake and a new lock, once the
     * native callbacks have returned; a token still in a native transition is retried shortly.
     */
    private void reconcileWallpapers(String why, int attempts) {
        if (handler == null) return;
        handler.postDelayed(() -> guarded(() -> {
            // Not while the native exit holds the retired LOCK producer for its own transition.
            if (mirror == null || root == null || lease.id() >= 0 && presentation.handoff()) return;
            Object controller = NativeReflection.get(NativeReflection.call(root, "getDefaultDisplay"), "mWallpaperController");
            StringBuilder done = new StringBuilder();
            boolean busy = false;
            for (Object token : (List<?>) NativeReflection.get(controller, "mWallpaperTokens")) {
                String result = mirror.reconcile(token);
                if ("busy".equals(result)) busy = true;
                else if (result != null) done.append(result).append(' ');
            }
            if (busy && attempts > 1) reconcileWallpapers(why, attempts - 1);
            exitTrace("wallpaper:reconcile " + why + (busy ? " busy" : "") + " " + done.toString().trim());
        }), RECONCILE_DELAY_MS);
    }
    private void exitTrace(String entry) {
        if (exitJournal.size() == 12) exitJournal.removeFirst();
        exitJournal.addLast(now() + " " + entry);
        reporter.gestureJournal = String.join(" | ", exitJournal); reporter.publish();
    }
    private void stop(String reason) { stop(reason, false); }
    private void stop(String reason, boolean forceCancel) {
        long old = lease.id();
        endedHomeLock = !forceCancel && target != null && (boolean) NativeReflection.call(target, "isActivityTypeHome") ? lockToken : null;
        endedHomeAt = now(); if (!forceCancel) endedAt = endedHomeAt;
        exitTrace("stop lease="+old+" force="+forceCancel+" homeLock="+(endedHomeLock!=null)+" "+reason);
        // Once native auth owns unlock, focus loss/end/heartbeat expiry cannot undo the scene.
        // Sleep, AOD, a real new lock or explicit disable are the explicit cancellation routes.
        //
        // The wait below is for a terminal receipt - nativeOwned, or takeover confirmed with the
        // cover fully exposed - and this build may never send one. Waiting forever is not neutral:
        // the cover is a wallpaper mirror, so one left in place reads on the phone as a desktop that
        // has lost every icon, and as a black lockscreen once the display has cycled; only sleep
        // clears it, which is why it appeared to heal after a rest. Bound the wait, then fall
        // through to the normal path, which still computes handoff and so never replays the
        // pre-transition hidden state.
        if (!forceCancel && presentation.handoff() && nativeUnlockStarted()
                && presentation.phase() != com.zhuanz.flymemotion.core.ScenePresentation.Phase.NATIVE_OWNED
                && !(nativeTakeoverConfirmed && cover != null && cover.exposureComplete())
                && !handoffHoldExpired()) {
            reporter.liveScene = "原生交接尚未取得完整回执，保留场景：" + reason; reporter.publish();
            if (handler != null) { handler.removeCallbacks(watch); handler.postDelayed(watch,150); }
            return;
        }
        handoffHoldSince = 0;
        boolean handoff = keyguard != null && (SceneExitPolicy.completedNativeExit(nativeTakeoverConfirmed,
                (boolean) invokeKeyguard("isKeyguardShowing"), (boolean) invokeKeyguard("isKeyguardLocked"), forceCancel)
                || SceneExitPolicy.handoff(forceCancel, clientCanDismiss,
                target != null && safeActivity(target), (boolean) invokeKeyguard("isKeyguardLocked"),
                (boolean) invokeKeyguard("isKeyguardGoingAway"))
                // A keyguard that is gone and no longer locked means the unlock already happened,
                // whatever our own receipts say. Falling through to the cancel path there replays the
                // pre-transition hidden intent onto the return client, which leaves the desktop
                // showing its wallpaper with every icon missing - the exact state the phone reported
                // after some unlocks, and one that only a later lock cycle cleared.
                || (!forceCancel && !(boolean) invokeKeyguard("isKeyguardShowing")
                    && !(boolean) invokeKeyguard("isKeyguardLocked")));
        nativeExitRequested=false; nativeTakeoverConfirmed=false; lease.clear(); presentation.end(); synchronized (hazards) { hazards.clear(); } drawWindow = null; drawLayer = drawProducerLayer = -1; target = candidate = null;
        revealing = false;
        reporter.sceneSession = -1; reporter.returnTarget = "NONE";
        homeEngineDrawn = lockEngineDrawn = false; homeGeneration = lockGeneration = 0;
        reporter.sceneReadiness = reason;
        lastStatus = ""; lastReady = false;
        if (handler != null) handler.removeCallbacks(watch);
        // Reconcile native visibility first. On successful unlock, native show commands become
        // the restore baseline; replaying the old hidden state here would create a black frame.
        if (root != null && wm != null) {
            Object display = NativeReflection.call(root, "getDefaultDisplay");
            if (display != null) NativeReflection.invoke(adjustWallpaper, NativeReflection.get(display, "mWallpaperController"));
            refresh();
        }
        restoreClient(handoff);
        String outcome = reason + "; exit=" + (handoff ? "HANDOFF" : "CANCEL") + "; atomicRestore=true";
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            if (surfaces != null) surfaces.restore(transaction, handoff);
            if (cover != null) cover.retire(transaction, () -> {});
            if (mirror != null) mirror.restore(transaction, handoff, () -> { if(lateHome!=null)lateHome.finish(old); reply(old, false, outcome); });
            else transaction.addTransactionCommittedListener(handler::post, () -> { if(lateHome!=null)lateHome.finish(old); reply(old, false, outcome); });
            // No synchronous apply: WM's lock must never wait for SurfaceFlinger.
            transaction.apply();
        }
        finally {
            lockWindow = lockToken = shadeWindow = null; homeTokens.clear(); producerFrames = List.of();
            if (root != null && wm != null) {
                Object display = NativeReflection.call(root, "getDefaultDisplay");
                if (display != null) NativeReflection.invoke(adjustWallpaper, NativeReflection.get(display, "mWallpaperController"));
                refresh();
            }
            reporter.sceneReadiness = outcome + "; draw="+drawDiagnostics()+"; sources=" + (mirror == null ? "none" : mirror.lastRestore());
            reporter.lastExitReadiness = reporter.sceneReadiness + "; retiredSources=" + (mirror == null ? 0 : mirror.retiredSourceCount());
            reporter.liveScene = outcome; reporter.publish();
        }
    }
    private void guarded(Runnable work) {
        if (wm == null || failed) return;
        long began = now();
        // Sample PowerManager before entering WM and Surface observer monitors.
        // Rendering hooks only read this cached value, avoiding cross-service lock inversion.
        if (context != null) try { interactive = context.getSystemService(PowerManager.class).isInteractive(); }
        catch (RuntimeException unavailable) { interactive = false; }
        synchronized (NativeReflection.get(wm, "mGlobalLock")) {
            try { work.run(); } catch (Throwable t) { disable(t); }
        }
        reporter.sceneWorkMaxMs.accumulateAndGet(now() - began, Math::max);
    }
    private void disable(Throwable t) {
        failed = true;
        try { stop("框架场景停用，已回滚", true); } catch (Throwable ignored) { lease.clear(); target = candidate = null; homeTokens.clear(); }
        if (mirror != null) mirror.clearExitSources();
        reporter.fail(t);
    }
    private static long now() { return SystemClock.uptimeMillis(); }
}
