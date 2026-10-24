package pzcraft.mc;

import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import pzcraft.protocol.InputState;

/**
 * Replaces the keyboard as the source of Steve's movement keys. While PZ is connected, keys come from PZ's
 * {@link InputState}; with no PZ (plain development runs) it falls back to the real keyboard.
 */
public final class PzInput extends ClientInput {
    private final KeyboardInput fallback;
    private int lastJumpPresses;
    private boolean jumpSynced;

    public PzInput(Options options) {
        this.fallback = new KeyboardInput(options);
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0F : (positive ? 1.0F : -1.0F);
    }

    @Override
    public void tick() {
        if (!Session.pzConnected) {
            fallback.tick();
            this.keyPresses = fallback.keyPresses;
            this.moveVector = fallback.getMoveVector();
            return;
        }
        InputState s = Session.readInput();
        //#if MC_26_1
        //$ boolean on = s != null && s.active && Session.released && net.minecraft.client.Minecraft.getInstance().screen == null;
        //#else
        boolean on = s != null && s.active && Session.released && net.minecraft.client.Minecraft.getInstance().gui.screen() == null;
        //#endif
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
        boolean f = walk && s.has(InputState.FWD);
        boolean b = walk && s.has(InputState.BACK);
        boolean l = walk && s.has(InputState.LEFT);
        boolean r = walk && s.has(InputState.RIGHT);
        this.keyPresses = new Input(f, b, l, r,
                walk && (s.has(InputState.JUMP) || tapped), on && s.has(InputState.SNEAK), walk && s.has(InputState.SPRINT));
        this.moveVector = new Vec2(impulse(l, r), impulse(f, b)).normalized();
    }
}
