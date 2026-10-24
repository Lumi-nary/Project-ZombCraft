package pzcraft.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import pzcraft.protocol.InputState;

/**
 * 1.20.1 version of the shared PzInput: replaces the keyboard as the source of Steve's movement keys. 1.20.1's Input
 * is a set of fields with tick(sneaking, sneak factor), and sprinting is read from the sprint key (LocalPlayer.aiStep),
 * so PZ's sprint is passed through that key while PZ drives.
 */
public final class PzInput extends Input {
    private final Options options;
    private final KeyboardInput fallback;
    private int lastJumpPresses;
    private boolean jumpSynced, sprintHeld;
    /** keyUp, keyDown, keyLeft, keyRight, keyJump, keyShift as last set from PZ: other mods poll them (Viltrumite flight). */
    private final boolean[] held = new boolean[6];

    public PzInput(Options options) {
        this.options = options;
        this.fallback = new KeyboardInput(options);
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : (positive ? 1.0F : -1.0F);
    }

    /** Only changes are applied: keyShift and keySprint are toggle bindings when the player chose toggle sneak/sprint. */
    private void mirror(boolean... now) {
        var keys = new net.minecraft.client.KeyMapping[] {options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift};
        for (int i = 0; i < keys.length; i++) if (held[i] != now[i]) { keys[i].setDown(now[i]); held[i] = now[i]; }
    }

    @Override
    public void tick(boolean sneaking, float sneakFactor) {
        if (!Session.pzConnected) {
            if (sprintHeld) { options.keySprint.setDown(false); sprintHeld = false; }
            mirror(false, false, false, false, false, false);
            fallback.tick(sneaking, sneakFactor);
            up = fallback.up; down = fallback.down; left = fallback.left; right = fallback.right;
            jumping = fallback.jumping; shiftKeyDown = fallback.shiftKeyDown;
            forwardImpulse = fallback.forwardImpulse; leftImpulse = fallback.leftImpulse;
            return;
        }
        InputState s = Session.readInput();
        boolean on = s != null && s.active && Session.released && Minecraft.getInstance().screen == null;
        // A space tap shorter than a tick still jumps: PZ counts key-downs, we jump once per new one.
        boolean tapped = false;
        if (s != null) {
            if (jumpSynced) tapped = s.jumpPresses != lastJumpPresses;
            lastJumpPresses = s.jumpPresses;
            jumpSynced = true;
        }
        // Gate counted taps too: masking JUMP on the PZ side does not clear jumpPresses.
        // PZ drives the car; Minecraft keeps only sneak so the rider can get out.
        boolean walk = on && !PzCars.seat.inCar();
        up = walk && s.has(InputState.FWD);
        down = walk && s.has(InputState.BACK);
        left = walk && s.has(InputState.LEFT);
        right = walk && s.has(InputState.RIGHT);
        jumping = walk && (s.has(InputState.JUMP) || tapped);
        shiftKeyDown = on && s.has(InputState.SNEAK);
        boolean sprint = walk && s.has(InputState.SPRINT);
        if (sprint != sprintHeld) { options.keySprint.setDown(sprint); sprintHeld = sprint; }
        mirror(up, down, left, right, jumping, shiftKeyDown);
        forwardImpulse = impulse(up, down);
        leftImpulse = impulse(left, right);
        if (sneaking) {
            leftImpulse *= sneakFactor;
            forwardImpulse *= sneakFactor;
        }
    }
}
