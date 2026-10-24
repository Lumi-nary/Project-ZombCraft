package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;

/**
 * PzCraft's own screens (PzUi), drawn just before PZ swaps buffers: Display.update(boolean) runs on the render thread,
 * the one that owns the OpenGL context. Viewpoint draws its windows at the same point. Drawing from Display.imguiEndFrame
 * (the main loop thread, which has no context) aborted the JVM on PZ's intro.
 */
@Patch(className = "org.lwjglx.opengl.Display", methodName = "update")
public class Patch_PzUiFrame {
    @OnEnter public static void enter(boolean processMessages) { PzUi.frameEnd(); }
}
