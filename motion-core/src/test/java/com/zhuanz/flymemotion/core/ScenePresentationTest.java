package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ScenePresentationTest {
    @Test public void nativePreparationBeforeFirstSurfaceNeverClaimsAFrame(){
        var scene=new ScenePresentation();scene.open(10);
        assertTrue(scene.prepareNativeExit(10));assertTrue(scene.handoff());
        assertFalse(scene.ready());assertFalse(scene.nativeOwned(true,true));
        long bound=scene.bind(10,20,30,2);assertTrue(scene.handoff());
        assertTrue(scene.drawn(bound,2,true));assertTrue(scene.presented(bound,2,true,true));
        assertFalse(scene.nativeOwned(true,true));scene.foregroundHidden(bound);assertTrue(scene.nativeOwned(true,true));
    }
    @Test public void cancelledNativePreparationKeepsOpaqueReadinessAndRejectsLateSessions(){
        var scene=new ScenePresentation();scene.open(10);assertTrue(scene.prepareNativeExit(10));
        assertTrue(scene.cancelNativeExit(10));assertFalse(scene.handoff());assertFalse(scene.ready());
        scene.end();assertFalse(scene.prepareNativeExit(10));
        scene.open(11);assertFalse(scene.prepareNativeExit(10));assertFalse(scene.cancelNativeExit(10));
    }
    @Test public void nativePreparationSurvivesSurfaceInvalidationWithoutVisualReadiness(){
        var scene=new ScenePresentation();scene.open(10);scene.prepareNativeExit(10);
        scene.invalidate();assertTrue(scene.handoff());assertFalse(scene.ready());
        long bound=scene.bind(10,20,30,2);assertTrue(scene.handoff());
        assertFalse(scene.drawn(bound,2,false));assertFalse(scene.nativeOwned(true,true));
    }
    @Test public void emptyWindowAckDoesNotPreventRealProducerProof() {
        var s=new ScenePresentation();long binding=s.bind(1,2,3,90);
        assertFalse(s.drawn(binding,91,false));
        assertTrue(s.producerPresented(binding,1,true,true));
        assertTrue(s.ready());assertEquals(1,s.drawSequence());
        assertEquals("SURFACE_BUFFER",s.proofSource());
        assertFalse(s.drawn(binding,92,true));assertTrue(s.ready());
    }
    @Test public void producerNeedsLatchFenceAndAnActualFrame() {
        var s=new ScenePresentation();long b=s.bind(1,2,3,0);
        assertFalse(s.producerPresented(b,1,false,true));
        assertFalse(s.producerPresented(b,1,true,false));
        assertFalse(s.producerPresented(b,0,true,true));
        assertFalse(s.ready());
        assertTrue(s.producerPresented(b,1,true,true));
    }
    @Test public void oldProducerCannotReviveReplacedOrCancelledBinding() {
        var s=new ScenePresentation();long old=s.bind(1,2,3,0);
        long current=s.bind(1,2,4,0);
        assertFalse(s.producerPresented(old,100,true,true));assertFalse(s.ready());
        assertTrue(s.producerPresented(current,1,true,true));
        s.end();assertFalse(s.producerPresented(current,2,true,true));assertFalse(s.ready());
    }
    @Test public void invalidationImmediatelyRetiresOutstandingProducerCallback() {
        var s=new ScenePresentation();long b=s.bind(1,2,3,0);
        s.invalidate();assertFalse(s.producerPresented(b,1,true,true));assertFalse(s.ready());
    }
    @Test public void producerProofSurvivesHandoffButNotInvalidation() {
        var s=new ScenePresentation();long b=s.bind(1,2,3,0);
        s.producerPresented(b,1,true,true);assertTrue(s.beginHandoff(b));
        s.foregroundHidden(b);assertTrue(s.nativeOwned(true,true));
        s.invalidate();assertFalse(s.ready());assertEquals("NONE",s.proofSource());
        long next=s.bind(1,2,4,0);
        assertFalse(s.producerPresented(b,2,true,true));
        assertTrue(s.producerPresented(next,1,true,true));assertTrue(s.handoff());
    }
    @Test public void historicalStateAndSyntheticTimeoutCannotProveAFrame() {
        var s = new ScenePresentation(); long epoch = s.bind(10, 20, 30, 7);
        assertFalse(s.ready());
        assertFalse(s.presented(epoch, 7, true, true));
        assertFalse(s.drawn(epoch, 6, true));
        assertFalse(s.drawn(epoch, Integer.MAX_VALUE, true));
        assertFalse(s.drawn(epoch, 7, false));
        assertEquals(ScenePresentation.Phase.PREPARING, s.phase());
        assertTrue(s.drawn(epoch, 7, true));
        assertFalse(s.presented(epoch, 7, true, false));
        assertFalse(s.presented(epoch, 7, false, true));
        assertFalse(s.ready());
        assertTrue(s.presented(epoch, 7, true, true));
        assertEquals(ScenePresentation.Phase.REVEALABLE, s.phase());
    }
    @Test public void followingAppDrawCannotOrphanTheOutstandingFence() {
        var s = new ScenePresentation(); long epoch = s.bind(1, 2, 3, 5);
        assertTrue(s.drawn(epoch, 5, true));
        assertFalse(s.drawn(epoch, 6, true));
        assertTrue(s.presented(epoch, 5, true, true));
        assertEquals(5, s.drawSequence());
    }
    @Test public void replacedWindowRejectsOldEpochAndOldWindowReceipts() {
        var s = new ScenePresentation(); long old = s.bind(1, 2, 3, 5);
        s.drawn(old, 5, true);
        long current = s.bind(1, 4, 6, 9);
        assertFalse(s.matches(1, old, 2, 3));
        assertFalse(s.presented(old, 5, true, true));
        assertFalse(s.drawn(old, 9, true));
        assertFalse(s.ready());
        assertTrue(s.matches(1, current, 4, 6));
        assertTrue(s.drawn(current, 9, true));
        assertTrue(s.presented(current, 9, true, true));
    }
    @Test public void presentationLossRestoresCoverageAndRequiresAnotherRealDraw() {
        var s = ready(); long old = s.epoch();
        s.invalidate();
        assertFalse(s.ready());
        assertFalse(s.presented(old, 5, true, true));
        long next = s.bind(1, 2, 4, 8);
        assertFalse(s.presented(old, 5, true, true));
        s.drawn(next, 8, true); assertTrue(s.presented(next, 8, true, true));
    }
    @Test public void handoffNeedsAllThreeIndependentProofsBeforeNativeOwnership() {
        var s = ready(); long epoch = s.epoch();
        assertTrue(s.beginHandoff(epoch));
        assertFalse(s.nativeOwned(true, true));
        s.foregroundHidden(epoch - 1); assertFalse(s.nativeOwned(true, true));
        s.foregroundHidden(epoch);
        assertFalse(s.nativeOwned(false, true));
        assertFalse(s.nativeOwned(true, false));
        assertTrue(s.nativeOwned(true, true));
        assertEquals(ScenePresentation.Phase.NATIVE_OWNED, s.phase());
    }
    @Test public void surfaceRebuildDuringHandoffNeverReturnsToPreAuthenticationState() {
        var s = ready(); long epoch = s.epoch(); s.beginHandoff(epoch);
        s.foregroundHidden(epoch); s.invalidate();
        assertEquals(ScenePresentation.Phase.HANDOFF, s.phase());
        assertFalse(s.nativeOwned(true, true));
        long current = s.bind(1, 2, 8, 10);
        assertTrue(s.handoff()); assertFalse(s.ready());
        s.drawn(current, 10, true); s.presented(current, 10, true, true);
        assertFalse(s.nativeOwned(true, true));
        s.foregroundHidden(current); assertTrue(s.nativeOwned(true, true));
    }
    @Test public void cancelledSessionCannotBeRevivedByDelayedCallbacks() {
        var s = ready(); long epoch = s.epoch(); s.end();
        assertFalse(s.drawn(epoch, 5, true));
        assertFalse(s.presented(epoch, 5, true, true));
        assertFalse(s.beginHandoff(epoch)); assertFalse(s.ready());
        assertEquals(ScenePresentation.Phase.ENDED, s.phase());
    }
    @Test public void peerTimeoutDoesNotRetireAnAuthenticatedHandoff() {
        var lease = new SceneLease(); lease.begin(1, 100);
        assertFalse(lease.active(2000));
        lease.renewHandoff(2000); assertTrue(lease.active(2100));
        assertEquals(1, lease.id());
        lease.clear(); lease.renewHandoff(3000); assertFalse(lease.active(3001));
    }
    @Test public void orderedReceiptsCannotExposeAnOlderFrameOrCancelANewScene() {
        var order = new SceneStatusOrder(); order.begin(1);
        assertTrue(order.accept(1, 12)); assertFalse(order.accept(1, 11));
        assertFalse(order.accept(1, 12)); assertFalse(order.accept(2, 13));
        order.begin(2); assertFalse(order.accept(1, 14));
        assertTrue(order.accept(2, 1)); assertFalse(order.accept(2, -1));
    }
    @Test public void onlyActualLockAndAodBoundariesClearRetiredSources() {
        assertFalse(LockCycle.newLock(true, false, false, true, false));
        assertTrue(LockCycle.newLock(false, false, false, true, false));
        assertTrue(LockCycle.newLock(true, true, false, true, false));
        assertTrue(LockCycle.newLock(true, false, false, true, true));
        assertFalse(LockCycle.newLock(true, true, true, true, false));
        assertFalse(LockCycle.newLock(true, false, true, true, true));
    }
    @Test public void aRefusedDrawNamesTheTermThatRefusedIt() {
        // The OS2 device measured 475 completed client draws with drawSequence still -1; the proof
        // chain has to say which term said no rather than leave that to be guessed.
        var s = new ScenePresentation(); long epoch = s.bind(1, 2, 3, 7);
        assertFalse(s.drawn(epoch - 1, 7, true));
        assertTrue(s.drawnRefusal().startsWith("epoch"));
        assertFalse(s.drawn(epoch, 7, false));
        assertEquals("no-buffer-transaction", s.drawnRefusal());
        assertFalse(s.drawn(epoch, 6, true));
        assertEquals("sequence 6<7", s.drawnRefusal());
        assertFalse(s.drawn(epoch, Integer.MAX_VALUE, true));
        assertEquals("max-sequence", s.drawnRefusal());
        assertTrue(s.drawn(epoch, 7, true));
        assertEquals("accepted 7", s.drawnRefusal());
        // Once pinned, a later frame is refused by name instead of silently.
        assertFalse(s.drawn(epoch, 8, true));
        assertEquals("pinned 8!=7", s.drawnRefusal());
        // Producer proof closes the client path, and says so.
        var p = new ScenePresentation(); long bound = p.bind(1, 2, 3, 0);
        assertTrue(p.producerPresented(bound, 1, true, true));
        assertFalse(p.drawn(bound, 5, true));
        assertEquals("producerProof", p.drawnRefusal());
    }
    private static ScenePresentation ready() {
        var s = new ScenePresentation(); long epoch = s.bind(1, 2, 3, 5);
        s.drawn(epoch, 5, true); s.presented(epoch, 5, true, true); return s;
    }
}
