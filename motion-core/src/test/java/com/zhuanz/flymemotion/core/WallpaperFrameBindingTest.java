package com.zhuanz.flymemotion.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WallpaperFrameBindingTest {
    @Test public void clientRootIsNotTheServerWindowParentOrBlastChild() {
        // Actual failing myron layout: server WindowState=55, client root=56.
        // Android's BLAST child is another surface beneath the client root.
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,8,1,56,55));
        assertTrue(WallpaperFrameBinding.matches(2,2,true,true,8,1,56,56));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,8,1,58,56));
    }
    @Test public void legacyServerCreatedClientRootAlsoMatchesItsRegisteredSurface() {
        assertTrue(WallpaperFrameBinding.matches(2,2,true,true,8,1,32,32));
    }
    @Test public void rebuiltRootAndOldWindowClientCannotQualify() {
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,8,2,56,71));
        assertFalse(WallpaperFrameBinding.matches(2,2,false,true,8,2,71,71));
    }
    @Test public void rolesAndCurrentEngineDrawRemainRequired() {
        assertFalse(WallpaperFrameBinding.matches(1,2,true,true,8,1,56,56));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,false,8,1,56,56));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,0,1,56,56));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,8,0,56,56));
        assertTrue(WallpaperFrameBinding.matches(3,1,true,true,8,1,56,56));
        assertTrue(WallpaperFrameBinding.matches(3,2,true,true,8,1,56,56));
    }
    @Test public void missingSurfacesNeverQualify() {
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,8,1,-1,-1));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,8,1,0,0));
    }
    @Test public void eitherEngineLayerMayBeTheRegisteredClientRoot() {
        // Inspected build: the engine root itself is the registered client surface.
        assertTrue(WallpaperFrameBinding.matches(2,2,true,true,8,1,56,0,56));
        // A build whose engine owns both a root and a BLAST buffer beneath it: either one may be
        // what WMS registered, and the layer that is neither still never qualifies.
        assertTrue(WallpaperFrameBinding.matches(2,2,true,true,39,2,60,61,61));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,39,2,60,61,59));
        // An unknown buffer layer does not weaken the root comparison.
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,39,2,60,-1,59));
        assertFalse(WallpaperFrameBinding.matches(2,2,true,true,39,2,60,0,59));
    }
}
