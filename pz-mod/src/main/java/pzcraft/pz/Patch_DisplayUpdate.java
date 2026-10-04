package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;

/** Draw the Minecraft overlay as the last thing before PZ swaps buffers. Matches update(boolean) only. */
@Patch(className = "org.lwjglx.opengl.Display", methodName = "update")
public class Patch_DisplayUpdate {
    @OnEnter
    public static void enter(boolean processMessages) {
        GuiInput.frame();
        Overlay.draw();
    }
}

