package pzcraft.pz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.WorldTargets;
import zombie.WorldSoundManager;
import zombie.characters.IsoPlayer;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.objects.interfaces.BarricadeAble;
import zombie.iso.sprite.IsoSprite;
import pzcraft.protocol.CollisionBoxes;
import pzcraft.protocol.Materials;

/**
 * PZ things near Steve that Minecraft can mine (W2): tall grass and small plants, bushes, loose stones, twigs, branches and
 * logs, boulders and ore deposits, and what stands on tile edges: barricades, doors, windows, walls, window frames and
 * fences. Sends them to Minecraft as {@link WorldTargets} when they change; when Steve has mined one, removes it the way
 * PZ's own actions do (ISRemoveGrass, ISRemoveBush, ISPickUpGroundCoverItem, ISPickAxeGroundCoverItem; edges as the
 * sledgehammer and {@link ExplosionBridge} do, windows smashed first) with their noise for zombies, and answers. Minecraft
 * drops the items, so PZ's are skipped (no door knobs, hinges or planks from PZ).
 */
final class MineBridge {
    /** Tiles around the player that are scanned; Steve reaches 4.5 blocks. */
    private static final int RADIUS = 6;
    private static final long SCAN_MS = 200, RESEND_MS = 1000;
    private static final ConcurrentLinkedQueue<WorldTargets.Mine> requests = new ConcurrentLinkedQueue<>();
    private static long lastScan, lastSent;
    private static int lastHash;
    private static boolean dirty;
    private static volatile int targetCount, mined, missed, failures;
    private static volatile String last = "";

    private MineBridge() {}

    /** Link thread. */
    static void receive(byte[] payload) {
        if (requests.size() < 64) requests.add(WorldTargets.Mine.decode(payload));
    }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null || !LinkService.connected()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        WorldTargets.Mine m;
        while ((m = requests.poll()) != null) {
            boolean removed = false;
            try {
                removed = mine(cell, player, m);
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 3) Log.error("mining " + m + " failed", e);
            }
            if (removed) { mined++; dirty = true; WorldExporter.invalidate(m.x(), m.y()); } else missed++;
            last = (removed ? "removed " : "missed ") + m;
            LinkService.send(pzcraft.protocol.Wire.MSG_PZ_MINED, new WorldTargets.Mined(m.seq(), removed).encode());
        }
        long now = System.currentTimeMillis();
        if (!dirty && now - lastScan < SCAN_MS) return;
        lastScan = now;
        List<WorldTargets.Target> targets = scan(cell, player);
        byte[] payload = WorldTargets.encode(targets);
        int hash = Arrays.hashCode(payload);
        if (!dirty && hash == lastHash && now - lastSent < RESEND_MS) return;
        if (LinkService.send(pzcraft.protocol.Wire.MSG_TARGETS, payload)) {
            lastHash = hash;
            lastSent = now;
            dirty = false;
            targetCount = targets.size();
        }
    }

    // ---- what a PZ object is, for Minecraft ----

    private static String name(IsoObject o) {
        IsoSprite s = o.getSprite();
        return s == null ? null : s.getName();
    }

    private static int key(IsoObject o) {
        String n = name(o);
        return n == null ? 0 : n.hashCode();
    }

    private static String customName(PropertyContainer p) { return p == null ? null : p.get("CustomName"); }

    /** Loose ground cover PZ lets you pick up by hand (not furniture), or 0. */
    private static int cover(IsoObject o, PropertyContainer p) {
        if (p == null || p.has("IsMoveAble")) return 0; // B42.21: d_generic_1, and the Stone2 / LargeStone of boulders_*, Limestone of crafting_ore
        return WorldTargets.coverVariant(customName(p));
    }

    /** A boulder or ore deposit that is a single tile (a multi-tile rock would lose one piece), or 0. */
    private static int boulder(IsoObject o, PropertyContainer p) {
        if (!o.isOres() || (p != null && p.has("IsMoveAble"))) return 0;
        if (o.getSprite() != null && o.getSprite().getSpriteGrid() != null) return 0;
        return WorldTargets.boulderVariant(customName(p));
    }

    /**
     * A bush as PZ's "Remove bush" finds it: canBeCut (B42.21: 32 of f_bushes_1's tiles, 16 wild bushes and 16 low hedges
     * that are also IsMoveAble). Hedges without canBeCut are furniture, not this.
     */
    private static boolean bush(IsoObject o, PropertyContainer p) {
        return p != null && p.has(IsoFlagType.canBeCut);
    }

    /**
     * Tall grass, small plants and loose debris PZ's "Remove grass" takes (canBeRemoved: e_newgrass, d_plants, d_generic,
     * wild farm plants). Not what only shares the flag: decals painted on the floor (attachedFloor: street cracks, grass
     * overlays), vines on walls, hedges (IsMoveAble).
     */
    private static boolean grass(IsoObject o, PropertyContainer p) {
        if (p == null || !p.has(IsoFlagType.canBeRemoved) || p.has(IsoFlagType.attachedFloor) || p.has("IsMoveAble")) return false;
        String n = name(o);
        return n == null || !(n.startsWith("blends_") || n.startsWith("f_wallvines_"));
    }

    // ---- snapshot ----

    private static List<WorldTargets.Target> scan(IsoCell cell, IsoPlayer player) {
        List<WorldTargets.Target> out = new ArrayList<>();
        int px = (int) Math.floor(player.getX()), py = (int) Math.floor(player.getY()), pz = (int) Math.floor(player.getZ());
        for (int z = pz - 1; z <= pz + 1; z++) {
            for (int y = py - RADIUS; y <= py + RADIUS; y++) {
                for (int x = px - RADIUS; x <= px + RADIUS; x++) {
                    IsoGridSquare sq = cell.getGridSquare(x, y, z);
                    if (sq == null) continue;
                    scanSquare(sq, x, y, z, out);
                    if (out.size() >= WorldTargets.MAX_TARGETS - 8) return out;
                }
            }
        }
        return out;
    }

    private static void scanSquare(IsoGridSquare sq, int x, int y, int z, List<WorldTargets.Target> out) {
        boolean grass = false, bush = false;
        IsoObject top = null;
        int topKind = 0;
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o == null || o.getSprite() == null || o instanceof IsoTree) continue;
            PropertyContainer p = o.getSprite().getProperties();
            int v;
            if ((v = cover(o, p)) != 0) out.add(coverTarget(v, x, y, z, key(o)));
            else if ((v = boulder(o, p)) != 0) out.add(boulderTarget(sq, o, v, x, y, z, key(o)));
            else if (bush(o, p)) bush = true;
            else if (grass(o, p)) grass = true;
            else if ((v = piece(o, p)) != 0) { top = o; topKind = v; } // the last one is the one on top
        }
        if (top != null) out.add(pieceTarget(top, topKind, x, y, z));
        if (bush) out.add(WorldTargets.Target.of(WorldTargets.KIND_BUSH, 0, x, y, z, 0, .05, 0, .05, .95, 1.2, .95));
        if (grass) out.add(WorldTargets.Target.of(WorldTargets.KIND_GRASS, 0, x, y, z, 0, .1, 0, .1, .9, .5, .9));
        for (boolean north : new boolean[] {true, false}) {
            Edge e = edge(sq, north);
            if (e != null) out.add(edgeTarget(e, x, y, z, north));
        }
    }

    // ---- furniture and other objects standing on a tile ----

    /**
     * Furniture PZ can pick up (IsMoveAble), or a solid object it cannot (both on the tile itself, not on its edges): 0 for
     * anything else. Not doors, windows, walls, fences, what hangs on a wall, floors or rugs.
     */
    private static int piece(IsoObject o, PropertyContainer p) {
        if (p == null || o instanceof IsoDoor || o instanceof IsoWindow || o instanceof IsoWindowFrame || o instanceof IsoBarricade
                || o instanceof zombie.iso.objects.IsoWorldInventoryObject || o.isWallSE()) return 0;
        if (o instanceof IsoThumpable t && (t.isDoor() || t.isWindow() || t.isStairs() || !t.isBlockAllTheSquare() && !p.has("IsMoveAble"))) return 0;
        IsoSprite s = o.getSprite();
        if (s.cutN || s.cutW || p.has(IsoFlagType.solidfloor) || p.has(IsoFlagType.attachedFloor)
                || p.has(IsoFlagType.attachedN) || p.has(IsoFlagType.attachedW) || p.has(IsoFlagType.attachedS) || p.has(IsoFlagType.attachedE)
                || p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW) || p.has(IsoFlagType.TallHoppableN) || p.has(IsoFlagType.TallHoppableW)) return 0;
        String moveType = p.get("MoveType"); // B42.21: WallObject, Window, WindowObject, FloorTile, FloorRug, Vegitation
        if ("WallObject".equals(moveType) || "Window".equals(moveType) || "WindowObject".equals(moveType) || "FloorTile".equals(moveType)) return 0;
        if (p.has("IsMoveAble")) return WorldTargets.KIND_FURNITURE;
        if (p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans)) return WorldTargets.KIND_SOLID;
        return 0;
    }

    /**
     * From where it stands (on the floor, or on a table: PZ's render offset) to its top surface; a piece that blocks the
     * tile as tall as its collision box (WorldExporter's "solid but see-through" height, or the storey), across the whole
     * tile, so aiming at that box aims at it.
     */
    private static WorldTargets.Target pieceTarget(IsoObject o, int kind, int x, int y, int z) {
        double storey = pzcraft.protocol.Coords.BLOCKS_PER_LEVEL;
        PropertyContainer p = o.getSprite().getProperties();
        float offset = Math.max(0f, o.getRenderYOffset()), surface = o.getSurfaceOffsetNoTable();
        boolean solid = p.has(IsoFlagType.solid), blocks = solid || p.has(IsoFlagType.solidtrans);
        double base = Math.min(storey - .25, offset / 96f * storey);
        double top = solid || blocks && surface < 4f ? storey
                : surface >= 4f ? (offset + surface) / 96f * storey : base + .5;
        top = Math.min(storey, Math.max(base + .25, top));
        double in = blocks && offset == 0 ? 0 : .05;
        if ("FloorRug".equals(p.get("MoveType"))) { base = 0; top = .06; in = .02; } // flat on the floor, like a carpet
        // W4: PZ's seating data says who can sit on it and facing which way; beds (couches too) carry PZ's bed flag
        boolean seat = zombie.seating.SeatingManager.getInstance().getTilePositionCount(o) > 0, bed = p.has(IsoFlagType.bed);
        String facing = seat ? zombie.seating.SeatingManager.getInstance().getFacingDirection(o) : p.get("Facing");
        int variant = WorldTargets.pieceVariant(PzMaterials.materialOf(o, Materials.MAT_WOOD), seat, bed, WorldTargets.face(facing));
        return WorldTargets.Target.of(kind, variant, x, y, z, key(o), in, base, in, 1 - in, top, 1 - in);
    }

    /** PZ's own pick-up for furniture (its item, to the inventory); a solid object PZ cannot move breaks, contents spilled. */
    private static boolean minePiece(IsoPlayer player, IsoGridSquare sq, WorldTargets.Mine m) {
        IsoObject found = null;
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o != null && o.getSprite() != null && key(o) == m.key() && piece(o, o.getSprite().getProperties()) == m.kind()) found = o;
        }
        if (found == null) return false;
        if (m.kind() == WorldTargets.KIND_FURNITURE) return FurnitureBridge.pickUp(player, sq, found);
        found.dumpContentsInSquare();
        sq.transmitRemoveItemFromSquare(found);
        return true;
    }

    // ---- what stands on a tile's north or west edge ----

    /** The thing on an edge Minecraft mines next, and the object PZ finds it by (for a barricade: the door or window). */
    private record Edge(int kind, int variant, IsoObject object) {}

    /** One thing per edge at a time, in the order they come off: barricade, door or window, wall or frame, fence. */
    private static Edge edge(IsoGridSquare sq, boolean north) {
        IsoObject opening = sq.getDoorOrWindow(north);
        if (opening != null) {
            if (opening instanceof BarricadeAble able && able.isBarricaded()) {
                IsoBarricade same = able.getBarricadeOnSameSquare(), other = able.getBarricadeOnOppositeSquare();
                boolean metal = same != null && (same.isMetal() || same.isMetalBar()) || other != null && (other.isMetal() || other.isMetalBar());
                int planks = (same != null ? same.getNumPlanks() : 0) + (other != null ? other.getNumPlanks() : 0);
                return new Edge(WorldTargets.KIND_BARRICADE, WorldTargets.barricadeVariant(metal, north, planks), opening);
            }
            if (opening instanceof IsoWindow w)
                return new Edge(WorldTargets.KIND_WINDOW, WorldTargets.edgeVariant(Materials.MAT_GLASS, north, w.isDestroyed()), w);
            if (opening instanceof IsoDoor d) {
                return d.IsOpen() ? null : new Edge(WorldTargets.KIND_DOOR,
                        WorldTargets.edgeVariant(PzMaterials.materialOf(d, Materials.MAT_WOOD), north, false), d);
            }
            if (opening instanceof IsoThumpable t && t.isDoor()) {
                return t.IsOpen() ? null : new Edge(WorldTargets.KIND_DOOR,
                        WorldTargets.edgeVariant(PzMaterials.materialOf(t, Materials.MAT_WOOD), north, false), t);
            }
            return new Edge(WorldTargets.KIND_WALL, WorldTargets.edgeVariant(PzMaterials.materialOf(opening, Materials.MAT_WOOD), north, false), opening);
        }
        IsoObject wall = sq.getWall(north);
        if (wall != null) return new Edge(WorldTargets.KIND_WALL, WorldTargets.edgeVariant(PzMaterials.materialOf(wall, Materials.MAT_WOOD), north, false), wall);
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o instanceof IsoWindowFrame frame && frame.getNorth() == north)
                return new Edge(WorldTargets.KIND_WALL, WorldTargets.edgeVariant(PzMaterials.materialOf(o, Materials.MAT_WOOD), north, false), o);
        }
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            PropertyContainer p = o == null || o.getSprite() == null ? null : o.getSprite().getProperties();
            if (p == null) continue;
            boolean low = p.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW);
            boolean tall = p.has(north ? IsoFlagType.TallHoppableN : IsoFlagType.TallHoppableW);
            if (low || tall) return new Edge(WorldTargets.KIND_FENCE,
                    WorldTargets.edgeVariant(PzMaterials.materialOf(o, Materials.MAT_WOOD), north, low && !tall), o);
        }
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i) instanceof IsoThumpable t && t.getNorth() == north && !t.isDoor() && !t.isBlockAllTheSquare() && !t.isStairs())
                return new Edge(WorldTargets.KIND_WALL, WorldTargets.edgeVariant(PzMaterials.materialOf(t, Materials.MAT_WOOD), north, false), t);
        }
        return null;
    }

    /** The same box as the edge's collision (CollisionBoxes), so aiming at a wall's collision aims at the wall. */
    private static WorldTargets.Target edgeTarget(Edge e, int x, int y, int z, boolean north) {
        double half = CollisionBoxes.EDGE_HALF;
        double top = e.kind() == WorldTargets.KIND_FENCE && WorldTargets.edgeFlag(e.variant()) ? CollisionBoxes.LOW_EDGE
                : pzcraft.protocol.Coords.BLOCKS_PER_LEVEL;
        return north ? WorldTargets.Target.of(e.kind(), e.variant(), x, y, z, key(e.object()), 0, 0, -half, 1, top, half)
                : WorldTargets.Target.of(e.kind(), e.variant(), x, y, z, key(e.object()), -half, 0, 0, half, top, 1);
    }

    /** Takes the edge thing off the way PZ's sledgehammer and explosions do, PZ's own drops left out. */
    private static boolean mineEdge(IsoGridSquare sq, WorldTargets.Mine m) {
        boolean north = WorldTargets.edgeNorth(m.variant());
        Edge e = edge(sq, north);
        if (e == null || e.kind() != m.kind() || key(e.object()) != m.key()) return false;
        IsoObject o = e.object();
        switch (m.kind()) {
            case WorldTargets.KIND_BARRICADE -> ExplosionBridge.removeBarricades(o);
            case WorldTargets.KIND_WINDOW -> {
                IsoWindow w = (IsoWindow) o;
                if (!w.isDestroyed()) {
                    w.smashWindow();                 // the glass breaks (PZ's sound, alarm and noise); the frame stays
                } else {
                    sq.transmitRemoveItemFromSquare(w); // a smashed window goes with the wall it sits in
                    IsoObject wall = sq.getWall(north);
                    if (wall != null) {
                        ExplosionBridge.removeAttached(sq, north);
                        sq.transmitRemoveItemFromSquare(wall);
                    }
                }
            }
            case WorldTargets.KIND_DOOR, WorldTargets.KIND_WALL, WorldTargets.KIND_FENCE -> {
                if (o instanceof IsoThumpable t) t.dumpContentsInSquare();
                else if (m.kind() == WorldTargets.KIND_WALL && !(o instanceof IsoWindowFrame)) ExplosionBridge.removeAttached(sq, north);
                sq.transmitRemoveItemFromSquare(o);
            }
            default -> { return false; }
        }
        return true;
    }

    private static WorldTargets.Target coverTarget(int v, int x, int y, int z, int key) {
        return switch (v) {
            case WorldTargets.COVER_LARGE_STONE, WorldTargets.COVER_LARGE_STONE_TWIGS ->
                    WorldTargets.Target.of(WorldTargets.KIND_COVER, v, x, y, z, key, .2, 0, .2, .8, .4, .8);
            case WorldTargets.COVER_TWIGS, WorldTargets.COVER_BRANCH ->
                    WorldTargets.Target.of(WorldTargets.KIND_COVER, v, x, y, z, key, .15, 0, .15, .85, .2, .85);
            case WorldTargets.COVER_LOG -> WorldTargets.Target.of(WorldTargets.KIND_COVER, v, x, y, z, key, .05, 0, .05, .95, .45, .95);
            default -> WorldTargets.Target.of(WorldTargets.KIND_COVER, v, x, y, z, key, .25, 0, .25, .75, .25, .75);
        };
    }

    /**
     * As big as the tile's collision box when the boulder blocks the tile (so aiming at that box mines it: the same top as
     * WorldExporter gives a "solid but see-through" tile), else knee high.
     */
    private static WorldTargets.Target boulderTarget(IsoGridSquare sq, IsoObject o, int v, int x, int y, int z, int key) {
        double storey = pzcraft.protocol.Coords.BLOCKS_PER_LEVEL, top = 1.0;
        if (sq.isSolid()) top = storey;
        else if (sq.isSolidTrans()) {
            float px = o.getSurfaceOffsetNoTable();
            top = px < 4f ? storey : Math.min(storey, px / 96f * storey);
        }
        return WorldTargets.Target.of(WorldTargets.KIND_BOULDER, v, x, y, z, key, 0, 0, 0, 1, top, 1);
    }

    // ---- mining ----

    private static boolean mine(IsoCell cell, IsoPlayer player, WorldTargets.Mine m) {
        if (Math.hypot(m.x() + .5 - player.getX(), m.y() + .5 - player.getY()) > RADIUS + 4 || Math.abs(m.level() - player.getZ()) > 2) return false;
        IsoGridSquare sq = cell.getGridSquare(m.x(), m.y(), m.level());
        if (sq == null) return false;
        if (m.kind() == WorldTargets.KIND_FURNITURE || m.kind() == WorldTargets.KIND_SOLID) {
            if (!minePiece(player, sq, m)) return false;
            WorldSoundManager.instance.addSound(player, m.x(), m.y(), m.level(), 10, 5);
            return true;
        }
        if (WorldTargets.isEdge(m.kind())) {
            if (!mineEdge(sq, m)) return false;
            WorldSoundManager.instance.addSound(player, m.x(), m.y(), m.level(), m.kind() == WorldTargets.KIND_FENCE ? 10 : 20, 10);
            return true;
        }
        List<IsoObject> remove = new ArrayList<>();
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o == null || o.getSprite() == null || o instanceof IsoTree) continue;
            PropertyContainer p = o.getSprite().getProperties();
            boolean match = switch (m.kind()) {
                case WorldTargets.KIND_COVER -> key(o) == m.key() && cover(o, p) != 0;
                case WorldTargets.KIND_BOULDER -> key(o) == m.key() && boulder(o, p) != 0;
                case WorldTargets.KIND_BUSH -> cover(o, p) == 0 && boulder(o, p) == 0 && bush(o, p);
                case WorldTargets.KIND_GRASS -> cover(o, p) == 0 && boulder(o, p) == 0 && !bush(o, p) && grass(o, p);
                default -> false;
            };
            if (!match) continue;
            remove.add(o);
            if (m.kind() == WorldTargets.KIND_COVER || m.kind() == WorldTargets.KIND_BOULDER) break; // that one object only
        }
        if (remove.isEmpty()) return false;
        for (IsoObject o : remove) sq.transmitRemoveItemFromSquare(o);
        // PZ's actions make noise zombies hear: pulling grass or a bush, picking stones up, a pick on rock.
        int radius = m.kind() == WorldTargets.KIND_BOULDER ? 20 : 10;
        WorldSoundManager.instance.addSound(player, m.x(), m.y(), m.level(), radius, 5);
        return true;
    }

    static Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("targets", targetCount);
        s.put("mined", mined);
        s.put("missed", missed);
        s.put("failures", failures);
        s.put("pending", requests.size());
        s.put("last", last);
        return s;
    }
}
