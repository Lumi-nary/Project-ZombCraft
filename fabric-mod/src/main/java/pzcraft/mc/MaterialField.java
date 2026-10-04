package pzcraft.mc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.MaterialSection;
import pzcraft.protocol.Materials;

/**
 * What PZ's tiles are made of, as last sent by PZ: floors, wall/door/window/fence edges, solid objects and trees. Written
 * by the link thread, read by the server and client threads; sections are immutable once published. Tile (x, y) is PZ's
 * (Minecraft x, z); a level is a PZ storey.
 */
public final class MaterialField {
    private static final ConcurrentHashMap<Long, MaterialSection> SECTIONS = new ConcurrentHashMap<>();

    private MaterialField() {}

    public static void put(MaterialSection s) {
        if (s.isEmpty()) SECTIONS.remove(s.key64());
        else SECTIONS.put(s.key64(), s);
    }

    public static void remove(int level, int cx, int cy) { SECTIONS.remove(CollisionSection.key64(level, cx, cy)); }

    public static void reset() { SECTIONS.clear(); }

    public static int sectionCount() { return SECTIONS.size(); }

    public static MaterialSection section(int level, int cx, int cy) {
        return SECTIONS.get(CollisionSection.key64(level, cx, cy));
    }

    private static int local(int tile) { return Math.floorMod(tile, MaterialSection.SIZE); }
    private static int chunk(int tile) { return Math.floorDiv(tile, MaterialSection.SIZE); }
    private static int index(int tx, int ty) { return local(ty) * MaterialSection.SIZE + local(tx); }

    /** Floor class of a tile ({@link Materials#FLOOR_NONE} when unknown or absent). */
    public static int floor(int level, int tx, int ty) {
        MaterialSection s = section(level, chunk(tx), chunk(ty));
        return s == null ? Materials.FLOOR_NONE : MaterialSection.unsigned(s.floor[index(tx, ty)]);
    }

    public static int flags(int level, int tx, int ty) {
        MaterialSection s = section(level, chunk(tx), chunk(ty));
        return s == null ? 0 : MaterialSection.unsigned(s.flags[index(tx, ty)]);
    }

    public static int solid(int level, int tx, int ty) {
        MaterialSection s = section(level, chunk(tx), chunk(ty));
        return s == null ? 0 : MaterialSection.unsigned(s.solid[index(tx, ty)]);
    }

    /** What stands on the north edge of tile (tx, ty): {@link Materials#edge(int, int)}. */
    public static int edgeNorth(int level, int tx, int ty) {
        MaterialSection s = section(level, chunk(tx), chunk(ty));
        return s == null ? 0 : MaterialSection.unsigned(s.edgeN[index(tx, ty)]);
    }

    public static int edgeWest(int level, int tx, int ty) {
        MaterialSection s = section(level, chunk(tx), chunk(ty));
        return s == null ? 0 : MaterialSection.unsigned(s.edgeW[index(tx, ty)]);
    }

    /** A tree on a tile: its tile, size (1..8) and species. */
    public record Tree(int x, int y, int size, int kind) {
        public long key() { return ((long) x << 32) | (y & 0xffffffffL); }
    }

    /** Trees on the ground level within {@code radius} blocks (horizontally) of (x, z). */
    public static List<Tree> trees(double x, double z, int radius) {
        List<Tree> out = new ArrayList<>();
        int c0x = chunk((int) Math.floor(x - radius)), c1x = chunk((int) Math.floor(x + radius));
        int c0y = chunk((int) Math.floor(z - radius)), c1y = chunk((int) Math.floor(z + radius));
        double r2 = (double) radius * radius;
        for (int cx = c0x; cx <= c1x; cx++) {
            for (int cy = c0y; cy <= c1y; cy++) {
                MaterialSection s = section(0, cx, cy);
                if (s == null) continue;
                for (int i = 0; i < MaterialSection.TILES; i++) {
                    int size = MaterialSection.unsigned(s.treeSize[i]);
                    if (size == 0) continue;
                    int tx = cx * MaterialSection.SIZE + i % MaterialSection.SIZE, ty = cy * MaterialSection.SIZE + i / MaterialSection.SIZE;
                    double dx = tx + 0.5 - x, dz = ty + 0.5 - z;
                    if (dx * dx + dz * dz <= r2) out.add(new Tree(tx, ty, size, MaterialSection.unsigned(s.treeKind[i])));
                }
            }
        }
        return out;
    }

    /** Counts for the developer automation: how many tiles of each class exist across the ground level. */
    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        int[] floors = new int[Materials.FLOOR_CLASSES];
        int[][] edges = new int[8][Materials.MATERIAL_CLASSES];
        int trees = 0, basements = 0, interiors = 0, solids = 0;
        for (MaterialSection s : SECTIONS.values()) {
            if (s.level != 0) continue;
            for (int i = 0; i < MaterialSection.TILES; i++) {
                floors[MaterialSection.unsigned(s.floor[i]) % Materials.FLOOR_CLASSES]++;
                int flags = MaterialSection.unsigned(s.flags[i]);
                if ((flags & Materials.TILE_BASEMENT) != 0) basements++;
                if ((flags & Materials.TILE_INTERIOR) != 0) interiors++;
                if (MaterialSection.unsigned(s.treeSize[i]) != 0) trees++;
                if (s.solid[i] != 0) solids++;
                for (int e : new int[] {MaterialSection.unsigned(s.edgeN[i]), MaterialSection.unsigned(s.edgeW[i])}) {
                    if (e != 0) edges[Math.min(7, Materials.edgeKind(e))][Materials.edgeMaterial(e) % Materials.MATERIAL_CLASSES]++;
                }
            }
        }
        m.put("sections", SECTIONS.size());
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 1; i < floors.length; i++) if (floors[i] > 0) f.put(Materials.floorName(i), floors[i]);
        m.put("floors", f);
        Map<String, Object> e = new LinkedHashMap<>();
        for (int k = 1; k < 8; k++) {
            Map<String, Object> byMaterial = new LinkedHashMap<>();
            for (int mat = 0; mat < Materials.MATERIAL_CLASSES; mat++) if (edges[k][mat] > 0) byMaterial.put(Materials.materialName(mat), edges[k][mat]);
            if (!byMaterial.isEmpty()) e.put(Materials.kindName(k), byMaterial);
        }
        m.put("edges", e);
        m.put("trees", trees);
        m.put("solids", solids);
        m.put("basementTiles", basements);
        m.put("interiorTiles", interiors);
        return m;
    }
}

