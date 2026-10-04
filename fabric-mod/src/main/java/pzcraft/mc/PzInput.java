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
        boolean on = s != null && s.active && Session.released && net.minecraft.client.Minecraft.getInstance().gui.screen() == null;
        // A space tap shorter than a tick still jumps: PZ counts key-downs, we jump once per new one.
        boolean tapped = false;
        if (s != null) {
            if (jumpSynced) tapped = s.jumpPresses != lastJumpPresses;
            lastJumpPresses = s.jumpPresses;
            jumpSynced = true;
        }
        boolean f = on && s.has(InputState.FWD);
        boolean b = on && s.has(InputState.BACK);
        boolean l = on && s.has(InputState.LEFT);
        boolean r = on && s.has(InputState.RIGHT);
        this.keyPresses = new Input(f, b, l, r,
                on && (s.has(InputState.JUMP) || tapped), on && s.has(InputState.SNEAK), on && s.has(InputState.SPRINT));
        this.moveVector = new Vec2(impulse(l, r), impulse(f, b)).normalized();
    }
}

