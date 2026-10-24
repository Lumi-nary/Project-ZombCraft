package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

@Patch(className = "viewpoint.world.RoofGather", methodName = "begin")
public class Patch_RoofDamage {
    @OnExit public static void exit() { RoofDamage.gathered(); }
}
