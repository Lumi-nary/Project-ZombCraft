package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import zombie.characters.IsoGameCharacter;
/** After Viewpoint's camera step for the frame (0.1.6+): take its first-person kick zoom back out (see {@link SteveCamera#unkick}). */
@Patch(className = "viewpoint.camera.ThirdPerson", methodName = "capture")
public class Patch_SteveCameraStep {
    @OnExit public static void exit(@Argument(0) Object frame, @Argument(2) IsoGameCharacter character, @Argument(3) float aspect) {
        SteveCamera.unkick(frame, character, aspect);
    }
}
