package com.zhuanz.flymemotion.hook;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import com.zhuanz.flymemotion.RuntimeProvider;
import com.zhuanz.flymemotion.TargetBuild;
import java.util.concurrent.atomic.AtomicLong;

final class RuntimeReporter {
    final AtomicLong motion = new AtomicLong(), blur = new AtomicLong(), entry = new AtomicLong();
    final AtomicLong touch = new AtomicLong(), drag = new AtomicLong(), height = new AtomicLong(), release = new AtomicLong();
    final AtomicLong intercept = new AtomicLong(), fling = new AtomicLong(), spring = new AtomicLong();
    final AtomicLong fastRedirect = new AtomicLong(), wallpaperFrames = new AtomicLong();
    final AtomicLong coordinatedRelease = new AtomicLong(), nativeRelease = new AtomicLong();
    final AtomicLong scaleSuppressed = new AtomicLong();
    volatile String motionFrame = "尚未应用运动帧";
    volatile String releaseReason = "等待释放", gestureJournal = "";
    volatile String scene = "等待触摸入口", gesture = "尚未收到锁屏手势";
    volatile String blocked = "", layers = "", wallpaper = "等待当前锁屏壁纸 Surface 绑定";
    volatile String liveScene = "等待系统框架场景回执";
    volatile String sceneReadiness = "等待真实场景绘制", returnTarget = "NONE";
    volatile String lastExitReadiness = "none";
    volatile String lastHomeReadiness = "尚无桌面场景回执";
    /**
     * Short single-line diagnostics. The readiness line is capped by the receipt transport once it
     * grows past a few kilobytes, so anything that must always be readable lives here instead.
     */
    volatile String diag = "";
    volatile long sceneSession = -1, surfaceGeneration;
    volatile long revokeNonce;
    volatile boolean panelFound, keyguardFound, wallpaperBound;
    volatile String desktopAnimationState="uninitialized",desktopAnimationDetail="";
    final AtomicLong startOnly = new AtomicLong();
    final AtomicLong sceneWorkMaxMs = new AtomicLong();
    volatile String state = "initializing", error = "";
    /** Live/disabled parts of this process's adapter on this build; see CompatLog. */
    final com.zhuanz.flymemotion.core.CompatLog compat = new com.zhuanz.flymemotion.core.CompatLog();
    volatile int hooks;
    volatile long configRevision;
    volatile boolean enabled;
    private final String pkg;
    private volatile Context context;
    private final Handler worker;
    private final Runnable beat = new Runnable() {
        @Override public void run() { report(); worker.postDelayed(this, 60_000); }
    };
    RuntimeReporter(String pkg) {
        this.pkg = pkg; HandlerThread t = new HandlerThread("FlymeMotion-status"); t.start(); worker = new Handler(t.getLooper());
    }
    void attach(Context c) {
        Context app = c.getApplicationContext(); context = app == null ? c : app;
        worker.removeCallbacks(beat); worker.post(beat);
    }
    void publish() { worker.post(this::report); }
    private long lastFrameReceipt;
    void publishFrame() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFrameReceipt >= 500) { lastFrameReceipt = now; publish(); }
    }
    void fail(Throwable t) {
        state = "failed"; error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        Log.e("FlymeMotion", pkg + " disabled: " + error); publish();
    }
    private void report() {
        Context c = context; if (c == null) return;
        try {
            Bundle data = new Bundle(); data.putString("state", state); data.putInt("hooks", hooks);
            data.putLong("motion", motion.get()); data.putLong("blur", blur.get()); data.putLong("entry", entry.get());
            data.putLong("configRevision", configRevision); data.putBoolean("enabled", enabled);
            data.putString("moduleVersion", TargetBuild.MODULE_VERSION);
            data.putLong("touch", touch.get()); data.putLong("drag", drag.get());
            data.putLong("height", height.get()); data.putLong("release", release.get());
            data.putString("scene", scene); data.putString("gesture", gesture);
            data.putLong("intercept", intercept.get()); data.putLong("fling", fling.get()); data.putLong("spring", spring.get());
            data.putLong("fastRedirect", fastRedirect.get()); data.putLong("wallpaperFrames", wallpaperFrames.get());
            data.putLong("coordinatedRelease", coordinatedRelease.get()); data.putLong("nativeRelease", nativeRelease.get());
            data.putString("releaseReason", releaseReason); data.putString("gestureJournal", gestureJournal);
            data.putLong("scaleSuppressed", scaleSuppressed.get()); data.putString("motionFrame", motionFrame);
            data.putBoolean("wallpaperBound", wallpaperBound);
            data.putLong("startOnly", startOnly.get());
            data.putLong("sceneWorkMaxMs", sceneWorkMaxMs.get());
            data.putString("blocked", blocked); data.putString("layers", layers); data.putString("wallpaper", wallpaper);
            data.putString("liveScene", liveScene);
            data.putString("sceneReadiness", sceneReadiness); data.putString("returnTarget", returnTarget);
            data.putString("desktopAnimationState",desktopAnimationState);data.putString("desktopAnimationDetail",desktopAnimationDetail);
            data.putString("lastExitReadiness", lastExitReadiness);
            data.putString("lastHomeReadiness", lastHomeReadiness);
            data.putString("diag", diag);
            data.putLong("sceneSession", sceneSession); data.putLong("surfaceGeneration", surfaceGeneration);
            data.putLong("revokeNonce", revokeNonce);
            data.putBoolean("panelFound", panelFound); data.putBoolean("keyguardFound", keyguardFound);
            data.putInt("pid", Process.myPid());
            data.putInt("boot", Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
            data.putString("compat", compat.encode());
            data.putString("error", error); data.putString("build", Build.FINGERPRINT);
            c.getContentResolver().call(RuntimeProvider.URI, "report", pkg, data);
        } catch (RuntimeException e) { Log.w("FlymeMotion", "Receipt not delivered: " + e.getClass().getSimpleName()); }
    }
}
