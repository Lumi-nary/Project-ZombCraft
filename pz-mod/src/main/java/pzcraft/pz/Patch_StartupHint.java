package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/** After PZ has drawn its UI (main menu or in game), add PzCraft's "what to do next" box on top of it. */
@Patch(className = "zombie.ui.UIManager", methodName = "render")
public class Patch_StartupHint {
    @OnExit
    public static void exit() {
        StartupHint.render();
    }
}
