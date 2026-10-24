package pzcraft.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import pzcraft.api.PzCraftApi;

/**
 * Area abilities of other mods that break blocks by looping over them (Viltrumite's punch crater, super punch,
 * thunderclap shockwave, hurled mobs) never reach PZ's walls and furniture, which are air to Minecraft. Their block
 * loops are watched (ViltrumiteImpacts120Mixin): every block position one call looks at grows a box, and when the call
 * returns the box is handed to PZ as a blast (PzCraftApi.breakArea), stopped by walls like an explosion. Server thread.
 */
public final class ModImpacts120 {
    private static ServerLevel level;
    private static int minX, minY, minZ, maxX, maxY, maxZ;
    private static boolean any;

    private ModImpacts120() {}

    public static void touch(ServerLevel l, BlockPos p) {
        if (!Session.pzConnected) return;
        int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!any || l != level) {
            if (any) flush();
            level = l; any = true;
            minX = maxX = x; minY = maxY = y; minZ = maxZ = z;
            return;
        }
        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }

    public static void flush() {
        if (!any) return;
        any = false;
        ServerLevel l = level;
        level = null;
        PzCraftApi.breakArea(l, new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1));
    }
}
