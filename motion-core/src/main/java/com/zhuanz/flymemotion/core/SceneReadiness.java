package com.zhuanz.flymemotion.core;

/** Target-specific quorum. Wallpaper drawing alone never proves an app return scene. */
public record SceneReadiness(long session, Target target, long surfaceGeneration,
        boolean authenticated, boolean windowVisible, boolean ancestorsVisible, boolean windowDrawn,
        boolean wallpaperDrawn, boolean surfaceValid, String reason, long windowIdentity,
        long bindingEpoch, long drawSequence, boolean framePresented) {
    public enum Target { NONE, HOME, APP }
    /** The conditions {@link #ready()} requires, each with the name this state reports it under. */
    private static final String[] TERMS = {"session", "surfaceGeneration", "authenticated", "target",
            "windowIdentity", "bindingEpoch", "drawSequence", "framePresented", "surfaceValid",
            "windowVisible", "ancestorsVisible", "windowDrawn", "wallpaperDrawn"};
    /** True when the term at this index is satisfied; the same order as {@link #TERMS}. */
    private boolean satisfied(int term) {
        return switch (term) {
            case 0 -> session >= 0;
            case 1 -> surfaceGeneration > 0;
            case 2 -> authenticated;
            case 3 -> target != Target.NONE;
            case 4 -> windowIdentity > 0;
            case 5 -> bindingEpoch > 0;
            case 6 -> drawSequence >= 0;
            case 7 -> framePresented;
            case 8 -> surfaceValid;
            case 9 -> windowVisible;
            case 10 -> ancestorsVisible;
            case 11 -> windowDrawn;
            // HOME needs the desktop wallpaper itself; an APP return never does.
            default -> target != Target.HOME || wallpaperDrawn;
        };
    }
    public boolean ready() {
        for (int term = 0; term < TERMS.length; term++) if (!satisfied(term)) return false;
        return true;
    }
    /** The terms {@link #ready()} still needs, so a waiting scene names its own blocker. */
    public String missing() {
        StringBuilder out = new StringBuilder();
        for (int term = 0; term < TERMS.length; term++)
            if (!satisfied(term)) out.append(TERMS[term]).append(' ');
        return out.length() == 0 ? "none" : out.toString().trim();
    }
}
