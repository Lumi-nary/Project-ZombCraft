package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/**
 * Runs once per frame, right after PZ polls the mouse (the same point Viewpoint uses to read its look input).
 * Phase 0: just report the link. Phase 1 will drive the player and Viewpoint's look angles from here.
 */
@Patch(className = "zombie.input.Mouse", methodName = "update")
public class Patch_FrameTick {
    @OnExit
    public static void exit() {
        FrameTick.run();
    }
}

