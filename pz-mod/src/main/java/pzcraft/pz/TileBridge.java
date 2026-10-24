package pzcraft.pz;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldTargets;
import zombie.characters.IsoPlayer;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Walls and fences from Minecraft's creative tabs: placed on a tile's north or west edge as PZ's own built walls are
 * (ISWoodenWall: an IsoThumpable with health, {@code PzCraftMoveables.placeWall}), so PZ's zombies thump and break them
 * like any player-built wall. The tabs carry each style's west-edge sprite; on a north edge its north twin is used (PZ's
 * sprites come in W/N pairs, nearly always next to each other). The edge must be free.
 */
final class TileBridge {
    private static final ConcurrentLinkedQueue<WorldTargets.PlaceTile> requests = new ConcurrentLinkedQueue<>();
    private static volatile int placed, refused, failures;
    private static volatile String last = "";

    private TileBridge() {}

    /** Link thread. */
    static void receive(byte[] payload) {
        if (requests.size() < 16) requests.add(WorldTargets.PlaceTile.decode(payload));
    }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null || !LinkService.connected()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        WorldTargets.PlaceTile p;
        while ((p = requests.poll()) != null) {
            boolean ok = false;
            try {
                ok = place(cell, player, p);
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 3) Log.error("placing " + p + " failed", e);
            }
            if (ok) placed++; else refused++;
            LinkService.send(Wire.MSG_TILE_PLACED, new WorldTargets.Placed(p.seq(), ok).encode());
        }
    }

    private static boolean place(IsoCell cell, IsoPlayer player, WorldTargets.PlaceTile p) {
        if (Math.hypot(p.x() + .5 - player.getX(), p.y() + .5 - player.getY()) > 8 || Math.abs(p.level() - player.getZ()) > 2) return false;
        IsoGridSquare sq = cell.getGridSquare(p.x(), p.y(), p.level());
        if (sq == null) { last = "no square at " + p.x() + "," + p.y() + "," + p.level(); return false; }
        String sprite = p.north() ? twin(p.sprite()) : p.sprite();
        if (sprite == null) { last = p.sprite() + " has no north twin"; return false; }
        if (!edgeFree(sq, p.north())) { last = "edge taken at " + p.x() + "," + p.y(); return false; }
        IsoSprite spr = IsoSpriteManager.instance.namedMap.get(sprite);
        if (spr == null) { last = "PZ has no sprite " + sprite; return false; }
        PropertyContainer props = spr.getProperties();
        boolean fence = props.has(IsoFlagType.HoppableN) || props.has(IsoFlagType.HoppableW) || props.has(IsoFlagType.TallHoppableN) || props.has(IsoFlagType.TallHoppableW);
        boolean low = props.has(IsoFlagType.HoppableN) || props.has(IsoFlagType.HoppableW);
        int material = PzMaterials.materialOfSprite(spr, pzcraft.protocol.Materials.MAT_WOOD);
        boolean ok = Boolean.TRUE.equals(FurnitureBridge.call("placeWall", sq, sprite, p.north(), (double) health(material, fence, low),
                fence, low, fence ? "Fence" : "Wall"));
        last = (ok ? "placed " : "PZ did not place ") + sprite + " on the " + (p.north() ? "north" : "west") + " edge of " + p.x() + "," + p.y();
        if (ok) WorldExporter.invalidate(p.x(), p.y());
        return ok;
    }

    /**
     * Health of a placed wall or fence, PZ's numbers for built walls (a wooden one 200-700 by Woodwork skill): tougher
     * for brick and metal, weaker for glass, plaster and low fences. PZ's zombies take one point per thump when eight
     * thump together, a fraction when fewer do (IsoThumpable.Thump).
     */
    static int health(int material, boolean fence, boolean low) {
        int h = switch (material) {
            case pzcraft.protocol.Materials.MAT_BRICK, pzcraft.protocol.Materials.MAT_STONE, pzcraft.protocol.Materials.MAT_CERAMIC -> 800;
            case pzcraft.protocol.Materials.MAT_METAL -> 600;
            case pzcraft.protocol.Materials.MAT_METAL_HEAVY -> 900;
            case pzcraft.protocol.Materials.MAT_PLASTER -> 250;
            case pzcraft.protocol.Materials.MAT_GLASS, pzcraft.protocol.Materials.MAT_FABRIC, pzcraft.protocol.Materials.MAT_NATURAL -> 150;
            default -> 400;
        };
        return fence ? (low ? h / 2 : h * 3 / 4) : h;
    }

    /** Nothing on that edge yet: no wall, door, window, frame or fence. */
    private static boolean edgeFree(IsoGridSquare sq, boolean north) {
        if (sq.getWall(north) != null || sq.getDoorOrWindow(north) != null) return false;
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o instanceof IsoWindowFrame f && f.getNorth() == north) return false;
            PropertyContainer props = o == null || o.getSprite() == null ? null : o.getSprite().getProperties();
            if (props != null && (props.has(north ? IsoFlagType.HoppableN : IsoFlagType.HoppableW)
                    || props.has(north ? IsoFlagType.TallHoppableN : IsoFlagType.TallHoppableW))) return false;
        }
        return true;
    }

    /** The north-edge sprite of a west-edge wall or fence: the nearest one in its tileset with only the north flag. */
    static String twin(String west) {
        int cut = west.lastIndexOf('_');
        if (cut < 0) return null;
        String set = west.substring(0, cut);
        int index;
        try { index = Integer.parseInt(west.substring(cut + 1)); } catch (NumberFormatException e) { return null; }
        IsoSprite w = IsoSpriteManager.instance.namedMap.get(west);
        if (w == null) return null;
        boolean fence = w.getProperties().has(IsoFlagType.HoppableW) || w.getProperties().has(IsoFlagType.TallHoppableW);
        for (int d : new int[] {1, -1, 2, -2, 3, -3, 4, -4, 8, -8, 16, -16}) {
            IsoSprite s = IsoSpriteManager.instance.namedMap.get(set + "_" + (index + d));
            if (s == null || s.getProperties() == null) continue;
            PropertyContainer q = s.getProperties();
            boolean n = fence ? q.has(IsoFlagType.HoppableN) || q.has(IsoFlagType.TallHoppableN) : q.has(IsoFlagType.WallN) || q.has(IsoFlagType.WallNTrans);
            boolean wFlag = fence ? q.has(IsoFlagType.HoppableW) || q.has(IsoFlagType.TallHoppableW) : q.has(IsoFlagType.WallW) || q.has(IsoFlagType.WallWTrans);
            if (n && !wFlag) return s.getName();
        }
        return null;
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("placed", placed);
        m.put("refused", refused);
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}
