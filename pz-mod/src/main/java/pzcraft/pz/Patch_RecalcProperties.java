package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.iso.IsoGridSquare;

/** Re-apply the "a Minecraft block is here" solid flag every time PZ rebuilds a square's properties. */
@Patch(className = "zombie.iso.IsoGridSquare", methodName = "RecalcProperties")
public class Patch_RecalcProperties {
    @OnExit
    public static void exit(@This IsoGridSquare square) {
        BlockBridge.onRecalc(square);
    }
}

