package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import zombie.pathfind.PolygonalMap2;

/** Keep unloaded-area goals working, but reject missing floors inside loaded chunks. */
@Patch(className = "zombie.pathfind.highLevel.HLAStar", methodName = "initGoalNode")
public class Patch_PathfindingMissingGoal {
    @OnEnter(skipOn = true)
    public static boolean enter(@Argument(0) float x, @Argument(1) float y, @Argument(2) int z) {
        return PolygonalMap2.instance.getChunkFromSquarePos((int) Math.floor(x), (int) Math.floor(y)) != null
                && PolygonalMap2.instance.getSquare((int) Math.floor(x), (int) Math.floor(y), z) == null;
    }
}
