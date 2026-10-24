package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
/** Viewpoint's wide-angle (Panini) correction bends PZ's world, and Minecraft's picture over it is not bent: off while Steve drives. */
@Patch(className = "viewpoint.render.Panini", methodName = "strength")
public class Patch_StevePanini {
    @OnExit public static void exit(@Return(readOnly = false) float strength) {
        if (SteveControl.drives(zombie.characters.IsoPlayer.getInstance())) strength = 0f;
    }
}
