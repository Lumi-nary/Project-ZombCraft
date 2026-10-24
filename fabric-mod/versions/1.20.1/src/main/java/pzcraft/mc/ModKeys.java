package pzcraft.mc;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;
import pzcraft.protocol.GuiEvent;

/**
 * 1.20.1 version of the shared ModKeys. Two things the hidden Minecraft window cannot see because the keyboard belongs
 * to PZ's window:
 * <ul>
 * <li>Key bindings other mods register (Viltrumite's abilities and flight, ...). Their keys are published to PZ, which
 * forwards presses, repeats and releases while Steve drives; they reach the bindings exactly as KeyboardHandler would
 * (held state and one click per press).</li>
 * <li>1.20.1 screens read modifiers by polling GLFW (Screen.hasShiftDown is InputConstants.isKeyDown on Minecraft's own
 * window), so shift-click, ctrl+Q and shift double-click never saw Shift. {@link #held} answers that poll
 * (KeyDown120Mixin) from the keys and modifier bits PZ sent.</li>
 * </ul>
 */
public final class ModKeys {
    private static final boolean[] screenKeys = new boolean[GLFW.GLFW_KEY_LAST + 1], modKeys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private static volatile int mods;
    private static Set<KeyMapping> vanilla;
    private static Set<Integer> reserved;
    private ModKeys() {}

    /** Bindings Minecraft itself declares: every KeyMapping field of Options. The rest of options.keyMappings came from mods. */
    private static void vanilla(Options o) {
        if (vanilla != null) return;
        Set<KeyMapping> set = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (var f : Options.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() == KeyMapping.class) { f.setAccessible(true); set.add((KeyMapping) f.get(o)); }
                else if (f.getType() == KeyMapping[].class) {
                    // keyHotbarSlots, not keyMappings (which holds the mods' too); compared by identity: field names are
                    // obfuscated outside the development environment
                    f.setAccessible(true);
                    var keys = (KeyMapping[]) f.get(o);
                    if (keys != o.keyMappings) Collections.addAll(set, keys);
                }
            }
        } catch (ReflectiveOperationException e) {
            PzCraftMain.LOG.warn("Vanilla key bindings unknown, other mods' keys not forwarded: {}", e.toString());
        }
        vanilla = set;
    }

    /** Keys PZ drives Steve with or Minecraft already takes another way: forwarding them would mute PZ's own polling. */
    private static Set<Integer> reserved(Options o) {
        if (reserved != null) return reserved;
        var keys = new java.util.HashSet<Integer>();
        var mine = new ArrayList<KeyMapping>(List.of(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keyShift, o.keySprint,
                o.keyDrop, o.keyInventory, o.keyChat, o.keyCommand, o.keyTogglePerspective));
        Collections.addAll(mine, o.keyHotbarSlots);
        for (var m : mine) {
            var k = KeyBindingHelper.getBoundKeyOf(m);
            if (k.getType() == InputConstants.Type.KEYSYM) keys.add(k.getValue());
        }
        keys.add(GLFW.GLFW_KEY_ESCAPE);
        return reserved = keys;
    }

    private static List<KeyMapping> foreign(Minecraft mc) {
        var o = mc.options;
        vanilla(o);
        var out = new ArrayList<KeyMapping>();
        for (var m : o.keyMappings) if (!vanilla.contains(m)) out.add(m);
        return out;
    }

    static List<Integer> keys(Minecraft mc) {
        if (mc.options == null) return List.of();
        reserved = null;   // the player may have rebound a key in Controls
        var skip = reserved(mc.options);
        var out = new ArrayList<Integer>();
        for (var m : foreign(mc)) {
            var k = KeyBindingHelper.getBoundKeyOf(m);
            if (k.getType() != InputConstants.Type.KEYSYM || k.getValue() < 0 || skip.contains(k.getValue())) continue;
            if (!out.contains(k.getValue())) out.add(k.getValue());
        }
        return out;
    }

    static boolean key(Minecraft mc, int glfw, int action) {
        if (glfw < 0 || glfw >= modKeys.length || mc.options == null) return false;
        boolean any = false, down = action != GLFW.GLFW_RELEASE;
        for (var m : foreign(mc)) {
            var k = KeyBindingHelper.getBoundKeyOf(m);
            if (k.getType() != InputConstants.Type.KEYSYM || k.getValue() != glfw) continue;
            any = true;
            m.setDown(down);
            if (action == GLFW.GLFW_PRESS) m.clickCount++;
        }
        if (any) modKeys[glfw] = down;
        return any;
    }

    static void track(int type, int value, int action, int mods, boolean screen) {
        if (type == GuiEvent.KEY && action == GLFW.GLFW_RELEASE && value >= 0 && value < modKeys.length) modKeys[value] = false;
        if (!screen) {
            // A screen closed: keys held in it may be released where PZ no longer forwards them.
            java.util.Arrays.fill(screenKeys, false);
            ModKeys.mods = 0;
            return;
        }
        if (type == GuiEvent.KEY && value >= 0 && value < screenKeys.length) screenKeys[value] = action != GLFW.GLFW_RELEASE;
        if (type == GuiEvent.KEY || type == GuiEvent.BUTTON) {
            int m = mods;
            // GLFW's modifier bits may or may not include the modifier key that is itself changing: follow the key.
            if (type == GuiEvent.KEY) {
                int bit = switch (value) {
                    case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_MOD_SHIFT;
                    case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> GLFW.GLFW_MOD_CONTROL;
                    case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_MOD_ALT;
                    case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> GLFW.GLFW_MOD_SUPER;
                    default -> 0;
                };
                if (bit != 0) m = action == GLFW.GLFW_RELEASE ? m & ~bit : m | bit;
            }
            ModKeys.mods = m;
        }
    }

    /** InputConstants.isKeyDown on Minecraft's window: what PZ says is held. */
    public static boolean held(int glfw) {
        if (!Session.pzConnected) return false;
        if (glfw >= 0 && glfw < screenKeys.length && (screenKeys[glfw] || modKeys[glfw])) return true;
        int m = mods;
        return switch (glfw) {
            case GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> (m & GLFW.GLFW_MOD_SHIFT) != 0;
            case GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL -> (m & GLFW.GLFW_MOD_CONTROL) != 0;
            case GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT -> (m & GLFW.GLFW_MOD_ALT) != 0;
            case GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER -> (m & GLFW.GLFW_MOD_SUPER) != 0;
            default -> false;
        };
    }
}
