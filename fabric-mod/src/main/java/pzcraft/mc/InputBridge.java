package pzcraft.mc;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import pzcraft.protocol.InputState;

/**
 * Applies PZ's mouse buttons, hotbar keys and wheel to Minecraft's own key bindings, once per client tick before the
 * game processes them, so vanilla attack, use, drop and hotbar logic runs untouched.
 *
 * <p>Presses arrive as counters, not as "is it down right now": a tap shorter than Minecraft's 50 ms tick would
 * otherwise fall between two samples and be lost, and a number key would be applied on every tick it is held.
 */
final class InputBridge {
    private static boolean synced;
    private static int lastHotbarSeq, lastAttack, lastUse, lastDrop;
    private static int prevWheel;
    private static long lastPeer;

    private InputBridge() {}

    static void apply(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.options == null) return;
        InputState s = Session.readInput();
        boolean on = Session.pzConnected && s != null && s.active && Session.released && mc.gui.screen() == null;
        Options o = mc.options;
        int b = on ? s.buttons : 0;
        o.keyAttack.setDown((b & InputState.ATTACK) != 0);
        o.keyUse.setDown((b & InputState.USE) != 0);
        if (s == null) {
            synced = false;
            return;
        }

        long peer = Session.link == null ? 0 : Session.link.peerPidOrZero();
        if (!synced || peer != lastPeer) {
            // A new PZ (or the first sample): adopt its counters without replaying presses that happened before.
            synced = true;
            lastPeer = peer;
            lastHotbarSeq = s.hotbarSeq;
            lastAttack = s.attackPresses;
            lastUse = s.usePresses;
            lastDrop = s.dropPresses;
            prevWheel = s.wheel;
            return;
        }

        int attacks = s.attackPresses - lastAttack, uses = s.usePresses - lastUse, drops = s.dropPresses - lastDrop;
        lastAttack = s.attackPresses;
        lastUse = s.usePresses;
        lastDrop = s.dropPresses;
        int wheel = s.wheel - prevWheel;
        prevWheel = s.wheel;
        // Clicks made while Minecraft cannot take them (a screen open, Steve not placed yet) are dropped, but a hotbar
        // choice waits: a number pressed just as PZ unpauses lands while the pause screen is still closing.
        if (!on) return;

        for (int i = 0; i < Math.min(attacks, 3); i++) click(o.keyAttack);
        for (int i = 0; i < Math.min(uses, 3); i++) click(o.keyUse);
        for (int i = 0; i < Math.min(drops, 3); i++) click(o.keyDrop);
        if (s.hotbarSeq != lastHotbarSeq) {
            lastHotbarSeq = s.hotbarSeq;
            if (s.hotbar >= 0 && s.hotbar < 9) p.getInventory().setSelectedSlot(s.hotbar);
        }
        if (wheel != 0) {
            int cur = p.getInventory().getSelectedSlot();
            p.getInventory().setSelectedSlot(Math.floorMod(cur - wheel, 9)); // wheel up = previous slot, like vanilla
        }
    }

    private static void click(KeyMapping m) {
        InputConstants.Key key = m.getDefaultKey();
        KeyMapping.click(key);
    }
}

