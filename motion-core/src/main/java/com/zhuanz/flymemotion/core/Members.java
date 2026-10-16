package com.zhuanz.flymemotion.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Member lookup with alternatives, for system builds the port was never inspected on. Every
 * decision is "does this member exist", never a device or version check. Each lookup returns
 * null when nothing matches; callers decide whether that member is required. Pure reflection,
 * so the choices are unit tested against fake class shapes.
 */
public final class Members {
    private Members() {}

    /** One candidate signature. */
    public record Sig(String name, Class<?>... params) {
        public static Sig of(String name, Class<?>... params) { return new Sig(name, params); }
        @Override public String toString() { return name + "(" + params.length + ")"; }
    }

    /** First declared (on type or a superclass) of the candidates, in order. */
    public static Method method(Class<?> type, Sig... candidates) {
        for (Sig sig : candidates) {
            Method m = declared(type, sig.name(), sig.params());
            if (m != null) return m;
        }
        return null;
    }

    /** {@code base}, else a compiler-numbered rename {@code base$1..base$9}. */
    public static Method suffixed(Class<?> type, String base, Class<?>... params) {
        Method exact = declared(type, base, params);
        if (exact != null) return exact;
        for (int i = 1; i <= 9; i++) {
            Method m = declared(type, base + "$" + i, params);
            if (m != null) return m;
        }
        return null;
    }

    /**
     * The first candidate that has a body. An abstract declaration has none to hook, and the
     * method an instance actually runs is the one its own class implements, so a caller that needs
     * the real method skips it: the panel declares the AOSP names abstract on the builds this was
     * added for and the MIUI subclass that is instantiated implements them.
     */
    public static Method concrete(Class<?> type, Sig... candidates) {
        for (Sig sig : candidates) {
            Method m = declared(type, sig.name(), sig.params());
            if (m != null && !Modifier.isAbstract(m.getModifiers())) return m;
        }
        return null;
    }

    /**
     * The synthetic static accessor an inner class uses to reach {@code outer}'s private method:
     * javac's {@code -$$Nest$m<name>} (D8 desugaring) or {@code access$NNN}. Parameters are the
     * outer instance followed by the private method's own. Null when the build calls it directly.
     */
    public static Method accessor(Class<?> outer, String name, Class<?>... params) {
        Class<?>[] full = new Class<?>[params.length + 1];
        full[0] = outer; System.arraycopy(params, 0, full, 1, params.length);
        Method nest = null, access = null; int accesses = 0;
        for (Method m : outer.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !Arrays.equals(m.getParameterTypes(), full)) continue;
            if (m.getName().endsWith("$$Nest$m" + name)) nest = m;
            else if (m.getName().startsWith("access$")) { access = m; accesses++; }
        }
        Method found = nest != null ? nest : accesses == 1 ? access : null;
        if (found != null) found.setAccessible(true);
        return found;
    }

    /** Declared on the first of {@code types} that has it; a subclass may own it on another build. */
    public static Method declaredIn(String name, Class<?>[] params, Class<?>... types) {
        for (Class<?> type : types) {
            if (type == null) continue;
            Method m = declared(type, name, params);
            if (m != null) return m;
        }
        return null;
    }

    /** Every overload with this name declared on type or a superclass, nearest first. */
    public static List<Method> overloads(Class<?> type, String name) {
        List<Method> found = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods()) {
                String key = Arrays.toString(m.getParameterTypes());
                if (m.getName().equals(name) && !seen.contains(key)) { m.setAccessible(true); found.add(m); seen.add(key); }
            }
        return found;
    }

    /** The only method named {@code name} (any signature), or null when absent or ambiguous. */
    public static Method unique(Class<?> type, String name) {
        List<Method> all = overloads(type, name);
        return all.size() == 1 ? all.get(0) : null;
    }

    /** First method whose name starts with {@code prefix} (lambda$x$7 → lambda$x$), matching params. */
    public static Method prefixed(Class<?> type, String prefix, Class<?>... params) {
        for (Method m : type.getDeclaredMethods())
            if (m.getName().startsWith(prefix) && Arrays.equals(m.getParameterTypes(), params)) {
                m.setAccessible(true); return m;
            }
        return null;
    }

    /** First of the field names found on type or a superclass. */
    public static Field field(Class<?> type, String... names) {
        for (String name : names)
            for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
                Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
            } catch (NoSuchFieldException ignored) {}
        return null;
    }

    /** The only instance field of exactly {@code kind}, or null when absent or ambiguous. */
    public static Field fieldOfType(Class<?> type, Class<?> kind) {
        Field found = null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == kind) {
                    if (found != null) return null;
                    found = f;
                }
        if (found != null) found.setAccessible(true);
        return found;
    }

    /**
     * An anonymous or Kotlin object class of {@code outer}: {@code outer$1..$40} and, for each
     * hint, {@code outer$hint$1..$9}. Matched by structure, so renumbering does not matter.
     */
    public static Class<?> inner(ClassLoader loader, Class<?> outer, Predicate<Class<?>> test, String... hints) {
        List<String> names = new ArrayList<>();
        List<String> miui = new ArrayList<>();
        for (String hint : hints) {
            names.add(outer.getName() + "$" + hint);
            for (int i = 1; i <= 9; i++) names.add(outer.getName() + "$" + hint + "$" + i);
            // An anonymous class is named after the field it is assigned to, and MIUI prefixes
            // those with `m` (mKeyguardUpdateMonitorCallback$1). Tried after the shapes above, so a
            // build that already answers keeps answering the same class.
            if (hint.isEmpty()) continue;
            String prefixed = "m" + Character.toUpperCase(hint.charAt(0)) + hint.substring(1);
            miui.add(outer.getName() + "$" + prefixed);
            for (int i = 1; i <= 9; i++) miui.add(outer.getName() + "$" + prefixed + "$" + i);
        }
        for (int i = 1; i <= 40; i++) names.add(outer.getName() + "$" + i);
        names.addAll(miui);
        for (int i = 1; i <= 40; i++) names.add(outer.getName() + "$" + i);
        for (String name : names) {
            Class<?> c = load(loader, name);
            if (c != null && test.test(c)) return c;
        }
        return null;
    }

    /** True when c declares a method with this name and parameter types. */
    public static boolean declares(Class<?> c, String name, Class<?>... params) {
        try { c.getDeclaredMethod(name, params); return true; } catch (NoSuchMethodException e) { return false; }
    }

    /** The class's outer-instance field type ({@code this$0}), or null. */
    public static Class<?> enclosingInstance(Class<?> c) {
        try { return c.getDeclaredField("this$0").getType(); } catch (NoSuchFieldException e) { return null; }
    }

    public static Class<?> load(ClassLoader loader, String... names) {
        for (String name : names) try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError ignored) {}
        return null;
    }

    static Method declared(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try {
            Method m = c.getDeclaredMethod(name, params); m.setAccessible(true); return m;
        } catch (NoSuchMethodException ignored) {}
        return null;
    }
}
