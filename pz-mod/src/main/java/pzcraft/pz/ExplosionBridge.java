package pzcraft.pz;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.PzExplosion;
import zombie.WorldSoundManager;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.interfaces.BarricadeAble;

/**
 * A Minecraft explosion reached these PZ objects (see {@link PzExplosion}): smash windows, destroy doors, remove walls,
 * fences, furniture and upper floors the way PZ's own sledgehammer does (contents spill, barricades and attached objects
 * go too), make the noise a blast makes, and tell the exporter to look again. A few targets per frame so a big blast
 * collapses over a moment instead of hitching the game.
 */
final class ExplosionBridge {
    private static final int PER_FRAME = 30;
    private static final ConcurrentLinkedQueue<PzExplosion.Blast> incoming = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<PzExplosion.Target> work = new ArrayDeque<>();
    private static volatile int blasts, windows, doors, walls, fences, solids, floors, missed, failures;
    private static volatile String lastError = "";

    private ExplosionBridge() {}

    /** Link thread. */
    static void receive(PzExplosion.Blast blast) {
        if (incoming.size() < 32) incoming.add(blast);
    }

    /** Game thread, once per frame. */
    static void tick() {
        if (incoming.isEmpty() && work.isEmpty()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        PzExplosion.Blast blast;
        while ((blast = incoming.poll()) != null) {
            blasts++;
            int level = (int) Math.floor(pzcraft.protocol.Coords.pzZ(blast.y()));
            int range = Math.max(20, (int) (blast.radius() * 30));
            WorldSoundManager.instance.addSound(null, (int) Math.floor(blast.x()), (int) Math.floor(blast.z()), Math.max(0, level), range, 1);
            work.addAll(blast.targets());
            Log.info(String.format("Minecraft explosion at (%.1f, %.1f) radius %.1f: %d PZ objects to destroy",
                    blast.x(), blast.z(), blast.radius(), blast.targets().size()));
        }
        for (int n = 0; n < PER_FRAME && !work.isEmpty(); n++) {
            PzExplosion.Target t = work.poll();
            try {
                apply(cell, t);
            } catch (RuntimeException e) {
                failures++;
                lastError = e.toString();
                if (failures <= 3) Log.error("explosion target " + t + " failed", e);
            }
        }
    }

    private static void apply(IsoCell cell, PzExplosion.Target t) {
        IsoGridSquare square = cell.getGridSquare(t.x(), t.y(), t.level());
        if (square == null) { missed++; return; }
        boolean did = switch (t.kind()) {
            case PzExplosion.KIND_EDGE_N -> destroyEdge(square, true);
            case PzExplosion.KIND_EDGE_W -> destroyEdge(square, false);
            case PzExplosion.KIND_SOLID -> destroySolid(square);
            case PzExplosion.KIND_FLOOR -> destroyFloor(square);
            default -> false;
        };
        if (did) WorldExporter.invalidate(t.x(), t.y());
        else missed++;
    }

    // ---- what stands on a tile edge ----

    private static boolean destroyEdge(IsoGridSquare sq, boolean north) {
        // 1. A door or a window: the glass breaks, the door goes; the wall around them holds this time.
        IsoObject opening = sq.getDoorOrWindow(north);
        if (opening != null) {
            if (opening instanceof IsoWindow window && !window.isDestroyed()) {
                removeBarricades(window);
                window.smashWindow(true, true);
                windows++;
                return true;
            }
            removeBarricades(opening);
            if (opening instanceof IsoDoor door) door.destroy();
            else if (opening instanceof IsoThumpable thumpable) thumpable.destroy();
            else sq.transmitRemoveItemFromSquare(opening);
            if (opening instanceof IsoWindow) windows++; else doors++;
            if (!(opening instanceof IsoWindow)) return true; // a smashed window is removed together with its wall below
        }
        // 2. The wall itself, with what hangs on it.
        IsoObject wall = sq.getWall(north);
        if (wall != null) {
            removeAttached(sq, north);
            sq.transmitRemoveItemFromSquare(wall);
            walls++;
            return true;
        }
        // 3. An empty window frame, 4. a fence, 5. a player-built wall.
        for (IsoObject o : snapshot(sq)) {
            if (o instanceof IsoWindowFrame frame && frame.getNorth() == north) {
                removeBarricades(frame);
                sq.transmitRemoveItemFromSquare(frame);
                walls++;
                return true;
            }
        }
        for (IsoObject o : snapshot(sq)) {
            if (o.getSprite() == null) continue;
            PropertyContainer p = o.getSprite().getProperties();
            if (p != null && p.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW)) {
                sq.transmitRemoveItemFromSquare(o);
                fences++;
                return true;
            }
            if (o instanceof IsoThumpable thumpable && thumpable.getNorth() == north && !thumpable.isBlockAllTheSquare()) {
                thumpable.destroy();
                walls++;
                return true;
            }
        }
        return false;
    }

    /** Posters, switches and the like on both sides of a destroyed wall (as the sledgehammer removes them). */
    private static void removeAttached(IsoGridSquare sq, boolean north) {
        IsoFlagType near = north ? IsoFlagType.attachedN : IsoFlagType.attachedW;
        IsoFlagType far = north ? IsoFlagType.attachedS : IsoFlagType.attachedE;
        removeFlagged(sq, near);
        IsoGridSquare other = sq.getAdjacentSquare(north ? zombie.iso.IsoDirections.N : zombie.iso.IsoDirections.W);
        if (other != null) removeFlagged(other, far);
    }

    private static void removeFlagged(IsoGridSquare sq, IsoFlagType flag) {
        for (IsoObject o : snapshot(sq)) {
            if (o.getSprite() != null && o.getSprite().getProperties() != null && o.getSprite().getProperties().has(flag)) {
                sq.transmitRemoveItemFromSquare(o);
            }
        }
    }

    private static void removeBarricades(Object thing) {
        if (!(thing instanceof BarricadeAble able)) return;
        IsoBarricade same = able.getBarricadeOnSameSquare(), opposite = able.getBarricadeOnOppositeSquare();
        if (same != null && same.getSquare() != null) same.getSquare().transmitRemoveItemFromSquare(same);
        if (opposite != null && opposite.getSquare() != null) opposite.getSquare().transmitRemoveItemFromSquare(opposite);
    }

    // ---- furniture, appliances, containers ----

    private static boolean destroySolid(IsoGridSquare sq) {
        boolean any = false;
        for (IsoObject o : snapshot(sq)) {
            if (o instanceof IsoTree || o instanceof IsoWorldInventoryObject || o.getSprite() == null) continue;
            PropertyContainer p = o.getSprite().getProperties();
            if (p == null || !(p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans)) || p.has(IsoFlagType.solidfloor)) continue;
            if (o instanceof IsoGenerator generator) generator.setActivated(false);
            o.dumpContentsInSquare(); // fridge, crate and shelf contents spill onto the floor
            sq.transmitRemoveItemFromSquare(o);
            any = true;
        }
        if (any) solids++;
        return any;
    }

    // ---- floors (upper storeys) ----

    private static boolean destroyFloor(IsoGridSquare sq) {
        IsoObject floor = sq.getFloor();
        if (floor == null) return false;
        floor.dumpContentsInSquare();
        sq.transmitRemoveItemFromSquare(floor);
        floors++;
        return true;
    }

    private static List<IsoObject> snapshot(IsoGridSquare sq) {
        var objects = sq.getObjects();
        List<IsoObject> list = new ArrayList<>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o != null) list.add(o);
        }
        return list;
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blasts", blasts);
        m.put("queued", work.size());
        m.put("windows", windows);
        m.put("doors", doors);
        m.put("walls", walls);
        m.put("fences", fences);
        m.put("solids", solids);
        m.put("floors", floors);
        m.put("missed", missed);
        m.put("failures", failures);
        m.put("lastError", lastError);
        return m;
    }
}

