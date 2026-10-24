package pzcraft.mc;

/**
 * 1.20.1 installs its vehicle dashboard adjustment through CarHud120Mixin's Gui hooks.
 * There is no HudElementRegistry on this version, so client initialization needs no event registration.
 */
public final class HudLift {
    static void init() {}
    private HudLift() {}
}
