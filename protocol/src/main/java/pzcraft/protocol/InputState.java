package pzcraft.protocol;

/**
 * PZ -> Minecraft: what the player is asking Steve to do this frame. Rotation is absolute and PZ-authoritative
 * (Viewpoint integrates the mouse), so the camera has no round-trip lag; Minecraft only owns position and physics.
 */
public final class InputState {
    public static final int FWD = 1;
    public static final int BACK = 1 << 1;
    public static final int LEFT = 1 << 2;
    public static final int RIGHT = 1 << 3;
    public static final int JUMP = 1 << 4;
    public static final int SNEAK = 1 << 5;
    public static final int SPRINT = 1 << 6;
    public static final int ATTACK = 1 << 7;
    public static final int USE = 1 << 8;
    public static final int DROP = 1 << 9;
    public static final int INVENTORY = 1 << 10;
    public static final int PAUSED = 1 << 11;
    /** Viewpoint is in third person: Steve's body is visible in PZ and Minecraft must not draw its first-person hand. */
    public static final int THIRD_PERSON = 1 << 12;
    /** Survival ownership persists while paused or in an inventory screen. */
    public static final int STEVE_DRIVES = 1 << 13;

    public long frame;
    public float yawDeg, pitchDeg;
    public int buttons;
    /** The hotbar slot (0..8) last chosen with a number key; -1 = none yet. Applied when {@link #hotbarSeq} changes. */
    public int hotbar = -1;
    /**
     * Press counters: incremented on every key-down in PZ. Minecraft compares them with what it saw last tick, so a tap
     * shorter than Minecraft's 50 ms tick is never lost and a selection is applied exactly once.
     */
    public int hotbarSeq, attackPresses, usePresses, dropPresses, jumpPresses;
    /** Running total of wheel clicks (positive = scroll up); a latest-value slot cannot carry deltas without losing some. */
    public int wheel;
    /** False while a PZ UI/menu owns the keyboard: Steve stands still. */
    public boolean active;
    /** Viewpoint's eye height above the feet (blocks), vertical field of view (degrees) and window aspect; 0 = unknown. */
    public float eyeHeight, fovDeg, aspect;

    public boolean has(int mask) { return (buttons & mask) != 0; }

    public void set(InputState o) {
        frame = o.frame; yawDeg = o.yawDeg; pitchDeg = o.pitchDeg; buttons = o.buttons;
        hotbar = o.hotbar; wheel = o.wheel; active = o.active;
        hotbarSeq = o.hotbarSeq; attackPresses = o.attackPresses; usePresses = o.usePresses;
        dropPresses = o.dropPresses; jumpPresses = o.jumpPresses;
        eyeHeight = o.eyeHeight; fovDeg = o.fovDeg; aspect = o.aspect;
    }

    @Override
    public String toString() {
        return String.format("InputState[frame=%d yaw=%.1f pitch=%.1f buttons=0x%x active=%b]", frame, yawDeg, pitchDeg, buttons, active);
    }
}
