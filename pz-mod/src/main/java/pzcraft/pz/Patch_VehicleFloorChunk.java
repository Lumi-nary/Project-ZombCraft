package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import zombie.iso.IsoChunk;

/** PZ starts giving Bullet a chunk storey's shapes: hole squares near cars borrow a floor flag meanwhile (VehicleFloors). */
@Patch(className = "zombie.core.physics.Bullet", methodName = "beginUpdateChunk")
public class Patch_VehicleFloorChunk {
    @OnEnter
    public static void enter(@Argument(0) IsoChunk chunk, @Argument(1) int level) {
        VehicleFloors.begin(chunk, level);
    }
}
