package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;

@Patch(className = "zombie.iso.IsoWorld", methodName = "init")
public class Patch_PathfindingInit {
    @OnEnter public static void enter() { PathfindingPolicy.select(); }
}
