package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;

/** Add blocks after PZ's cached terrain and moving models, without clearing their shadow depth. */
@Patch(className = "viewpoint.render.ShadowPass", methodName = "cascade")
public class Patch_BlockShadow {
    @OnExit
    public static void exit(@This Object pass, @Argument(0) Object scene, @Argument(1) int cascade) {
        NativeBlocks.shadow(pass, scene, cascade);
    }
}

