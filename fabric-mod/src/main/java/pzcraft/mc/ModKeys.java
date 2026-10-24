package pzcraft.mc;

import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Key bindings other mods register, and the keyboard state Minecraft's screens poll. Only the 1.20.1 back-port forwards
 * them so far (versions/1.20.1 replaces this file); here nothing is published and nothing is taken.
 */
final class ModKeys {
    private ModKeys() {}

    /** GLFW keys PZ should hand to Minecraft while Steve drives. */
    static List<Integer> keys(Minecraft mc) { return List.of(); }

    /** A forwarded key with no screen open: true when it belonged to another mod's binding. */
    static boolean key(Minecraft mc, int glfw, int action) { return false; }

    /** Every KEY and BUTTON event from PZ, before it is dispatched. */
    static void track(int type, int value, int action, int mods, boolean screen) {}
}
