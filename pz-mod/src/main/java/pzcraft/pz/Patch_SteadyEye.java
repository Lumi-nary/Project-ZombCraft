package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import zombie.characters.IsoGameCharacter;

/**
 * After Viewpoint places the eye for the frame (Controls.eye), hold it steady through PZ animations while Steve drives
 * (see {@link SteveEye}). The frame is taken as Object so this mod needs no compile-time dependency on Viewpoint.
 */
@Patch(className = "viewpoint.input.Controls", methodName = "eye")
public class Patch_SteadyEye {
    @OnExit
    public static void exit(@Argument(0) IsoGameCharacter character, @Argument(1) Object frame) {
        SteveEye.after(character, frame);
    }
}

