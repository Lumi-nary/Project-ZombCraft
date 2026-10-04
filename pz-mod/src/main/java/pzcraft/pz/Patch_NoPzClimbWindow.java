package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/**
 * While Steve drives, PZ does not start its own climb through a window: the climb animation would move the camera and pull
 * the PZ player away from Steve's position, which Minecraft keeps overwriting. No positional parameters, so every
 * overload of climbThroughWindow is covered. See {@link SteveControl}.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "climbThroughWindow")
public class Patch_NoPzClimbWindow {
    @OnEnter(skipOn = true)
    public static boolean enter(@This IsoGameCharacter self) {
        return SteveControl.drives(self);
    }
}

