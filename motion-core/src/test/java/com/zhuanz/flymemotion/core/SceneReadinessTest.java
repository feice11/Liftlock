package com.zhuanz.flymemotion.core;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.zhuanz.flymemotion.core.SceneReadiness.Target.*;

public final class SceneReadinessTest {
    @Test public void appNeverUsesWallpaperAsSubstituteForWindowDraw() {
        assertFalse(new SceneReadiness(1, APP, 1, true, true, true, false, true, true, "", 4, 1, 2, true).ready());
        assertTrue(new SceneReadiness(1, APP, 1, true, true, true, true, false, true, "", 4, 1, 2, true).ready());
    }
    @Test public void homeRequiresActualHomeEngineAndWindow() {
        assertFalse(new SceneReadiness(1, HOME, 1, true, true, true, true, false, true, "", 4, 1, 2, true).ready());
        assertTrue(new SceneReadiness(1, HOME, 1, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void hiddenAncestorIsNotReadyEvenWithShownWindowAndBuffer() {
        assertFalse(new SceneReadiness(1, APP, 1, true, true, false, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void noAuthenticationNeverRevealsAnApp() {
        assertFalse(new SceneReadiness(1, APP, 1, false, true, true, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void rebuiltSurfaceMustHaveFreshGeneration() {
        assertFalse(new SceneReadiness(1, APP, 0, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
        assertFalse(new SceneReadiness(1, APP, 3, true, true, true, true, true, false, "", 4, 1, 2, true).ready());
    }
    @Test public void endedAndUnselectedSessionsNeverReady() {
        assertFalse(new SceneReadiness(-1, APP, 1, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
        assertFalse(new SceneReadiness(1, NONE, 1, true, true, true, true, true, true, "", 4, 1, 2, true).ready());
    }
    @Test public void historicalDrawAndResumeCannotReplacePresentedFrame() {
        assertFalse(new SceneReadiness(1, APP, 1, true, true, true, true, true, true, "", 4, 1, 2, false).ready());
        assertFalse(new SceneReadiness(1, APP, 1, true, true, true, true, true, true, "", 4, 0, 2, true).ready());
    }
    @Test public void aWaitingSceneNamesEveryTermItStillNeeds() {
        // Field order: (session, target, surfaceGeneration, authenticated, windowVisible,
        //               ancestorsVisible, windowDrawn, wallpaperDrawn, surfaceValid, reason,
        //               windowIdentity, bindingEpoch, drawSequence, framePresented)
        SceneReadiness ready = new SceneReadiness(1, APP, 1, true, true, true, true, true, true, "", 4, 1, 2, true);
        assertTrue(ready.ready());
        assertEquals("none", ready.missing());

        // The exact state measured on the OS2 device while the return window could not be presented:
        // the draw proof never started and the return window was not shown yet.
        String measured = new SceneReadiness(226787, APP, 232, true, false, false, false, true, true, "", 4, 9, -1, false).missing();
        assertTrue(measured, measured.contains("drawSequence"));
        assertTrue(measured, measured.contains("framePresented"));
        assertTrue(measured, measured.contains("windowDrawn"));

        // Flipping one required flag at a time names exactly that one term, and nothing else.
        String[] cases = {"session", "surfaceGeneration", "authenticated", "target", "windowIdentity",
            "bindingEpoch", "drawSequence", "framePresented", "surfaceValid", "windowVisible",
            "ancestorsVisible", "windowDrawn", "wallpaperDrawn"};
        for (String term : cases) {
            Object[] args = {1L, HOME, 1L, true, true, true, true, true, true, "", 4L, 1L, 2L, true};
            switch (term) {
                case "session" -> args[0] = -1L;
                case "surfaceGeneration" -> args[2] = 0L;
                case "authenticated" -> args[3] = false;
                case "target" -> args[1] = NONE;
                case "windowIdentity" -> args[10] = 0L;
                case "bindingEpoch" -> args[11] = 0L;
                case "drawSequence" -> args[12] = -1L;
                case "framePresented" -> args[13] = false;
                case "surfaceValid" -> args[8] = false;
                case "windowVisible" -> args[4] = false;
                case "ancestorsVisible" -> args[5] = false;
                case "windowDrawn" -> args[6] = false;
                case "wallpaperDrawn" -> args[7] = false;
                default -> throw new IllegalStateException(term);
            }
            SceneReadiness one = new SceneReadiness((Long) args[0], (SceneReadiness.Target) args[1], (Long) args[2],
                    (Boolean) args[3], (Boolean) args[4], (Boolean) args[5], (Boolean) args[6],
                    (Boolean) args[7], (Boolean) args[8], (String) args[9], (Long) args[10],
                    (Long) args[11], (Long) args[12], (Boolean) args[13]);
            assertEquals(term, term, one.missing());
            assertFalse(one.ready());
        }
        // The same flip on an APP return: wallpaperDrawn is a HOME requirement, never an APP one.
        SceneReadiness app = new SceneReadiness(1, APP, 1, true, true, true, true, false, true, "", 4, 1, 2, true);
        assertTrue(app.ready());
        assertEquals("none", app.missing());
    }
}
