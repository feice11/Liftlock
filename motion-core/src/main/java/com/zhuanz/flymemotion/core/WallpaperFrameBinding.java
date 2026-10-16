package com.zhuanz.flymemotion.core;

/**
 * Match the engine's live layer to the one WMS registered for the same client.
 *
 * <p>The engine publishes two layers of its own: its root ({@code mSurfaceControl}) and the BLAST
 * buffer beneath it ({@code mBbqSurfaceControl}). Which of the two WMS registered as the client's
 * surface is a per-build detail: on the inspected build the engine root <em>is</em> the registered
 * surface, while another build wraps it one level deeper - SurfaceFlinger shows
 * {@code MiuiKeyguardPictorialWallpaper#59 > Wallpaper BBQ wrapper#60}, where the registered root
 * is the engine container's parent. Both layers are therefore passed and either may match; the
 * window's own server surface and any unrelated layer still never do.
 */
public final class WallpaperFrameBinding {
    private WallpaperFrameBinding() {}

    public static boolean matches(int roles, int requiredRole, boolean currentClient,
                                  boolean drawn, long drawSequence, long generation,
                                  long engineRoot, long registeredClientRoot) {
        return matches(roles, requiredRole, currentClient, drawn, drawSequence, generation,
                engineRoot, 0, registeredClientRoot);
    }

    public static boolean matches(int roles, int requiredRole, boolean currentClient,
                                  boolean drawn, long drawSequence, long generation,
                                  long engineRoot, long engineBuffer, long registeredClientRoot) {
        return (roles & requiredRole) != 0 && currentClient && drawn && drawSequence > 0
                && generation > 0 && registeredClientRoot > 0
                && (engineRoot > 0 || engineBuffer > 0)
                && (engineRoot == registeredClientRoot || engineBuffer > 0 && engineBuffer == registeredClientRoot);
    }

    /** Why {@link #matches} answered what it did; see the class note for the two engine layers. */
    public static String explain(int roles, int requiredRole, boolean currentClient,
                                 boolean drawn, long drawSequence, long generation,
                                 long engineRoot, long registeredClientRoot) {
        return explain(roles, requiredRole, currentClient, drawn, drawSequence, generation,
                engineRoot, 0, registeredClientRoot);
    }

    public static String explain(int roles, int requiredRole, boolean currentClient,
                                 boolean drawn, long drawSequence, long generation,
                                 long engineRoot, long engineBuffer, long registeredClientRoot) {
        return "roles=" + roles + "/need=" + requiredRole + " role=" + ((roles & requiredRole) != 0)
                + " client=" + currentClient + " drawn=" + drawn + " seq=" + drawSequence
                + " gen=" + generation + " engine=" + engineRoot + " buffer=" + engineBuffer
                + " registered=" + registeredClientRoot
                + " layer=" + (engineRoot == registeredClientRoot || engineBuffer > 0 && engineBuffer == registeredClientRoot);
    }
}
