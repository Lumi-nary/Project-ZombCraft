package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import zombie.characters.IsoGameCharacter;

/**
 * After Viewpoint places the eye for the frame (Eye.place since Viewpoint 0.1.6, Controls.eye before), hold it steady
 * through PZ animations while Steve drives (see {@link SteveEye}). The frame is taken as Object so this mod needs no
 * compile-time dependency on Viewpoint.
 */
@Patch(className = "viewpoint.input.Eye", methodName = "place")
public class Patch_SteadyEye {
    @OnExit
    public static void exit(@Argument(0) IsoGameCharacter character, @Argument(1) Object frame) {
        SteveEye.after(character, frame);
    }
}
