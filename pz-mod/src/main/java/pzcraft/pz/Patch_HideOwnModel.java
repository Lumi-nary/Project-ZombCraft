package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/**
 * Steve has no PZ body: while he is in control, skip Viewpoint's drawing of the local player's model (arms, torso, and
 * the shadow it casts), which would otherwise show a PZ character under the Minecraft hand.
 * Targets Viewpoint's ModelCapture.capture; if Viewpoint renames it, the patch simply stops applying.
 */
@Patch(className = "viewpoint.models.ModelCapture", methodName = "capture")
public class Patch_HideOwnModel {
    @OnEnter(skipOn = true)
    public static boolean enter(@Argument(2) IsoGameCharacter character) {
        return FrameTick.hidesOwnModel(character);
    }
}

