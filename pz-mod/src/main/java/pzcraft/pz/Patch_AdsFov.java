package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;

/** Zoom while Steve aims down his sights (first person only; the hands keep their own projection). */
@Patch(className = "viewpoint.core.View", methodName = "fovY")
public class Patch_AdsFov {
    @OnExit
    public static void exit(@Argument(0) boolean thirdPerson, @Return(readOnly = false) float fov) {
        if (!thirdPerson) fov = AdsZoom.fov(fov);
    }
}

