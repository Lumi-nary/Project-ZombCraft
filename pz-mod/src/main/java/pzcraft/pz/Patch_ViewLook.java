package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/**
 * Viewpoint re-reads the mouse right before it draws the 3D view (late latching). Note the look direction it ends up
 * drawing with, so the Minecraft blocks can be re-projected to exactly the camera PZ's picture was drawn through.
 */
@Patch(className = "viewpoint.input.Look", methodName = "readBeforeDrawing")
public class Patch_ViewLook {
    @OnExit
    public static void exit() {
        NativeBlocks.beginFrame();
        NativeEntities.beginFrame();
        Overlay.noteViewLook();
    }
}

