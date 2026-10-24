package pzcraft.pz;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * PZ's vehicle physics (Bullet) stands a car on the floors of the squares it is on. Where Minecraft dug the ground away the
 * square has no floor, so a car driven onto a hole fell through it into the Minecraft ground underneath, which Bullet knows
 * nothing about, and drove on inside the blocks. A car cannot go down into the hole either: PZ takes a vehicle's storey from
 * its height, and the storey below the ground has no squares. So, near cars, a hole in the ground keeps an invisible
 * physics floor at ground level and cars cross it. Only near cars: the same floor would stop blast ragdolls, which land
 * in the crater (BlastRagdolls).
 */
public final class VehicleFloors {
    /** IsoChunk.PhysicsShapes.Floor */
    private static final int FLOOR = 6;
    private static final int RADIUS = 14, EVERY = 20;
    private static volatile Set<Long> bridged = Set.of();
    private static int frame;
    private static volatile int added, refreshed, failures;

    private VehicleFloors() {}

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    /** Hole squares given a borrowed solidfloor flag while Bullet takes their chunk's shapes. */
    private static final java.util.List<IsoGridSquare> borrowed = new java.util.ArrayList<>();
    private static volatile int begins;

    /**
     * Patch_VehicleFloorChunk, on entering Bullet.beginUpdateChunk: PZ is about to give Bullet the shapes of this chunk storey,
     * square by square (IsoChunk.calcPhysics, which adds a floor for a square flagged solidfloor). The hole squares near cars
     * borrow that flag until {@link #end}. (Advice on Bullet.updateChunk or the private calcPhysics never ran under
     * ZombieBuddy.) Public: inlined into PZ's class.
     */
    public static void begin(IsoChunk c, int z) {
        begins++;
        end();
        Set<Long> set = bridged;
        if (z != 0 || set.isEmpty() || c == null) return;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            if (!set.contains(key(c.wx * 8 + x, c.wy * 8 + y))) continue;
            IsoGridSquare sq = c.getGridSquare(x, y, 0);
            if (sq == null || sq.getProperties().has(IsoFlagType.solidfloor)) continue;
            sq.getProperties().set(IsoFlagType.solidfloor);
            borrowed.add(sq);
            added++;
        }
    }

    /** Patch_VehicleFloorEnd, on entering Bullet.endUpdateChunk: the borrowed flags go back. Public: inlined into PZ's class. */
    public static void end() {
        if (borrowed.isEmpty()) return;
        for (IsoGridSquare sq : borrowed) if (sq.getFloor() == null) sq.getProperties().unset(IsoFlagType.solidfloor);
        borrowed.clear();
    }

    /** Game thread, once per frame. */
    static void tick() {
        if (++frame % EVERY != 0) return;
        try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
            Set<Long> next = new HashSet<>();
            if (cell != null && !ItemFall.holes().isEmpty()) {
                for (BaseVehicle v : cell.getVehicles()) {
                    if (v == null || v.getZ() > 0.9f) continue;
                    int vx = (int) Math.floor(v.getX()), vy = (int) Math.floor(v.getY());
                    for (long k : ItemFall.holes()) {
                        int x = (int) (k >> 32), y = (int) k;
                        if (Math.abs(x - vx) <= RADIUS && Math.abs(y - vy) <= RADIUS) next.add(k);
                    }
                }
            }
            Set<Long> old = bridged;
            if (next.equals(old)) return;
            bridged = Set.copyOf(next);
            if (cell == null) return;
            // Bullet rebuilds a chunk's shapes when PZ marks it, as it does for any square change.
            Set<IsoChunk> chunks = new HashSet<>();
            for (long k : old) if (!next.contains(k)) chunks.add(cell.getChunkForGridSquare((int) (k >> 32), (int) k, 0));
            for (long k : next) if (!old.contains(k)) chunks.add(cell.getChunkForGridSquare((int) (k >> 32), (int) k, 0));
            for (IsoChunk c : chunks) if (c != null) { c.checkPhysicsLater(0); refreshed++; }
        } catch (Throwable t) {
            if (++failures <= 3) Log.error("vehicle floors failed (" + failures + ")", t);
        }
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bridged", bridged.size());
        m.put("floorsAdded", added);
        m.put("chunksRefreshed", refreshed);
        m.put("failures", failures);
        m.put("begins", begins);
        return m;
    }
}
