package pzcraft.pz;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import pzcraft.protocol.InputState;

/**
 * Synthetic input from the control API (tests, automation): button holds with an expiry, hotbar selections and look
 * changes. While any hold is active, input counts as live even without mouse capture or window focus, so the game can
 * be driven in the background without touching the user's real mouse and keyboard.
 */
final class InputOverride {
    private static final CopyOnWriteArrayList<long[]> holds = new CopyOnWriteArrayList<>(); // {mask, untilMillis}
    /** A one-shot hotbar selection from the API: consumed by the next input frame, never sticky. */
    private static volatile int hotbar = -1;
    private static float pendingYawDeg, pendingPitchDeg;
    private static boolean pendingAbsYaw, pendingAbsPitch;
    private static volatile float spinDegPerSec;
    private static volatile long spinUntil;
    private static long lastSpin;

    private static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("FWD", InputState.FWD), Map.entry("BACK", InputState.BACK),
            Map.entry("LEFT", InputState.LEFT), Map.entry("RIGHT", InputState.RIGHT),
            Map.entry("JUMP", InputState.JUMP), Map.entry("SNEAK", InputState.SNEAK),
            Map.entry("SPRINT", InputState.SPRINT), Map.entry("ATTACK", InputState.ATTACK),
            Map.entry("USE", InputState.USE), Map.entry("DROP", InputState.DROP));

    private InputOverride() {}

    static int maskOf(List<?> names) {
        int m = 0;
        for (Object n : names) {
            Integer b = NAMES.get(String.valueOf(n).toUpperCase());
            if (b == null) throw new IllegalArgumentException("unknown button '" + n + "' (use " + NAMES.keySet() + ")");
            m |= b;
        }
        return m;
    }

    static void hold(int mask, long ms) {
        holds.add(new long[] {mask, System.currentTimeMillis() + Math.max(1, ms)});
    }

    static void releaseAll() { holds.clear(); }

    static int mask(long now) {
        int m = 0;
        for (long[] h : holds) {
            if (h[1] > now) m |= (int) h[0]; else holds.remove(h);
        }
        return m;
    }

    static boolean active(long now) { return mask(now) != 0; }

    static void selectHotbar(int slot) { hotbar = slot; }

    /** The pending API selection, cleared by reading it (-1 = none). */
    static int takeHotbar() {
        int h = hotbar;
        hotbar = -1;
        return h;
    }

    static boolean hotbarPending() { return hotbar >= 0; }

    /** Look changes, in Minecraft's yaw/pitch degrees (yaw 0 = south, 90 = west; pitch + = down). */
    static synchronized void look(Float yaw, Float pitch, boolean relative) {
        if (yaw != null) { pendingYawDeg = relative ? pendingYawDeg + yaw : yaw; pendingAbsYaw = !relative; }
        if (pitch != null) { pendingPitchDeg = relative ? pendingPitchDeg + pitch : pitch; pendingAbsPitch = !relative; }
    }

    /** Turn continuously (Minecraft yaw degrees per second, + = clockwise) for a while; for latency tests. */
    static void spin(float degPerSec, long ms) {
        spinDegPerSec = degPerSec;
        spinUntil = System.currentTimeMillis() + ms;
        lastSpin = 0;
    }

    /** Game thread: apply pending look changes to Viewpoint's look angles. */
    static synchronized void applyLook() {
        long now = System.currentTimeMillis();
        if (now < spinUntil) {
            if (lastSpin != 0) look(spinDegPerSec * (now - lastSpin) / 1000f, null, true);
            lastSpin = now;
        } else {
            lastSpin = 0;
        }
        if (pendingYawDeg == 0 && pendingPitchDeg == 0 && !pendingAbsYaw && !pendingAbsPitch) return;
        float yaw = ViewpointBridge.yaw();
        float pitch = ViewpointBridge.pitch();
        float newYaw = pendingAbsYaw ? pzcraft.protocol.Coords.mcYawDegToPzRad(pendingYawDeg)
                : yaw + (float) Math.toRadians(pendingYawDeg); // both yaws grow clockwise seen from above
        // pitch: MC + = down; Viewpoint + = up
        float newPitch = pendingAbsPitch ? pzcraft.protocol.Coords.mcPitchDegToPzRad(pendingPitchDeg)
                : pitch - (float) Math.toRadians(pendingPitchDeg);
        float max = (float) Math.toRadians(85);
        ViewpointBridge.setLook(newYaw, Math.max(-max, Math.min(max, newPitch)));
        pendingYawDeg = pendingPitchDeg = 0;
        pendingAbsYaw = pendingAbsPitch = false;
    }
}
