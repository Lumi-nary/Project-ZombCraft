package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/** PZ has queued its 3D world onto the screen; its text and UI come next. The Minecraft layer goes in between. */
@Patch(className = "zombie.core.Core", methodName = "RenderOffScreenBuffer")
public class Patch_WorldOnScreen {
    @OnExit
    public static void exit() {
        Overlay.queue();
    }
}
