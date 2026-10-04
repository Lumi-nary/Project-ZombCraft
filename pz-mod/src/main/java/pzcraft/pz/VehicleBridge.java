package pzcraft.pz;

import java.util.ArrayList;
import pzcraft.protocol.Coords;
import pzcraft.protocol.VehicleHull;
import pzcraft.protocol.Wire;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoWorld;

/** Live vehicle bodies are independent of the cached tile collision sections. */
final class VehicleBridge {
    static volatile int count;
    private static long lastSent;
    private VehicleBridge() {}
    static void tick(IsoPlayer p) {
        long now = System.currentTimeMillis();
        if (now - lastSent < 50) return;
        lastSent = now;
        var hulls = new ArrayList<VehicleHull>();
        var cell = IsoWorld.instance.getCell();
        if (cell != null) for (var v : cell.getVehicles()) {
            if (v == null || v.getScript() == null || Math.hypot(v.getX() - p.getX(), v.getY() - p.getY()) > 32) continue;
            var poly = v.getPoly();
            double bottom = Coords.mcY(v.getZ());
            double height = Math.max(.5, v.getScript().getExtents().y);
            hulls.add(new VehicleHull(bottom, bottom + height, poly.x1, poly.y1, poly.x2, poly.y2,
                    poly.x3, poly.y3, poly.x4, poly.y4));
            if (hulls.size() == VehicleHull.MAX_VEHICLES) break;
        }
        count = hulls.size();
        LinkService.send(Wire.MSG_VEHICLES, VehicleHull.encode(hulls));
    }
}

