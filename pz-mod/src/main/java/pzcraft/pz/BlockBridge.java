package pzcraft.pz;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Coords;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;

/**
 * Blocks Steve places in Minecraft are obstacles in PZ too. A PZ tile is solid while at least one Minecraft solid block
 * stands in its column within that storey. We do not add objects to PZ's world (they would be written into the save);
 * instead the square's own "solid" flag is re-applied every time PZ recalculates the square, so zombies, pathing and
 * collision all see it, and nothing extra is persisted.
 */
public final class BlockBridge {
    private static final ConcurrentHashMap<Long, Integer> counts = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<int[]> pending = new ConcurrentLinkedQueue<>();
    private static volatile boolean resetPending;

    private BlockBridge() {}

    private static long key(int x, int y, int level) {
        return ((long) (level + 64) << 56) ^ ((long) (x & 0xFFFFFFF) << 28) ^ (y & 0xFFFFFFF);
    }

    private static int levelOf(int mcY) {
        return (int) Math.floor(mcY / Coords.BLOCKS_PER_LEVEL);
    }

    /** Is this PZ square currently covered by a Minecraft block? Cheap enough to call from hot paths. */
    public static boolean has(int x, int y, int level) {
        return !counts.isEmpty() && counts.getOrDefault(key(x, y, level), 0) > 0;
    }

    static int size() { return counts.size(); }

    /** Called by the link thread. */
    static void onBlock(int[] b) { if (pending.size() < 100_000) pending.add(b); }

    static void onReset() { resetPending = true; }

    /** Applied by the ZombieBuddy patch at the end of IsoGridSquare.RecalcProperties. */
    public static void onRecalc(IsoGridSquare sq) {
        if (!counts.isEmpty() && has(sq.getX(), sq.getY(), sq.getZ())) {
            sq.getProperties().set(IsoFlagType.solid);
        }
    }

    /** Game thread: fold queued block changes in and refresh the squares they touch. */
    static void tick() {
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        List<long[]> touched = new ArrayList<>();
        if (resetPending) {
            resetPending = false;
            for (Long k : new ArrayList<>(counts.keySet())) touched.add(new long[] {k});
            counts.clear();
            // squares are refreshed below from the decoded keys; keep the raw keys for that
            for (long[] t : touched) refreshKey(cell, t[0]);
            touched.clear();
        }
        int[] b;
        int n = 0;
        while ((b = pending.poll()) != null && n++ < 2000) {
            int x = b[0], y = b[2], level = levelOf(b[1]);
            long k = key(x, y, level);
            boolean solid = b[3] != 0;
            Integer old = counts.get(k);
            int now = Math.max(0, (old == null ? 0 : old) + (solid ? 1 : -1));
            if (now == 0) counts.remove(k); else counts.put(k, now);
            boolean changed = (old == null || old == 0) != (now == 0);
            if (changed) {
                IsoGridSquare sq = cell.getGridSquare(x, y, level);
                if (sq != null) refresh(sq);
                Log.info("block " + (now > 0 ? "placed" : "removed") + " at tile (" + x + "," + y + ") level " + level
                        + (sq == null ? " (square not loaded)" : " -> square solid=" + sq.isSolid()));
            }
        }
    }

    private static void refreshKey(IsoCell cell, long k) {
        int level = (int) (k >>> 56) - 64;
        int x = (int) ((k >>> 28) & 0xFFFFFFF);
        int y = (int) (k & 0xFFFFFFF);
        // undo the sign folding of 28-bit fields
        if (x >= 0x8000000) x -= 0x10000000;
        if (y >= 0x8000000) y -= 0x10000000;
        IsoGridSquare sq = cell.getGridSquare(x, y, level);
        if (sq != null) refresh(sq);
    }

    private static void refresh(IsoGridSquare sq) {
        sq.RecalcProperties();
        sq.RecalcAllWithNeighbours(true);
        // What PZ itself does when an object is added or removed: without this its native pathfinder and collision map
        // never learn about the block, and zombies plan straight through Minecraft walls.
        zombie.MapCollisionData.instance.squareChanged(sq);
        zombie.pathfind.PolygonalMap2.instance.squareChanged(sq);
        zombie.iso.areas.isoregion.IsoRegions.squareChanged(sq);
        // Characters slide along obstacle polygons that each 8x8 chunk caches, including a 2-tile border taken from its
        // neighbours. RecalcProperties only drops the square's own chunk's cache, so a block within 2 tiles of a chunk
        // edge would stay invisible (or, once mined, an invisible wall) to zombies standing in the neighbouring chunk.
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dy = -2; dy <= 2; dy += 2) {
                zombie.iso.IsoChunk chunk = cell.getChunkForGridSquare(sq.getX() + dx, sq.getY() + dy, sq.getZ());
                if (chunk != null) chunk.collision.clear();
            }
        }
    }
}

