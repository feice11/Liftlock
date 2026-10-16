package com.zhuanz.flymemotion.hook;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import com.zhuanz.flymemotion.SceneProtocol;
import com.zhuanz.flymemotion.core.SceneLease;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Adapted lifecycle/frame-request subset of HyperMusicCover v0.7.10 WallpaperProbe (AGPL-3.0).
 * Copyright (C) 2026 zyl6932. Changes: session quorum, EGL swap receipt, no texture/content writes.
 * Source: github.com/zyl6932/HyperMusicCover @ 9462d23d1761e002c1241d874a78b4e4548c8c7f.
 *
 * <p>The member names above were read off the inspected APK, which is obfuscated there
 * ({@code t0.a / j / M / l / U}). Another MiWallpaper build ships the same three calls under real
 * names with a different pairing, so every member below is resolved on its own and a miss degrades
 * that one capability into the compat report instead of failing the whole engine adapter.
 */
final class WallpaperHooks {
    private static final String BASE = "com.miui.miwallpaper.wallpaperservice.impl.ImageEngineImpl";
    private static final String UNIVERSAL = "com.miui.miwallpaper.wallpaperservice.UniversalWallpaper$UniversalEngine";
    private static final String STREAM_LOCK = "com.miui.miwallpaper.wallpaperservice.impl.keyguard.KeyguardStreamImageEngineImpl";
    private final MotionModule module;
    private final ClassLoader loader;
    private final RuntimeReporter reporter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final IdentityHashMap<Object, Engine> engines = new IdentityHashMap<>();
    private record Drawing(Engine engine, int bufferId) {}
    private final ThreadLocal<Drawing> drawing = new ThreadLocal<>();
    private final SceneLease lease = new SceneLease();
    private Context context;
    private long generations, statusSequence;
    private Method request;
    /**
     * What the engine's own frame request passes for its flag, learned by watching it.
     *
     * <p>This flag's meaning is build-specific - a sibling adaptation documents the same call taking
     * {@code {true,true,false,false}} across four releases - so writing a constant is a write onto the
     * engine's own idea of what it is drawing. Measured here: forcing {@code true} on every gesture
     * left the lock wallpaper rendering one partly-cropped region with the rest of the screen black,
     * fresh after setting a wallpaper and degrading over a few unlock cycles. The value is therefore
     * replayed from an observed call and never invented; with nothing observed the request is skipped
     * instead, which costs at most a nudge - the engine still draws its visible wallpaper on its own.
     */
    private final java.util.concurrent.ConcurrentHashMap<Object, Boolean> learnedRequestFlag = new java.util.concurrent.ConcurrentHashMap<>();
    /** Set while this module issues the request, so the observer cannot learn from its own write. */
    private final ThreadLocal<Boolean> requestingFrame = new ThreadLocal<>();
    private long requestWrites, requestSkips;
    // Resolved by TYPE, not by obfuscated name, so another MiWallpaper build still binds.
    private java.lang.reflect.Field serviceOfEngine, engineOfService, eglOfService;
    /** The engine holder a build may leave unnamed: the field that produced {@link #engineOfService}. */
    private String engineFieldName = "?";
    /** The EGL helper is reachable from the impl on every inspected build: by field or by call. */
    private Method eglHelperCall;
    /** Where the engine keeps its window binder, read for the frame receipt's client identity. */
    private String clientFieldName;
    private boolean knownShape, drawHooked;
    private int unmatchedSwaps;
    /**
     * No MiWallpaper engine classes on this build (renamed or restructured): every platform
     * WallpaperService.Engine in this process is tracked directly, through the framework's own
     * lifecycle calls, with frames counted at EGL14. The engine then is its own "service".
     */
    private boolean platformMode;
    private String shapeReport = "";
    private String lastPublished = "";
    private long lastHomeGeneration = -1, lastLockGeneration = -1, lastHomeTop = -1, lastLockTop = -1;
    /** The last few frame/binding events this process produced, newest last. */
    private final java.util.ArrayDeque<String> journal = new java.util.ArrayDeque<>();
    private final Runnable keepAlive = new Runnable() {
        @Override public void run() {
            long now=SystemClock.uptimeMillis();
            if (!lease.active(now)) { end();return; }
            if(!lease.active(now)&&lease.id()>=0)end();
            requestFrames(); main.postDelayed(this, 450);
        }
    };
    static final class Engine {
        final Object service;
        int which;
        Object universal;
        SurfaceControl container, buffer;
        long generation, swaps, baseline;
        boolean visible, drawnPending;
        Engine(Object service, int which) { this.service = service; this.which = which; }
        boolean valid() { return container != null && buffer != null && container.isValid() && buffer.isValid(); }
        /** A frame after the last (re)capture: what the framework's freshness check reads. */
        boolean drawn() { return valid() && swaps > baseline; }
    }
    WallpaperHooks(MotionModule module, ClassLoader loader, RuntimeReporter reporter) {
        this.module = module; this.loader = loader; this.reporter = reporter;
    }
    void install() throws Throwable {
        Class<?> base = com.zhuanz.flymemotion.core.Members.load(loader, BASE);
        Class<?> universal = com.zhuanz.flymemotion.core.Members.load(loader, UNIVERSAL);
        if (base == null || universal == null) {
            installPlatform((base == null ? "ImageEngineImpl" : "UniversalEngine") + " not found");
            return;
        }
        // Every member below is resolved independently; a build that names or holds one
        // differently loses only that capability and says so in the report.
        engineOfService = fieldWhere(base, f -> f.getType() == universal);
        if (engineOfService != null) engineFieldName = "exact";
        else {
            // The holder under the names every MiWallpaper build keeps, then the field the platform
            // base it extends hides it in, then any holder of a supertype of it. Consulted only
            // after the exact field missed, in that order: the last one can otherwise pick the
            // service-side back reference this same pairing already answers.
            java.lang.reflect.Field held = fieldOrNull(base, "mEngine");
            if (held == null) held = fieldOrNull(base, "mEngineService");
            if (held == null) held = fieldWhere(base,
                    f -> f.getType() != Object.class && f.getType() != universal && f.getType().isAssignableFrom(universal));
            if (held != null && !universalType(held).isAssignableFrom(universal)) held = null;
            if (held == null) held = fieldWhere(base, f -> f.getType().isAssignableFrom(universal));
            if (held == null) held = anyField(base, f -> f.getType().isAssignableFrom(universal));
            if (held != null) { engineOfService = held; engineFieldName = held.getName(); }
        }
        // The engine may not reach its service back at all (seen on a readable-name port); the
        // pairing then comes from the field above, in the other direction, and from setEngine.
        serviceOfEngine = fieldWhere(universal,
                f -> f.getType() != Object.class && f.getType().isAssignableFrom(base));
        // The EGL helper: reachable from the impl by field or by accessor, on the impl itself or
        // on a helper it holds. Frame counting needs its swap call and its EGLSurface.
        eglOfService = fieldWhere(base, f -> declares(f.getType(), android.opengl.EGLSurface.class));
        eglHelperCall = com.zhuanz.flymemotion.core.Members.method(base,
                com.zhuanz.flymemotion.core.Members.Sig.of("getEglHelperInstance"),
                com.zhuanz.flymemotion.core.Members.Sig.of("getEglHelper"));
        // The engine's window binder, for the frame receipt the framework matches by client.
        clientFieldName = clientBinderField(universal);
        StringBuilder shape = new StringBuilder();
        shape.append("engineField=").append(engineFieldName)
                .append(serviceOfEngine == null ? " no-backref" : " backref=" + serviceOfEngine.getName());
        registerConstruction(base, universal);
        Method render = optionalMethod(base, "M", boolean.class);
        Method swap = optionalMethod(eglType(base), "l");
        request = optionalMethod(base, "U", boolean.class);
        knownShape = "t0.a".equals(eglType(base).getName()) && render != null && swap != null && request != null
                && swap.getReturnType() == boolean.class && "j".equals(fieldNameOf(eglOfService));
        if (!knownShape) {
            // A build that ships readable names - an OEM port keeps EglHelper and ImageEngineImpl -
            // offers the same calls under their real names: swapBuffer() and requestRender().
            // Consulted only after the exact inspected shape missed.
            if (swap == null) swap = optionalMethod(eglType(base), "swapBuffer");
            if (request == null) request = optionalMethod(base, "requestRender");
            if (request == null) request = optionalMethod(base, "requestRenderInternal");
            knownShape = swap != null && swap.getReturnType() == boolean.class;
        }
        drawHooked = knownShape && render != null;
        // The inspected draw hook pairs a frame with the BLAST child it lands on. A build that
        // matches the calls but not that field counts at EGL instead, like every other build.
        if (drawHooked && fieldOrNull(universal, "mBbqSurfaceControl") == null) drawHooked = false;
        shape.append(eglOfService == null ? " egl=byCall" : " egl=" + eglOfService.getType().getName() + "." + eglOfService.getName());
        shape.append(request == null ? " frameRequest=unresolved" : " frameRequest=" + request.getName());
        shape.append(swap == null ? " swap=unresolved" : " swap=" + swap.getName());
        if (request != null && request.getParameterCount() > 0) {
            // Watch the engine's own frame requests so the flag can be replayed instead of invented.
            // Our own calls are excluded: learning from ourselves would just confirm the constant.
            register(request, chain -> {
                if (!Boolean.TRUE.equals(requestingFrame.get()) && chain.getArg(0) instanceof Boolean)
                    learnedRequestFlag.put(chain.getThisObject(), (Boolean) chain.getArg(0));
                return chain.proceed();
            });
            shape.append(" flag=learned/").append(learnedRequestFlag.size());
        }
        shapeReport = knownShapeName() + " " + shape;
        if (drawHooked) register(render, chain -> {
            Engine engine;
            synchronized (engines) { engine = engines.get(chain.getThisObject()); }
            Drawing previous = drawing.get();
            SurfaceControl buffer = engine == null || engine.universal == null ? null
                    : (SurfaceControl) NativeReflection.optGet(engine.universal, "mBbqSurfaceControl");
            int bufferId = buffer == null || !buffer.isValid() ? -1 : ((Number) NativeReflection.call(buffer, "getLayerId")).intValue();
            drawing.set(new Drawing(engine, bufferId));
            try { return chain.proceed(); }
            finally { if (previous == null) drawing.remove(); else drawing.set(previous); }
        });
        if (!drawHooked) register(EGL14Swap.method(), chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result)) {
                Object surface = chain.getArg(1);
                main.post(() -> guarded(() -> {
                    Engine engine = engineForSurface(surface);
                    if (engine == null) { unmatchedSwaps++; return; }
                    bind(engine);
                    if (!engine.valid()) { unmatchedSwaps++; return; }
                    frame(engine);
                    if (lease.id() >= 0) publish();
                }));
            }
            return result;
        });
        if (drawHooked) register(swap, chain -> {
            Object result = chain.proceed(); Drawing sample = drawing.get(); Engine engine = sample == null ? null : sample.engine();
            if (engine != null && Boolean.TRUE.equals(result)
                    && (eglOfService == null || fieldOf(eglOfService, engine.service) == chain.getThisObject())) {
                main.post(() -> guarded(() -> {
                    bind(engine);
                    if (!engine.valid() || sample.bufferId() <= 0
                            || ((Number) NativeReflection.call(engine.buffer, "getLayerId")).intValue() != sample.bufferId()) {
                        unmatchedSwaps++; return;
                    }
                    frame(engine);
                    if (lease.id() >= 0) publish();
                }));
            }
            return result;
        });
        for (String name : new String[]{"onSurfaceCreated", "onSurfaceDestroyed", "onSurfaceRedrawNeeded"}) {
            Method lifecycle = NativeReflection.methodOrNull(universal, name, SurfaceHolder.class);
            if (lifecycle == null) continue;
            register(lifecycle, chain -> {
                Object result = chain.proceed(); Object owner = chain.getThisObject();
                main.post(() -> guarded(() -> {
                    Engine engine = serviceFor(owner);
                    if (engine == null) return;
                    engine.universal = owner;
                    if (name.equals("onSurfaceDestroyed")) {
                        engine.container = engine.buffer = null; engine.generation = ++generations;
                        engine.baseline = engine.swaps; publish();
                    } else { bind(engine); if (lease.id() >= 0) requestFrames(); }
                })); return result;
            });
        }
        Method visibility = NativeReflection.methodOrNull(universal, "onVisibilityChanged", boolean.class);
        if (visibility != null) register(visibility, chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject(); boolean visible = (boolean) chain.getArg(0);
            main.post(() -> guarded(() -> {
                Engine engine = serviceFor(owner); if (engine == null) return;
                engine.universal = owner; engine.visible = visible; bind(engine); reporter.entry.incrementAndGet();
                // Warm the existing desktop output when the native lock engine becomes visible.
                // Does not show any application or change native keyguard/engine visibility.
                if (visible && engine.which == 2) requestFrames();
                if (lease.id() >= 0) publish(); else reporter.publish();
            })); return result;
        });
        if (knownShape) for (var method : new Method[]{render, swap, request})
            if (method != null) module.deoptimize(method);
        reporter.state = "loaded"; reporter.wallpaper = shapeReport;
        if (request == null) reporter.compat.off("壁纸出帧", "无请求方法，只能被动计帧；" + shapeReport);
        else if (!knownShape) reporter.compat.off("壁纸出帧", "无出帧时机，无法计帧；" + shapeReport);
        else if (serviceOfEngine == null) reporter.compat.on("壁纸出帧", "引擎无反向引用，配对来自构建期捕获；" + shapeReport);
        else if (eglOfService == null) reporter.compat.on("壁纸出帧", "EGL 助手按调用获取；" + shapeReport);
        else reporter.compat.on("壁纸出帧", shapeReport);
    }
    /** See {@link #platformMode}. */
    private void installPlatform(String why) throws Throwable {
        platformMode = true;
        Class<?> engineType = Class.forName("android.service.wallpaper.WallpaperService$Engine", false, loader);
        register(EGL14Swap.method(), chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result)) {
                Object surface = chain.getArg(1);
                main.post(() -> guarded(() -> {
                    Engine engine = engineForSurface(surface);
                    if (engine == null) { unmatchedSwaps++; return; }
                    bind(engine);
                    if (!engine.valid()) { unmatchedSwaps++; return; }
                    frame(engine);
                    if (lease.id() >= 0) publish();
                }));
            }
            return result;
        });
        // The framework's own drivers of the subclass callbacks; their overloads vary by release.
        for (Method surface : com.zhuanz.flymemotion.core.Members.overloads(engineType, "updateSurface")) register(surface, chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject();
            main.post(() -> guarded(() -> {
                Engine engine = capture(owner); if (engine == null) return;
                bind(engine); if (lease.id() >= 0) requestFrames();
            })); return result;
        });
        for (Method visibility : com.zhuanz.flymemotion.core.Members.overloads(engineType, "doVisibilityChanged")) register(visibility, chain -> {
            Object result = chain.proceed(); Object owner = chain.getThisObject(); boolean visible = (boolean) chain.getArg(0);
            main.post(() -> guarded(() -> {
                Engine engine = capture(owner); if (engine == null) return;
                engine.visible = visible; bind(engine); reporter.entry.incrementAndGet();
                if (lease.id() >= 0) publish(); else reporter.publish();
            })); return result;
        });
        for (Method detach : com.zhuanz.flymemotion.core.Members.overloads(engineType, "detach")) register(detach, chain -> {
            Object owner = chain.getThisObject(); Object result = chain.proceed();
            main.post(() -> guarded(() -> {
                Engine engine; synchronized (engines) { engine = engines.remove(owner); }
                if (engine != null) publish();
            })); return result;
        });
        reporter.state = "loaded";
        reporter.wallpaper = "通用壁纸引擎模式：" + why;
        reporter.compat.on("壁纸出帧", "通用引擎模式（" + why + "），无法主动请求出帧");
    }
    void attach(Context c) {
        context = c.getApplicationContext(); reporter.attach(context);
        if (c.getApplicationInfo() == null) return;
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!SceneReceivers.fromPackage(c, this, "com.android.systemui")) return;
                    guarded(() -> receive(intent));
                }
            }, new IntentFilter(SceneProtocol.REQUEST), NativeSceneChannel.PERMISSION, main, Context.RECEIVER_EXPORTED);
            SceneReceivers.revoke(context, main, reporter, this::end);
            reporter.wallpaper = "现有 ImageEngine 监听就绪，等待原生 Surface / EGL swap"; reporter.publish();
        } catch (Throwable t) { end(); reporter.fail(t); }
    }
    private Engine capture(Object service) {
        if (service == null || !isImage(service)) return null;
        synchronized (engines) {
            Engine existing = engines.get(service); if (existing != null) return existing;
            int identity = role(service);
            if (identity != 1 && identity != 2 && identity != 3) return null;
            EngineNames.resolve(service); // HMC's type-safe renderer/pending-flag discovery; read-only.
            Engine engine = new Engine(service, identity); engines.put(service, engine);
            engine.universal = universalOf(service); bind(engine); return engine;
        }
    }
    private boolean isImage(Object service) {
        if (platformMode) return service instanceof android.service.wallpaper.WallpaperService.Engine;
        for (Class<?> type = service.getClass(); type != null; type = type.getSuperclass())
            if (BASE.equals(type.getName())) return true;
        return false;
    }
    /**
     * Which wallpaper this producer draws: 1 = home only, 2 = lock only, 3 = both. Read from the
     * producer itself, under the inspected build's own accessor and then the platform names an OEM
     * port keeps; a build with none of them has one shared engine, i.e. 3.
     */
    private int role(Object service) {
        // This APK's lock stream renderer inherits ImageEngineImpl directly; unlike
        // KeyguardImageEngineImpl it has no s() getter. Its own show/hideKeyguardWallpaper
        // implementation identifies the lock-only producer. Do not call a missing method.
        if (STREAM_LOCK.equals(service.getClass().getName())) return 2;
        // The inspected build's own accessor. A build can match the frame shape and still name this
        // one differently, so it is asked for by existence, never assumed.
        Method own = com.zhuanz.flymemotion.core.Members.unique(service.getClass(), "s");
        if (own != null && own.getReturnType() == int.class) return ((Number) NativeReflection.invoke(own, service)).intValue();
        // The platform names an OEM port keeps for the same field: singular then plural, and a
        // build whose accessor answers something other than a wallpaper role is asked the next one.
        for (String name : new String[]{"getWallpaperFlag", "getWallpaperFlags"}) {
            Object flags = NativeReflection.optCall(service, name);
            if (flags instanceof Number n && n.intValue() >= 1 && n.intValue() <= 3) return n.intValue();
        }
        // A platform engine (no MiWallpaper impl) answers the framework's own flags instead.
        Object engine = universalOf(service);
        if (engine == null) return platformMode ? 3 : 0;
        Object flags = NativeReflection.optCall(engine, "getWallpaperFlags");
        return flags instanceof Number n ? n.intValue() : 3;
    }
    private Object universalOf(Object service) {
        if (platformMode) return service;
        if (engineOfService == null) return null;
        return fieldOf(engineOfService, service);
    }
    private Object serviceOf(Object universal) {
        if (platformMode) return universal;
        if (serviceOfEngine == null) return null;
        return fieldOf(serviceOfEngine, universal);
    }
    /**
     * The captured service an engine instance belongs to. A build may not let the engine reference
     * its service back; there the pairing is the field from the other direction and, when the impl
     * has not been constructed yet, the identity kept when {@code setEngine} bound the two.
     */
    private Engine serviceFor(Object universal) {
        if (universal == null) return null;
        Engine paired = capture(serviceOf(universal));
        if (paired != null) return paired;
        synchronized (engines) {
            for (Engine e : engines.values()) {
                if (e.universal == universal) return e;
                if (e.universal == null && engineOfService != null && fieldOf(engineOfService, e.service) == universal) {
                    e.universal = universal; return e;
                }
            }
        }
        return null;
    }
    /** How this build's members were found, for the compat report. */
    private String knownShapeName() {
        return knownShape ? "shape=known" : "shape=oem";
    }
    /** The EGL helper a service owns: by field, else by the accessor some builds rename it to. */
    private Class<?> eglType(Class<?> base) {
        if (eglOfService != null) return eglOfService.getType();
        if (eglHelperCall != null) return eglHelperCall.getReturnType();
        return base; // Nothing resolved: the frame calls are looked up on the impl itself, and miss.
    }
    private Object eglHelper(Object service) {
        Object helper = eglOfService == null ? null : fieldOf(eglOfService, service);
        if (helper != null) return helper;
        return eglHelperCall == null ? null : NativeReflection.invoke(eglHelperCall, service);
    }
    /** The engine owning this EGLSurface: in its helper's field, or below the engine's own. */
    private Engine engineForSurface(Object surface) {
        if (surface == null) return null;
        synchronized (engines) {
            for (Engine e : engines.values()) {
                if (platformMode) { if (holdsSurface(e.service, surface, 1)) return e; continue; }
                Object helper = eglHelper(e.service);
                if (helper != null && surface.equals(fieldValueOfType(helper, android.opengl.EGLSurface.class))) return e;
            }
        }
        return null;
    }
    /** The engine owning this EGLSurface: its own fields, or one object below them. */
    private static boolean holdsSurface(Object owner, Object surface, int depth) {
        if (owner == null || surface == null) return false;
        for (Class<?> k = owner.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().startsWith("android.") || k.getName().startsWith("java.")) break;
            for (var f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value == null) continue;
                    if (f.getType() == android.opengl.EGLSurface.class) { if (surface.equals(value)) return true; }
                    else if (depth > 0 && holdsSurface(value, surface, depth - 1)) return true;
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
        }
        return false;
    }
    /**
     * The engine's own layer and its BLAST buffer. Read by the inspected build's field names, then a
     * class that renames them is asked for its BLAST child directly: the buffer's parent chain
     * reaches the container, so a pair taken by type is kept only when that relation holds.
     */
    private void findLayers(Engine e, Object universal) {
        SurfaceControl container = (SurfaceControl) NativeReflection.optGet(universal, "mSurfaceControl");
        SurfaceControl buffer = (SurfaceControl) NativeReflection.optGet(universal, "mBbqSurfaceControl");
        if (buffer == null) buffer = (SurfaceControl) anyFieldByType(universal, SurfaceControl.class);
        if (container == null) container = (SurfaceControl) anyFieldByType(universal, SurfaceControl.class);
        if (container != null && buffer != null && !same(container, buffer) && !descendsFrom(container, buffer)) {
            SurfaceControl held = container; container = buffer; buffer = held;
        }
        if (!same(container, e.container) || !same(buffer, e.buffer)) {
            e.container = container; e.buffer = buffer; e.generation = ++generations; e.baseline = e.swaps;
            record("layers " + e.service.getClass().getSimpleName() + " gen=" + e.generation
                    + " container=" + layerOf(container) + " buffer=" + layerOf(buffer) + " base=" + e.baseline);
        }
    }
    private static boolean descendsFrom(SurfaceControl child, SurfaceControl ancestor) {
        if (child == null || ancestor == null || !child.isValid() || !ancestor.isValid()) return false;
        long wanted = layerOf(ancestor);
        for (int depth = 0; depth < 8; depth++) {
            SurfaceControl parent = (SurfaceControl) NativeReflection.optCall(child, "getParent");
            if (parent == null || !parent.isValid()) return false;
            if (layerOf(parent) == wanted) return true;
            child = parent;
        }
        return false;
    }
    private static long layerOf(SurfaceControl surface) {
        Object id = NativeReflection.optCall(surface, "getLayerId");
        return id instanceof Number n ? n.longValue() : -1;
    }
    /** The window binder this frame belongs to, for the framework's client identity check. */
    private Object clientBinder(Object universal) {
        Object window = clientFieldName == null ? null : NativeReflection.optGet(universal, clientFieldName);
        if (window == null) for (String name : new String[]{"mWindow", "mIWindow", "mClient"})
            if ((window = NativeReflection.optGet(universal, name)) != null) break;
        return window instanceof android.os.IInterface i ? i.asBinder() : null;
    }
    static String clientBinderField(Class<?> engine) {
        for (String name : new String[]{"mWindow", "mIWindow", "mClient"}) {
            java.lang.reflect.Field f = fieldOrNull(engine, name);
            if (f != null && android.os.IInterface.class.isAssignableFrom(f.getType())) return name;
        }
        for (java.lang.reflect.Field f : allFields(engine))
            if (android.os.IInterface.class.isAssignableFrom(f.getType())) return f.getName();
        return null;
    }
    /** An engine that already existed when this adapter loaded never ran a constructor we saw. */
    private void registerConstruction(Class<?> base, Class<?> universal) {
        for (var ctor : base.getDeclaredConstructors()) register(ctor, chain -> {
            Object result = chain.proceed();
            Object service = chain.getThisObject();
            main.post(() -> guarded(() -> capture(service)));
            return result;
        });
        // Some builds hand the impl its engine through this setter instead of its constructor.
        Method setEngine = NativeReflection.methodOrNull(base, "setEngine", android.service.wallpaper.WallpaperService.Engine.class);
        if (setEngine == null) return;
        register(setEngine, chain -> {
            Object service = chain.getThisObject(), engine = chain.getArg(0);
            Object result = chain.proceed();
            main.post(() -> guarded(() -> {
                Engine captured = capture(service);
                if (captured != null && engine != null) captured.universal = engine;
            }));
            return result;
        });
    }
    private static String fieldNameOf(java.lang.reflect.Field field) {
        return field == null ? "-" : field.getName();
    }
    /** One line of this process's own frame/binding history, for the receipt. */
    private void record(String event) {
        if (journal.size() >= 6) journal.removeFirst();
        journal.addLast(android.os.SystemClock.uptimeMillis() % 1000000 + " " + event);
    }
    private static Object fieldOf(java.lang.reflect.Field field, Object owner) {
        try { return owner == null || field == null ? null : field.get(owner); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static java.lang.reflect.Field fieldWhere(Class<?> type, java.util.function.Predicate<java.lang.reflect.Field> test) {
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass())
            for (var f : k.getDeclaredFields())
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && test.test(f)) { f.setAccessible(true); return f; }
        return null;
    }
    private static java.lang.reflect.Field accessible(java.lang.reflect.Field f) { f.setAccessible(true); return f; }
    /** The type a holder declares, for deciding whether it can hold this build's engine at all. */
    private static Class<?> universalType(java.lang.reflect.Field field) {
        return field == null ? Object.class : field.getType();
    }
    private static java.util.List<java.lang.reflect.Field> allFields(Class<?> type) {
        java.util.List<java.lang.reflect.Field> found = new ArrayList<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass())
            for (var f : k.getDeclaredFields())
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); found.add(f); }
        return found;
    }
    private static java.lang.reflect.Field anyField(Class<?> type, java.util.function.Predicate<java.lang.reflect.Field> test) {
        for (var f : allFields(type)) if (test.test(f)) return f;
        return null;
    }
    /** The single field of this type, else the first one, so a renamed holder still binds. */
    private static Object anyFieldByType(Object owner, Class<?> kind) {
        Object first = null;
        for (var f : allFields(owner.getClass())) {
            if (!kind.isAssignableFrom(f.getType())) continue;
            Object value = fieldOf(f, owner);
            if (value == null) continue;
            if (first != null) return null; // Ambiguous: the plain names above are the only answer.
            first = value;
        }
        return first;
    }
    /** A member lookup that never throws: an absent member answers null instead of failing. */
    private static java.lang.reflect.Field fieldOrNull(Class<?> type, String name) {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) try {
            java.lang.reflect.Field f = k.getDeclaredField(name); f.setAccessible(true); return f;
        } catch (NoSuchFieldException ignored) {}
        return null;
    }
    private static boolean declares(Class<?> type, Class<?> member) {
        return fieldWhere(type, f -> f.getType() == member) != null;
    }
    private static Object fieldValueOfType(Object owner, Class<?> member) {
        var f = fieldWhere(owner.getClass(), x -> x.getType() == member);
        return f == null ? null : fieldOf(f, owner);
    }
    private static Method optionalMethod(Class<?> type, String name, Class<?>... args) {
        try { Method m = type.getDeclaredMethod(name, args); m.setAccessible(true); return m; }
        catch (NoSuchMethodException e) { return null; }
    }
    private void frame(Engine e) {
        e.swaps++; reporter.wallpaperFrames.incrementAndGet();
        record("swap " + e.service.getClass().getSimpleName() + " which=" + e.which
                + " swaps=" + e.swaps + " base=" + e.baseline + " drawn=" + e.drawn());
    }
    private void bind(Engine e) {
        int actualRole = role(e.service);
        if (actualRole != 1 && actualRole != 2 && actualRole != 3) { e.container = e.buffer = null; return; }
        if (actualRole != e.which) { e.which = actualRole; e.generation = ++generations; e.baseline = e.swaps; }
        if (e.universal == null) e.universal = universalOf(e.service);
        if (e.universal == null) return;
        // EngineService can be replaced while the same UniversalEngine/BLAST layer survives.
        // A retained old image renderer must never acknowledge the selected engine's buffer.
        // Without a back reference there is nothing to compare; the preview check below stays.
        if ((serviceOfEngine != null && serviceOf(e.universal) != e.service)
                || NativeReflection.optCallBool(e.universal, "isPreview", false)) {
            if (e.container != null || e.buffer != null) { e.generation = ++generations; e.baseline = e.swaps; }
            e.container = e.buffer = null; return;
        }
        findLayers(e, e.universal);
    }
    private static boolean same(SurfaceControl a, SurfaceControl b) {
        return a == b || a != null && b != null && a.isValid() && b.isValid()
                && (boolean) NativeReflection.invoke(NativeReflection.method(SurfaceControl.class, "isSameSurface", SurfaceControl.class), a, b);
    }
    private void receive(Intent intent) {
        if (!"loaded".equals(reporter.state)) return;
        long token = intent.getLongExtra("token", -1); String command = intent.getStringExtra("command");
        if ("end".equals(command)) { if (token == lease.id()) end(); return; }
        if ("begin".equals(command)) {
            if (!lease.begin(token, SystemClock.uptimeMillis())) return;
            // Do NOT re-baseline here. drawn() means "a frame since the last (re)capture", and a
            // scene starting is not a capture: the engine, its layers and its output are unchanged.
            // Leveling the baseline to the current swap count makes the very first readiness check
            // of every scene read drawn=false, and the LOCK wallpaper is static - it does not redraw
            // on its own, so requestRender() may legitimately produce no swapBuffer for a long time
            // or forever. The mirror then never gets a generation (mirrorCommitted=false), the cover
            // never commits, readiness can never become true and the scene never reveals: the
            // "swipe past the threshold, release, no unlock and no see-through" report, whose
            // receipt shows lockBinding raw:seq=81/base=81 (equal - re-baselined, no swap since),
            // fresh:false and mirrorCommitted=false. The desktop engine is unaffected because a live
            // wallpaper keeps swapping; this only removes an unsatisfiable wait, it does not relax
            // the freshness evidence itself (a frame is still required after a real recapture).
            synchronized (engines) { for (Engine e : engines.values()) bind(e); }
        } else if (!"renew".equals(command) || !lease.renew(token, SystemClock.uptimeMillis())) return;
        reporter.sceneSession = token; main.removeCallbacks(keepAlive); keepAlive.run(); publish();
    }
    private void requestFrames() {
        synchronized (engines) {
            for (Engine e : engines.values()) {
                bind(e);
                if (!e.valid() || request == null) continue;
                try {
                    if (request.getParameterCount() == 0) {
                        NativeReflection.invoke(request, e.service);
                        requestWrites++;
                    } else {
                        // Replay what the engine itself passes, never a constant of our own: the flag
                        // means something build-specific, and forcing it corrupted the lock wallpaper
                        // into a partly-drawn frame. Nothing observed yet means nothing to replay, and
                        // a skipped nudge is harmless - the engine still renders its visible wallpaper.
                        Boolean learned = learnedRequestFlag.get(e.service);
                        if (learned == null) { requestSkips++; continue; }
                        requestingFrame.set(Boolean.TRUE);
                        try { NativeReflection.invoke(request, e.service, learned); requestWrites++; }
                        finally { requestingFrame.remove(); }
                    }
                } catch (RuntimeException otherBuild) {
                    // A request that refuses to run must not fail the whole session.
                }
            }
        }
    }
    private void publish() {
        boolean home = false, lock = false; long homeGeneration = 0, lockGeneration = 0;
        StringBuilder detail = new StringBuilder();
        ArrayList<android.os.Bundle> frames = new ArrayList<>();
        long homeTop = 0, lockTop = 0;
        synchronized (engines) {
            for (Engine e : engines.values()) {
                bind(e);
                if ((e.which & 1) != 0 && e.valid() && e.generation >= homeGeneration) {
                    homeTop = Math.max(homeTop, e.swaps); home = e.drawn(); homeGeneration = e.generation;
                }
                if ((e.which & 2) != 0 && e.valid() && e.generation >= lockGeneration) {
                    lockTop = Math.max(lockTop, e.swaps); lock = e.drawn(); lockGeneration = e.generation;
                }
                if (e.valid() && clientBinder(e.universal) != null) {
                    android.os.Bundle frame = new android.os.Bundle();
                    frame.putInt("which", e.which); frame.putLong("generation", e.generation); frame.putBoolean("drawn", e.drawn());
                    frame.putLong("drawSequence", e.swaps); frame.putLong("baseline", e.baseline);
                    frame.putBoolean("visible", e.visible);
                    frame.putInt("containerLayer", ((Number) NativeReflection.call(e.container, "getLayerId")).intValue());
                    frame.putInt("bufferLayer", ((Number) NativeReflection.call(e.buffer, "getLayerId")).intValue());
                    frame.putBinder("client", (android.os.IBinder) clientBinder(e.universal));
                    frames.add(frame);
                }
                if (detail.length() < 350) detail.append("; ").append(e.which).append(':')
                        .append(e.service.getClass().getSimpleName()).append(" valid=").append(e.valid())
                        .append(knownShape ? " surfaceReady=" + NativeReflection.optBool(e.service, "h", false) : "")
                        .append(" visible=").append(e.visible).append(" drawn=").append(e.drawn())
                        .append(" swaps=").append(e.swaps).append(" base=").append(e.baseline);
            }
        }
        reporter.sceneSession = lease.id(); reporter.surfaceGeneration = homeGeneration;
        // The framework reads this line as fresh engine output; a repeated publish of the same
        // frame is no new frame, i.e. an idle engine that answered a request with a redraw.
        boolean progressed = homeGeneration != lastHomeGeneration ? home
                : lockGeneration != lastLockGeneration ? lock
                : home ? homeTop > lastHomeTop : lock && lockTop > lastLockTop;
        lastHomeGeneration = homeGeneration; lastLockGeneration = lockGeneration;
        lastHomeTop = homeTop; lastLockTop = lockTop;
        lastPublished = reporter.sceneReadiness;
        // What was actually sent, so the consumer's filtered view can be told apart from this one.
        record("publish seq=" + (statusSequence + 1) + " frames=" + frames.size()
                + " home=[gen=" + homeGeneration + " drawn=" + home + "] lock=[gen=" + lockGeneration + " drawn=" + lock + "]");
        reporter.sceneReadiness = "session=" + lease.id() + " homeGen=" + homeGeneration + " homeSwap=" + home
                + " reqWrites=" + requestWrites + " reqSkips=" + requestSkips + " lockGen=" + lockGeneration + " lockSwap=" + lock + (unmatchedSwaps == 0 ? "" : " unmatched=" + unmatchedSwaps) + detail
                + " journal=" + journal;
        reporter.wallpaper = "使用现有壁纸引擎输出；未替换封面或纹理"; reporter.publish();
        if (context == null || lease.id() < 0) return;
        long publishedSequence = ++statusSequence;
        for (String pkg : new String[]{"android", "com.android.systemui"})
            context.sendBroadcast(new Intent(SceneProtocol.WALLPAPER_STATUS).setPackage(pkg)
                    .putExtra("token", lease.id()).putExtra("statusSequence", publishedSequence).putExtra("homeDrawn", progressed).putExtra("lockDrawn", progressed)
                    .putExtra("homeGeneration", homeGeneration).putExtra("lockGeneration", lockGeneration)
                    .putParcelableArrayListExtra("frames", frames),
                    NativeSceneChannel.PERMISSION, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
    }
    private void end() {
        lease.clear(); main.removeCallbacks(keepAlive); reporter.sceneSession = -1;
        lastPublished = "";
        reporter.sceneReadiness = "壁纸绘制会话已撤销，EGL 由原生延时回收"; reporter.publish();
    }
    private void guarded(Runnable work) {
        try { work.run(); } catch (Throwable t) { end(); reporter.fail(t); }
    }
    private void register(java.lang.reflect.Executable method, XposedInterface.Hooker callback) {
        handles.add(module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(callback));
        reporter.hooks = handles.size();
    }
}
