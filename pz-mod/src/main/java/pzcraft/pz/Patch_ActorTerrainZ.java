package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;
import me.zed_0xff.zombie_buddy.Patch.Return;
import zombie.iso.IsoMovingObject;

@Patch(className="zombie.iso.IsoMovingObject",methodName="getZ")
public class Patch_ActorTerrainZ {
    @OnExit public static void exit(@This IsoMovingObject self,@Return(readOnly=false) float z){z=ActorTerrain.renderZ(self,z);}
}

