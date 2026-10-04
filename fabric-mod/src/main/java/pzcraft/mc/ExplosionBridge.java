package pzcraft.mc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Materials;
import pzcraft.protocol.PzExplosion;
import pzcraft.protocol.Wire;

/**
 * Traces every Minecraft explosion through PZ's world the way vanilla traces it through blocks: 1352 rays leave the centre
 * with randomised power, lose a little every step and a lot crossing a wall, door, window, piece of furniture or upper floor
 * (priced by what PZ says it is made of, at vanilla block resistances). Whatever a ray reaches with power to spare is
 * handed to PZ, which smashes, removes or topples it. Vanilla's own pass over Minecraft blocks is untouched.
 */
public final class ExplosionBridge {
    private static final double H = Coords.BLOCKS_PER_LEVEL;
    private static volatile int blasts, lastTargets;
    private static volatile Map<String, Object> last = Map.of();

    private ExplosionBridge() {}

    /** Server thread, at the start of {@code ServerExplosion.explode()}. */
    public static void before(ServerLevel level, Vec3 center, float radius, boolean fire) {
        if (!Session.pzConnected || radius < 0.5f || MaterialField.sectionCount() == 0) return;
        long start = System.nanoTime();
        List<PzExplosion.Target> targets = trace(level, center, radius);
        Session.send(Wire.MSG_PZ_EXPLOSION, PzExplosion.encode(new PzExplosion.Blast(center.x, center.y, center.z, radius, fire, targets)));
        blasts++;
        lastTargets = targets.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("center", List.of(center.x, center.y, center.z));
        m.put("radius", radius);
        m.put("targets", targets.size());
        int[] byKind = new int[5];
        for (PzExplosion.Target t : targets) byKind[t.kind()]++;
        m.put("edges", byKind[PzExplosion.KIND_EDGE_N] + byKind[PzExplosion.KIND_EDGE_W]);
        m.put("solids", byKind[PzExplosion.KIND_SOLID]);
        m.put("floors", byKind[PzExplosion.KIND_FLOOR]);
        m.put("traceMs", Math.round((System.nanoTime() - start) / 1e4) / 100.0);
        last = m;
        PzCraftClient.LOG.info("explosion at ({}, {}, {}) radius {}: {} PZ objects reached ({} edges, {} solids, {} floors) in {} ms",
                String.format("%.1f", center.x), String.format("%.1f", center.y), String.format("%.1f", center.z), radius, targets.size(),
                m.get("edges"), m.get("solids"), m.get("floors"), m.get("traceMs"));
    }

    private static int level(double y) { return (int) Math.floor(y / H); }

    static List<PzExplosion.Target> trace(ServerLevel level, Vec3 c, float radius) {
        Set<Long> seen = new HashSet<>();
        List<PzExplosion.Target> out = new ArrayList<>();
        RandomSource random = RandomSource.create(Double.doubleToLongBits(c.x) * 31 + Double.doubleToLongBits(c.y) * 17 + Double.doubleToLongBits(c.z));
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx != 0 && xx != 15 && yy != 0 && yy != 15 && zz != 0 && zz != 15) continue;
                    double dx = xx / 15.0 * 2.0 - 1.0, dy = yy / 15.0 * 2.0 - 1.0, dz = zz / 15.0 * 2.0 - 1.0;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= length; dy /= length; dz /= length;
                    float power = radius * (0.7f + random.nextFloat() * 0.6f);
                    double x = c.x, y = c.y, z = c.z, px = x, py = y, pz = z;
                    while (power > 0f) {
                        BlockPos pos = BlockPos.containing(x, y, z);
                        if (!level.isInWorldBounds(pos)) break;
                        BlockState block = level.getBlockState(pos);
                        FluidState fluid = level.getFluidState(pos);
                        if (!block.isAir() || !fluid.isEmpty()) {
                            power -= (Math.max(block.getBlock().getExplosionResistance(), fluid.getExplosionResistance()) + 0.3f) * 0.3f;
                        }
                        power = crossings(px, py, pz, x, y, z, power, seen, out);
                        px = x; py = y; pz = z;
                        x += dx * 0.3; y += dy * 0.3; z += dz * 0.3;
                        power -= 0.225f;
                    }
                }
            }
        }
        return out;
    }

    /** PZ obstacles between two consecutive samples of a ray: tile edges, upper floors, and a solid object at the new point. */
    private static float crossings(double ax, double ay, double az, double bx, double by, double bz, float power,
                                   Set<Long> seen, List<PzExplosion.Target> out) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int tax = (int) Math.floor(ax), tbx = (int) Math.floor(bx), taz = (int) Math.floor(az), tbz = (int) Math.floor(bz);
        if (tax != tbx) { // crossed a west/east tile line
            int line = Math.max(tax, tbx);
            double t = (line - ax) / dx;
            int lvl = level(ay + t * dy), ty = (int) Math.floor(az + t * dz);
            int edge = MaterialField.edgeWest(lvl, line, ty);
            if (edge != 0) power = hit(power, PzExplosion.KIND_EDGE_W, line, ty, lvl, edge, true, seen, out);
        }
        if (taz != tbz) { // crossed a north/south tile line
            int line = Math.max(taz, tbz);
            double t = (line - az) / dz;
            int lvl = level(ay + t * dy), tx = (int) Math.floor(ax + t * dx);
            int edge = MaterialField.edgeNorth(lvl, tx, line);
            if (edge != 0) power = hit(power, PzExplosion.KIND_EDGE_N, tx, line, lvl, edge, true, seen, out);
        }
        int la = level(ay), lb = level(by);
        if (la != lb) { // crossed a storey floor
            int upper = Math.max(la, lb);
            double t = (upper * H - ay) / dy;
            int tx = (int) Math.floor(ax + t * dx), tz = (int) Math.floor(az + t * dz);
            int floor = MaterialField.floor(upper, tx, tz);
            if (upper >= 1 && floor != Materials.FLOOR_NONE && floor != Materials.FLOOR_WATER) {
                float cost = (Materials.floorResistance(floor) + 0.3f) * 0.3f;
                power -= cost;
                if (power > 0f && seen.add(key(PzExplosion.KIND_FLOOR, tx, tz, upper))) out.add(new PzExplosion.Target(PzExplosion.KIND_FLOOR, tx, tz, upper, floor));
            }
        }
        int lvl = lb, sx = tbx, sz = tbz;
        int solid = MaterialField.solid(lvl, sx, sz);
        if (solid != 0 && by - lvl * H < 1.6) power = hit(power, PzExplosion.KIND_SOLID, sx, sz, lvl, solid, false, seen, out);
        return power;
    }

    /** A ray meets a PZ object: it pays the object's resistance, destroys it if power is left, and pays for its thickness. */
    private static float hit(float power, int kind, int x, int y, int level, int value, boolean edge, Set<Long> seen,
                             List<PzExplosion.Target> out) {
        int material = edge ? Materials.edgeMaterial(value) : value;
        float cost = (Materials.resistance(material) + 0.3f) * 0.3f;
        power -= cost;
        if (power > 0f && out.size() < PzExplosion.MAX_TARGETS && seen.add(key(kind, x, y, level))) out.add(new PzExplosion.Target(kind, x, y, level, value));
        int thickness = edge ? Materials.thicknessSamples(Materials.edgeKind(value)) : 3;
        return power - cost * (thickness - 1);
    }

    private static long key(int kind, int x, int y, int level) {
        return ((long) kind << 60) ^ ((long) (level & 0xFF) << 52) ^ ((long) (x & 0x3FFFFFF) << 26) ^ (y & 0x3FFFFFFL);
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blasts", blasts);
        m.put("lastTargets", lastTargets);
        m.put("last", last);
        return m;
    }
}

