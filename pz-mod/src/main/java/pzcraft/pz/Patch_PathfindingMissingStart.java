package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import zombie.pathfind.PolygonalMap2;

/** A blast can remove the square under a pending path request; fail that request instead of dereferencing null. */
@Patch(className = "zombie.pathfind.highLevel.HLAStar", methodName = "initStartNode")
public class Patch_PathfindingMissingStart {
    @OnEnter(skipOn = true)
    public static boolean enter(@Argument(0) float x, @Argument(1) float y, @Argument(2) int z) {
        return PolygonalMap2.instance.getSquare((int) Math.floor(x), (int) Math.floor(y), z) == null;
    }
}
