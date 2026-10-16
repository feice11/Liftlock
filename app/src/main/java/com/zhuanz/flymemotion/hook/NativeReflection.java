package com.zhuanz.flymemotion.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class NativeReflection {
    private record MethodKey(Class<?> type, String name, java.util.List<Class<?>> args) {}
    private record FieldKey(Class<?> type, String name) {}
    private static final Map<FieldKey, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<MethodKey, Method> METHODS = new ConcurrentHashMap<>();
    private NativeReflection() {}
    static Field field(Class<?> type, String name) {
        return FIELDS.computeIfAbsent(new FieldKey(type, name), key -> {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
                Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
            } catch (NoSuchFieldException ignored) {}
            throw new IllegalStateException(type.getName() + "#" + name + " missing");
        });
    }
    static Method method(Class<?> type, String name, Class<?>... args) {
        return METHODS.computeIfAbsent(new MethodKey(type, name, Arrays.asList(args)), key -> {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
                Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m;
            } catch (NoSuchMethodException ignored) {}
            throw new IllegalStateException(type.getName() + "#" + name + " missing");
        });
    }
    static Object get(Object target, String name) {
        try { return field(target.getClass(), name).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    /**
     * Reads {@code name} as declared on {@code owner} or above, not on the instance's own class.
     * A MIUI subclass redeclares some of the panel's fields, so the same name is two fields: the
     * driver this module calls writes the one on the class that declares the driver, which is the
     * owner here, while a plain get() would read the shadowing one on the subclass.
     */
    static Object getOn(Object target, Class<?> owner, String name) {
        if (owner == null) return null;
        try { return field(owner, name).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    static boolean bool(Object target, String name) { return (boolean) get(target, name); }
    static float number(Object target, String name) { return ((Number) get(target, name)).floatValue(); }
    static int integer(Object target, String name) { return ((Number) get(target, name)).intValue(); }
    static void set(Object target, String name, Object value) {
        try { field(target.getClass(), name).set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    static Object call(Object target, String name) { return invoke(method(target.getClass(), name), target); }
    // Optional members: a build without them answers the fallback instead of failing the adapter.
    private static final java.util.Set<Object> MISSING = ConcurrentHashMap.newKeySet();
    static Field fieldOrNull(Class<?> type, String name) {
        FieldKey key = new FieldKey(type, name);
        if (MISSING.contains(key)) return null;
        try { return field(type, name); } catch (IllegalStateException e) { MISSING.add(key); return null; }
    }
    static Method methodOrNull(Class<?> type, String name, Class<?>... args) {
        MethodKey key = new MethodKey(type, name, Arrays.asList(args));
        if (MISSING.contains(key)) return null;
        try { return method(type, name, args); } catch (IllegalStateException e) { MISSING.add(key); return null; }
    }
    static boolean has(Object target, String name) { return target != null && fieldOrNull(target.getClass(), name) != null; }
    static Object optGet(Object target, String name) {
        Field f = target == null ? null : fieldOrNull(target.getClass(), name);
        try { return f == null ? null : f.get(target); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    static boolean optBool(Object target, String name, boolean fallback) {
        Object value = optGet(target, name);
        return value instanceof Boolean b ? b : fallback;
    }
    /** Zero-argument call; null when the target is null or this build has no such method. */
    static Object optCall(Object target, String name) {
        Method m = target == null ? null : methodOrNull(target.getClass(), name);
        return m == null ? null : invoke(m, target);
    }
    static boolean optCallBool(Object target, String name, boolean fallback) {
        Object value = optCall(target, name);
        return value instanceof Boolean b ? b : fallback;
    }
    static Object invoke(Method method, Object receiver, Object... args) {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException e) { throw new IllegalStateException(method.getName(), e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
