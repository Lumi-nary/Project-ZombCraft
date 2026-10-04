package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoPlayer;

/**
 * After PZ has moved the player for the frame, overwrite the result with Minecraft's pose (see PuppetDriver).
 * Must run after PZ's own movement/collision, hence the exit of postupdate rather than update.
 */
@Patch(className = "zombie.characters.IsoPlayer", methodName = "postupdate")
public class Patch_PlayerPostUpdate {
    @OnExit
    public static void exit(@This IsoPlayer player) {
        FrameTick.puppet(player);
    }
}

