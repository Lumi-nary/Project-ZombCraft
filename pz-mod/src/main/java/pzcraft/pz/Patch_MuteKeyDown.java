package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;

/**
 * PZ's character controls ask "is binding X down" by name (aim, melee, attack, run...). While Steve drives, the bindings
 * that share Minecraft's keys report "up", so left click mines without PZ shoving and Ctrl sprints without PZ aiming.
 * The (String) overload only: the raw key-code queries (Viewpoint, PZ's UI) are untouched.
 */
@Patch(className = "zombie.input.GameKeyboard", methodName = "isKeyDown")
public class Patch_MuteKeyDown {
    @OnExit
    public static void exit(String keyName, @Return(readOnly = false) boolean down) {
        if (down && SteveControl.mutes(keyName)) down = false;
    }
}

