package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/**
 * While Steve drives, PZ does not start its own climb down a sheet rope: the climb animation would move the camera and pull
 * the PZ player away from Steve's position, which Minecraft keeps overwriting. No positional parameters, so every
 * overload of climbDownSheetRope is covered. See {@link SteveControl}.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "climbDownSheetRope")
public class Patch_NoPzClimbRopeDown {
    @OnEnter(skipOn = true)
    public static boolean enter(@This IsoGameCharacter self) {
        return SteveControl.drives(self);
    }
}

