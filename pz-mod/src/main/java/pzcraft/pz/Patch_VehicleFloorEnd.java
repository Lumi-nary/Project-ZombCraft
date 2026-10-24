package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;

/** The hole squares' borrowed solidfloor flags go back once Bullet has the chunk's shapes (VehicleFloors). */
@Patch(className = "zombie.core.physics.Bullet", methodName = "endUpdateChunk")
public class Patch_VehicleFloorEnd {
    @OnEnter
    public static void enter() {
        VehicleFloors.end();
    }
}
