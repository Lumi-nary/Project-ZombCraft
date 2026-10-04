package pzcraft.pz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import se.krka.kahlua.vm.KahluaTable;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.world.moddata.ModData;

/**
 * The ground under a PZ floor was dug away in Minecraft (or put back): remove that tile's floor, with the vegetation and
 * furniture standing on it, remembering which floor it was in PZ's own mod data (saved with the world) so refilling the
 * hole brings the very same floor back.
 */
final class GroundBridge {
    private static final String TAG = "PzCraftGround";
    private static final int PER_FRAME = 16;
    private static final ConcurrentLinkedQueue<int[]> edits = new ConcurrentLinkedQueue<>();
    private static volatile int removed, restored, missing;

    private GroundBridge() {}

    /** Link thread: {x, y, dug}. */
    static void receive(int[] edit) {
        if (edits.size() < 8192) edits.add(edit);
    }

    /** Game thread, once per frame. */
    static void tick() {
        if (edits.isEmpty()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        int[] e;
        for (int n = 0; n < PER_FRAME && (e = edits.poll()) != null; n++) {
            IsoGridSquare square = cell.getGridSquare(e[0], e[1], 0);
            if (square == null) { missing++; continue; }
            if (e[2] != 0) dig(square); else refill(square);
            WorldExporter.invalidate(e[0], e[1]);
        }
    }

    private static String key(IsoGridSquare sq) { return sq.getX() + "," + sq.getY(); }

    private static void dig(IsoGridSquare sq) {
        IsoObject floor = sq.getFloor();
        if (floor == null) return;
        KahluaTable memory = ModData.getOrCreate(TAG);
        memory.rawset(key(sq), floor.getSprite().getName());
        List<IsoObject> objects = new ArrayList<>(sq.getObjects().size());
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            if (o != null) objects.add(o);
        }
        for (IsoObject o : objects) {
            if (o == floor || o instanceof IsoTree || o instanceof IsoWorldInventoryObject || o.getSprite() == null) continue;
            PropertyContainer p = o.getSprite().getProperties();
            if (p != null && (p.has(IsoFlagType.vegitation) || p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans))
                    && !p.has(IsoFlagType.solidfloor)) {
                o.dumpContentsInSquare();
                sq.transmitRemoveItemFromSquare(o);
            }
        }
        sq.transmitRemoveItemFromSquare(floor);
        removed++;
    }

    private static void refill(IsoGridSquare sq) {
        if (sq.getFloor() != null) return;
        KahluaTable memory = ModData.getOrCreate(TAG);
        Object name = memory.rawget(key(sq));
        if (name instanceof String sprite && !sprite.isEmpty()) {
            sq.addFloor(sprite);
            memory.rawset(key(sq), null);
            restored++;
        }
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("floorsRemoved", removed);
        m.put("floorsRestored", restored);
        m.put("missing", missing);
        m.put("queued", edits.size());
        return m;
    }
}

