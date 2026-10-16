package com.zhuanz.flymemotion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import java.util.Set;
import com.zhuanz.flymemotion.core.ReloadPlan;

/** No notification/app content: bounded process receipts from scoped native UIDs. */
public final class RuntimeProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://com.zhuanz.flymemotion.runtime");
    public static final String[] COLUMNS = {"package", "state", "hooks", "motion", "blur", "entry", "pid", "boot", "elapsed", "error", "build", "configRevision", "enabled", "moduleVersion", "touch", "drag", "height", "release", "scene", "gesture", "panelFound", "keyguardFound", "intercept", "fling", "spring", "blocked", "layers", "wallpaper", "fastRedirect", "wallpaperFrames", "coordinatedRelease", "nativeRelease", "releaseReason", "gestureJournal", "liveScene", "sceneSession", "surfaceGeneration", "returnTarget", "sceneReadiness", "revokeNonce", "motionFrame", "scaleSuppressed", "wallpaperBound", "startOnly", "sceneWorkMaxMs", "lastHomeReadiness", "lastExitReadiness", "desktopAnimationState", "desktopAnimationDetail", "diag", "compat"};
    private static final Set<String> PACKAGES = Set.of("com.android.systemui", "com.android.settings", "android", "com.miui.miwallpaper");
    private SharedPreferences receipts;
    @Override public boolean onCreate() {
        receipts = getContext().createDeviceProtectedStorageContext().getSharedPreferences("runtime", Context.MODE_PRIVATE);
        return true;
    }
    private void requireReader() {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid() && uid != 0 && uid != 2000) throw new SecurityException("Private runtime diagnostics");
    }
    private void requireReporter(String pkg) {
        if (!PACKAGES.contains(pkg)) throw new SecurityException("Unscoped reporter");
        try {
            if (Binder.getCallingUid() != getContext().getPackageManager().getPackageUid(pkg, 0))
                throw new SecurityException("Reporter UID mismatch");
        } catch (android.content.pm.PackageManager.NameNotFoundException e) { throw new SecurityException(e); }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("invalidate".equals(method)) {
            if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Manager-only reload receipt reset");
            ReloadPlan.requirePackage(arg);
            var pids = ReloadPlan.pids(extras == null ? null : extras.getString("pids"));
            String blocked = pids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",", ",", ","));
            String boot = receipts.getString(arg + ".boot", "");
            var editor = receipts.edit();
            for (String key : COLUMNS) editor.remove(arg + "." + key);
            editor.putString(arg + ".invalidatedPids", blocked).putString(arg + ".invalidatedBoot", boot)
                    .putLong(arg + ".invalidatedAt", SystemClock.elapsedRealtime()).commit();
            return Bundle.EMPTY;
        }
        if ("report".equals(method)) {
            requireReporter(arg);
            if (extras == null) return Bundle.EMPTY;
            String blocked = receipts.getString(arg + ".invalidatedPids", "");
            long elapsed = SystemClock.elapsedRealtime() - receipts.getLong(arg + ".invalidatedAt", 0);
            if (elapsed >= 0 && elapsed < 150_000 && blocked.contains("," + extras.getInt("pid") + ",")
                    && receipts.getString(arg + ".invalidatedBoot", "").equals(Integer.toString(extras.getInt("boot"))))
                return Bundle.EMPTY;
            SharedPreferences.Editor edit = receipts.edit();
            for (String key : COLUMNS) {
                if (key.equals("package")) continue;
                Object value = extras.get(key);
                String text = value == null ? "" : String.valueOf(value);
                // "diag" is the short line that carries the fields a waiting scene is decided by, so
                // truncating it at the default 512 silently removed exactly those fields.
                int limit = key.equals("gestureJournal") ? 8192 : key.equals("compat") ? 4096 : key.equals("layers") ? 4096
                        : key.equals("sceneReadiness") || key.equals("lastHomeReadiness") || key.equals("lastExitReadiness") || key.equals("diag") ? 2048 : 512;
                edit.putString(arg + "." + key, text.substring(0, Math.min(limit, text.length())));
            }
            edit.putString(arg + ".elapsed", Long.toString(SystemClock.elapsedRealtime())).commit();
            return Bundle.EMPTY;
        }
        requireReader();
        Bundle data = new Bundle();
        for (String pkg : PACKAGES) for (String key : COLUMNS)
            data.putString(pkg + "." + key, receipts.getString(pkg + "." + key, ""));
        return data;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        requireReader(); MatrixCursor cursor = new MatrixCursor(COLUMNS);
        for (String pkg : new String[]{"com.android.systemui", "com.android.settings", "android", "com.miui.miwallpaper"}) {
            Object[] row = new Object[COLUMNS.length]; row[0] = pkg;
            for (int i = 1; i < row.length; i++) row[i] = receipts.getString(pkg + "." + COLUMNS[i], "");
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/vnd.flymemotion.runtime"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
