package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Argument;
@Patch(className = "viewpoint.input.Camera", methodName = "eye")
public class Patch_SteveCameraEye {
    @OnExit public static void exit(@Argument(0) Object frame, @Argument(2) float[] eye) { SteveCamera.eye(frame, eye); }
}

