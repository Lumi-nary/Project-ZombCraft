package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/** The complete opaque scene is in the g-buffer, and deferred lighting has not run yet. */
@Patch(className = "viewpoint.render.FarPass", methodName = "gbuffer")
public class Patch_BlockGbuffer {
    @OnExit
    public static void exit(@Argument(0) Object frame) { NativeBlocks.draw(frame); }
}

