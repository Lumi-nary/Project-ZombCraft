package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/**
 * While Steve drives, PZ does not start its own climb through an empty window frame: the climb animation would move the camera and pull
 * the PZ player away from Steve's position, which Minecraft keeps overwriting. No positional parameters, so every
 * overload of climbThroughWindowFrame is covered. See {@link SteveControl}.
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "climbThroughWindowFrame")
public class Patch_NoPzClimbWindowFrame {
    @OnEnter(skipOn = true)
    public static boolean enter(@This IsoGameCharacter self) {
        return SteveControl.drives(self);
    }
}

