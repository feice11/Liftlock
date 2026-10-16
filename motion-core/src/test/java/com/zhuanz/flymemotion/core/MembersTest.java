package com.zhuanz.flymemotion.core;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Member choices against fake class shapes of different system releases. */
public class MembersTest {
    // "Android 17" shape.
    @SuppressWarnings("unused")
    static class Controller17 {
        void keyguardGoingAway(int flags) {}
        void startedWakingUp(int group, int reason, boolean interactive) {}
        void ensureActivitiesVisible() {}
    }
    // "Android 15" shape.
    @SuppressWarnings("unused")
    static class Controller15 {
        void keyguardGoingAway(int display, int flags) {}
        void startedWakingUp(int group, int reason) {}
        void ensureActivitiesVisible(Object starting, int config, boolean preserve, boolean notify) {}
    }
    static class Token {}
    @SuppressWarnings("unused")
    static class Activity extends Token { void setClientVisible(boolean v) {} }
    @SuppressWarnings("unused")
    static class TokenWithVisibility { void setClientVisible(boolean v) {} }

    @SuppressWarnings("unused")
    static class Panel {
        private void initDownStates(Object e) {}
        void fling$1(float v, float s, boolean e, boolean f) {}
        void setDozing(boolean d) {}
        static void access$000(Panel p, Object e) {}
        static void _$$Nest$mendMotionEvent(Panel p, Object e, float x) {}
        int mBarState;
        String label;
        float pairA, pairB;
    }

    @Test public void firstPresentCandidateWins() {
        Members.Sig one = Members.Sig.of("keyguardGoingAway", int.class);
        Members.Sig two = Members.Sig.of("keyguardGoingAway", int.class, int.class);
        assertEquals(1, Members.method(Controller17.class, one, two).getParameterCount());
        assertEquals(2, Members.method(Controller15.class, one, two).getParameterCount());
        assertNull(Members.method(Token.class, one, two));
    }

    @Test public void overloadsFindEveryRelease() {
        assertEquals(3, Members.overloads(Controller17.class, "startedWakingUp").get(0).getParameterCount());
        assertEquals(2, Members.overloads(Controller15.class, "startedWakingUp").get(0).getParameterCount());
        assertTrue(Members.overloads(Token.class, "startedWakingUp").isEmpty());
        assertNotNull(Members.unique(Controller15.class, "ensureActivitiesVisible"));
    }

    @Test public void declaredInSearchesSubclassOwners() {
        Class<?>[] bool = {boolean.class};
        // Older shape: only the ActivityRecord subclass declares it.
        Method older = Members.declaredIn("setClientVisible", bool, Token.class, Activity.class);
        assertEquals(Activity.class, older.getDeclaringClass());
        Method newer = Members.declaredIn("setClientVisible", bool, TokenWithVisibility.class, Activity.class);
        assertEquals(TokenWithVisibility.class, newer.getDeclaringClass());
        assertNull(Members.declaredIn("setClientVisible", bool, Token.class));
    }

    @Test public void compilerSuffixesAndAccessors() {
        assertEquals("fling$1", Members.suffixed(Panel.class, "fling", float.class, float.class, boolean.class, boolean.class).getName());
        assertEquals("setDozing", Members.suffixed(Panel.class, "setDozing", boolean.class).getName());
        assertNull(Members.suffixed(Panel.class, "fling", float.class));
        // D8 nest accessor by name; javac access$NNN when it is the only one with that shape.
        assertEquals("_$$Nest$mendMotionEvent", Members.accessor(Panel.class, "endMotionEvent", Object.class, float.class).getName());
        assertEquals("access$000", Members.accessor(Panel.class, "initDownStates", Object.class).getName());
        assertNull(Members.accessor(Panel.class, "missing", int.class));
    }

    @Test public void fieldsByNameOrUniqueType() {
        assertEquals("mBarState", Members.field(Panel.class, "mStatusBarState", "mBarState").getName());
        assertNull(Members.field(Panel.class, "nothing"));
        assertEquals("label", Members.fieldOfType(Panel.class, String.class).getName());
        assertNull("two float fields are ambiguous", Members.fieldOfType(Panel.class, float.class));
    }

    interface Wake { void onStartedWakingUp(); }
    @Test public void innerClassesByStructureNotNumber() {
        ClassLoader loader = MembersTest.class.getClassLoader();
        Class<?> found = Members.inner(loader, MembersTest.class,
                c -> Wake.class.isAssignableFrom(c) && Members.declares(c, "onStartedWakingUp"));
        assertNotNull(found);
        assertTrue(Wake.class.isAssignableFrom(found));
        assertNull(Members.inner(loader, MembersTest.class, c -> false, "nothing"));
    }
    // Anonymous class: compiled as MembersTest$1 (or another number); found by structure.
    @SuppressWarnings("unused")
    private final Wake observer = new Wake() { @Override public void onStartedWakingUp() {} };

    // "Android 15" panel shape: the AOSP class declares the writer abstract and the MIUI subclass
    // that is instantiated implements it, so only the subclass has a body to hook.
    @SuppressWarnings("unused")
    static abstract class PanelBase {
        abstract void setExpandedHeightInternal(float h);
        void isTracking() {}
    }
    @SuppressWarnings("unused")
    static class MiuiPanel extends PanelBase {
        @Override void setExpandedHeightInternal(float h) {}
    }

    @Test public void abstractDeclarationIsNotTheRealMethod() {
        Members.Sig writer = Members.Sig.of("setExpandedHeightInternal", float.class);
        assertNotNull("the name is still findable on the panel", Members.method(PanelBase.class, writer));
        assertNull("but it has no body to hook", Members.concrete(PanelBase.class, writer));
        assertEquals("the implementation is the subclass's", MiuiPanel.class,
                Members.concrete(MiuiPanel.class, writer).getDeclaringClass());
        assertEquals("isTracking", Members.concrete(PanelBase.class, Members.Sig.of("isTracking")).getName());
        assertNull(Members.concrete(PanelBase.class, Members.Sig.of("nothing")));
    }

    // MIUI names the field an anonymous class is assigned to with an `m` prefix: the class becomes
    // MembersTest$mObserver$1 where the AOSP shape would be MembersTest$observer$1.
    interface Tag { void ping(); }
    @SuppressWarnings("unused")
    static class mObserver {
        static final Tag one = new Tag() { @Override public void ping() {} };
    }

    @Test public void innerClassesAlsoMatchMiuiFieldNames() {
        ClassLoader loader = MembersTest.class.getClassLoader();
        Class<?> found = Members.inner(loader, MembersTest.class, c -> Members.declares(c, "ping"), "observer");
        assertNotNull("m-prefixed field name is the MIUI shape", found);
        assertTrue(Members.declares(found, "ping"));
    }

    @Test public void prefixedLambdaIndex() {
        assertNotNull(Members.prefixed(Panel.class, "access$", Panel.class, Object.class));
        assertNull(Members.prefixed(Panel.class, "lambda$", Panel.class));
    }

    @Test public void compatLogKeepsMissesVisible() {
        CompatLog log = new CompatLog();
        log.on("壁纸跟随", "");
        log.off("区域模糊", "BlurUtils#applyBlur missing");
        log.on("区域模糊", "later success");
        log.note("壁纸跟随", "通用");
        List<CompatLog.Entry> entries = CompatLog.decode(log.encode());
        assertEquals(2, entries.size());
        assertTrue(entries.get(0).on());
        assertEquals("通用", entries.get(0).detail());
        assertFalse("an off feature stays off", entries.get(1).on());
        assertEquals("BlurUtils#applyBlur missing", entries.get(1).detail());
        assertTrue(log.anyOff());
        assertTrue(CompatLog.decode(null).isEmpty());
        log.off("a|b", "x|y\nz");
        CompatLog.Entry cleaned = CompatLog.decode(log.encode()).get(2);
        assertEquals("a/b", cleaned.feature()); assertEquals("x/y z", cleaned.detail());
    }

    @Test public void optionalFeatureUndoesOnlyItsOwnHooks() {
        CompatLog log = new CompatLog();
        List<String> handles = new ArrayList<>(List.of("core"));
        List<String> undone = new ArrayList<>();
        assertTrue(FeatureGate.optional(log, "ok", handles, undone::add, () -> handles.add("ok-hook")));
        assertFalse(FeatureGate.optional(log, "broken", handles, undone::add, () -> {
            handles.add("broken-1"); handles.add("broken-2");
            throw new IllegalStateException("X#y missing");
        }));
        assertEquals(List.of("core", "ok-hook"), handles);
        assertEquals(List.of("broken-2", "broken-1"), undone);
        List<CompatLog.Entry> entries = CompatLog.decode(log.encode());
        assertTrue(entries.get(0).on());
        assertFalse(entries.get(1).on());
        assertEquals("IllegalStateException: X#y missing", entries.get(1).detail());
    }

    @Test public void reasonUsesRootCause() {
        Throwable t = new RuntimeException("outer", new NoSuchMethodException("inner"));
        assertEquals("NoSuchMethodException: inner", FeatureGate.reason(t));
    }
}
