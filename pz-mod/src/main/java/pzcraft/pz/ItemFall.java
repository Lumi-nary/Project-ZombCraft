package pzcraft.pz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Coords;
import se.krka.kahlua.vm.KahluaTable;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.world.moddata.ModData;

/**
 * Items lying on a tile whose PZ floor is gone (a crater, a dug hole) fall to the Minecraft ground under it instead of
 * floating where the floor was: the contents a blast spills out of a fridge, what Steve or a zombie drops into a hole.
 * Minecraft reports the height of the solid ground under every such tile (a surface report); it is kept in PZ's own mod
 * data, saved with the world, so the items stay down after a reload. The item keeps its tile; only its height above the
 * tile (PZ's own {@code zoff}, saved with it) goes below zero. Filling the hole lifts them back onto the ground.
 */
final class ItemFall {
    private static final String TAG = "PzCraftGroundSurface";
    /** PZ storeys per second squared: Minecraft's gravity, in storeys. */
    private static final float GRAVITY = (float) (9.81 / Coords.BLOCKS_PER_LEVEL);
    private static final float EPS = 0.002f;
    private static final int SCAN_FRAMES = 15;
    private static final ConcurrentLinkedQueue<float[]> reports = new ConcurrentLinkedQueue<>();
    /** The mod data's surfaces as tile key -> Minecraft height, mirrored for the scan; reloaded when the world changes. */
    private static final Map<Long, Float> surfaces = new HashMap<>();
    private static final Map<IsoWorldInventoryObject, float[]> falling = new IdentityHashMap<>();
    private static KahluaTable table;
    private static long lastNanos;
    private static int frame;
    private static volatile int received, dropped, lifted, landed, failures;

    private ItemFall() {}

    /** Link thread. */
    static void receive(int x, int y, float surfaceMcY) {
        if (reports.size() < 16384) reports.add(new float[] {x, y, surfaceMcY});
        received++;
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xFFFFFFFFL); }

    /** Game thread: the ground-level tiles over a hole, as x << 32 | y (VehicleFloors). */
    static java.util.Set<Long> holes() { return surfaces.keySet(); }

    /** Game thread, once per frame. */
    static void tick() {
        try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
            if (cell == null) { falling.clear(); table = null; return; }
            KahluaTable current = ModData.getOrCreate(TAG);
            if (current != table) load(current);
            float[] r;
            while ((r = reports.poll()) != null) {
                int x = (int) r[0], y = (int) r[1];
                String k = x + "," + y;
                if (r[2] >= -EPS) { surfaces.remove(key(x, y)); table.rawset(k, null); }
                else { surfaces.put(key(x, y), r[2]); table.rawset(k, (double) r[2]); }
                settle(cell.getGridSquare(x, y, 0), Math.min(0f, r[2]));
            }
            if (++frame % SCAN_FRAMES == 0) {
                // Items that arrived since: dropped into the hole, spilled by a blast after its report.
                for (var e : surfaces.entrySet()) {
                    long k = e.getKey();
                    settle(cell.getGridSquare((int) (k >> 32), (int) k, 0), e.getValue());
                }
            }
            fall();
        } catch (Throwable t) {
            if (++failures <= 3) Log.error("item fall failed (" + failures + ")", t);
        }
    }

    private static void load(KahluaTable t) {
        table = t;
        surfaces.clear();
        falling.clear();
        for (var it = t.iterator(); it.advance(); ) {
            String[] xy = String.valueOf(it.getKey()).split(",");
            if (xy.length == 2 && it.getValue() instanceof Number n)
                surfaces.put(key(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])), n.floatValue());
        }
    }

    /** Starts the items on this ground-level tile falling to {@code surfaceMcY}, or lifts those now under it. */
    private static void settle(IsoGridSquare sq, float surfaceMcY) {
        if (sq == null || sq.getWorldObjects().isEmpty()) return;
        // A floor here again (restored, or a PZ floor built over the hole): the items belong on it.
        float target = sq.getFloor() != null ? 0f : (float) (surfaceMcY / Coords.BLOCKS_PER_LEVEL);
        for (IsoWorldInventoryObject o : new ArrayList<>(sq.getWorldObjects())) {
            if (o == null || o.getItem() == null) continue;
            if (o.zoff > target + EPS) {
                falling.computeIfAbsent(o, k -> new float[] {0f, target})[1] = target;
            } else if (o.zoff < target - EPS) {
                falling.remove(o);
                o.setOffset(o.xoff, o.yoff, target);
                lifted++;
            }
        }
    }

    private static void fall() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (falling.isEmpty() || dt <= 0f) return;
        for (var it = falling.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            IsoWorldInventoryObject o = e.getKey();
            float[] v = e.getValue(); // {speed, target}
            if (o.getSquare() == null || o.getItem() == null) { it.remove(); continue; }
            v[0] += GRAVITY * dt;
            float z = Math.max(v[1], o.zoff - v[0] * dt);
            o.setOffset(o.xoff, o.yoff, z);
            if (z <= v[1] + 1e-4f) { it.remove(); landed++; }
            else dropped++;
        }
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("surfaces", surfaces.size());
        m.put("falling", falling.size());
        m.put("received", received);
        m.put("landed", landed);
        m.put("lifted", lifted);
        m.put("fallSteps", dropped);
        m.put("failures", failures);
        return m;
    }
}
