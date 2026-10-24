package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PZ things near Steve that Minecraft can act on (mine them today; sit, sleep and drive later), PZ -> Minecraft, and the
 * request to mine one, Minecraft -> PZ, with PZ's answer. A target is found again by its tile, level, kind and the hash
 * of its sprite's name, never by a runtime id: a PZ client and a PZ server number their objects differently.
 */
public final class WorldTargets {
    private WorldTargets() {}

    // ---- kinds ----
    /** Tall grass and small plants on a tile (PZ's canBeRemoved): one target per tile, mined all together as PZ does. */
    public static final int KIND_GRASS = 1;
    /** A bush (PZ's canBeCut): one target per tile. */
    public static final int KIND_BUSH = 2;
    /** Loose ground cover PZ lets you pick up: stones, twigs, a branch, a log ({@link #coverVariant}). */
    public static final int KIND_COVER = 3;
    /** A boulder or ore deposit ({@link #boulderVariant}). */
    public static final int KIND_BOULDER = 4;
    /** What stands on a tile's north or west edge ({@link #edgeVariant}): a wall (also an empty window frame or a built one)... */
    public static final int KIND_WALL = 5;
    /** ...a closed door (an open one is no target: it hangs away from its edge)... */
    public static final int KIND_DOOR = 6;
    /** ...a window: the first hit smashes the glass; a smashed one goes with the wall it sits in ({@link #edgeFlag})... */
    public static final int KIND_WINDOW = 7;
    /** ...a fence ({@link #edgeFlag}: low, one Steve can jump)... */
    public static final int KIND_FENCE = 8;
    /** ...or the barricade on a door or window, which comes off first ({@link #barricadeVariant}). */
    public static final int KIND_BARRICADE = 9;

    /** Furniture PZ can pick up (IsMoveAble): mining it gives PZ's own item for it, which can be placed again. */
    public static final int KIND_FURNITURE = 10;
    /** A solid object PZ cannot pick up: mining breaks it into blocks of its material ({@link #edgeMaterial}). */
    public static final int KIND_SOLID = 11;

    public static boolean isEdge(int kind) { return kind >= KIND_WALL && kind <= KIND_BARRICADE; }

    /**
     * A piece of furniture or a solid object: what it is made of ({@link #edgeMaterial}), whether PZ seats people on it
     * (its seating data) or lets them sleep in it (a bed; a couch is both), and which way the sitter or sleeper faces.
     */
    public static int pieceVariant(int material, boolean seat, boolean bed, int face) {
        return (material & 15) | (seat ? 16 : 0) | (bed ? 32 : 0) | ((face & 3) << 6);
    }

    public static boolean isSeat(int variant) { return (variant & 16) != 0; }
    public static boolean isBed(int variant) { return (variant & 32) != 0; }
    public static int pieceFace(int variant) { return (variant >> 6) & 3; }

    /** PZ's facing names, "N" "E" "S" "W", to {@link #FACE_N}...; anything else is south (PZ's own default). */
    public static int face(String name) {
        if (name == null) return FACE_S;
        return switch (name) { case "N" -> FACE_N; case "E" -> FACE_E; case "W" -> FACE_W; default -> FACE_S; };
    }

    /** An edge thing: what it is made of ({@link Materials}), which edge, and the kind's flag (a low fence, a smashed window). */
    public static int edgeVariant(int material, boolean north, boolean flag) {
        return (material & 15) | (north ? 16 : 0) | (flag ? 32 : 0);
    }

    /** A barricade: metal or wooden planks, which edge, and how many planks (0-7). */
    public static int barricadeVariant(boolean metal, boolean north, int planks) {
        return (metal ? Materials.MAT_METAL : Materials.MAT_WOOD) | (north ? 16 : 0) | (Math.max(0, Math.min(7, planks)) << 5);
    }

    public static int edgeMaterial(int variant) { return variant & 15; }
    public static boolean edgeNorth(int variant) { return (variant & 16) != 0; }
    public static boolean edgeFlag(int variant) { return (variant & 32) != 0; }
    public static int barricadePlanks(int variant) { return (variant >> 5) & 7; }

    // ---- ground cover, by PZ's CustomName (PZ's own GroundCoverItems table and ISPickUpGroundCoverItem) ----
    public static final int COVER_STONE = 1, COVER_STONES = 2, COVER_LARGE_STONE = 3, COVER_STONE_TWIGS = 4,
            COVER_LARGE_STONE_TWIGS = 5, COVER_FLAT_STONE = 6, COVER_LIMESTONE = 7, COVER_TWIGS = 8, COVER_BRANCH = 9,
            COVER_LOG = 10;

    private static final Map<String, Integer> COVER = Map.ofEntries(
            Map.entry("Stone", COVER_STONE), Map.entry("Stone2", COVER_STONE),
            Map.entry("Stones", COVER_STONES), Map.entry("4Stones", COVER_STONES),
            Map.entry("LargeStone", COVER_LARGE_STONE), Map.entry("StoneTwigs", COVER_STONE_TWIGS),
            Map.entry("LargeStoneTwigs", COVER_LARGE_STONE_TWIGS), Map.entry("FlatStone", COVER_FLAT_STONE),
            Map.entry("flatRock", COVER_FLAT_STONE), Map.entry("Limestone", COVER_LIMESTONE),
            Map.entry("Twigs", COVER_TWIGS), Map.entry("TreeBranch2", COVER_BRANCH), Map.entry("Log", COVER_LOG));

    /** The ground cover a PZ CustomName stands for, or 0 when it is none of them. */
    public static int coverVariant(String customName) {
        if (customName == null) return 0;
        Integer v = COVER.get(customName);
        if (v != null) return v;
        return customName.contains("Stones") ? COVER_STONES : 0; // PZ itself takes any "...Stones" for stones
    }

    // ---- boulders and ores (PZ's ISPickAxeGroundCoverItem) ----
    public static final int BOULDER_PLAIN = 1, BOULDER_IRON = 2, BOULDER_COPPER = 3, BOULDER_FLINT = 4, BOULDER_LIMESTONE = 5;
    /** Size of an ore deposit, in the variant's second digit: small 0, medium 1, large 2. */
    public static final int SMALL = 0, MEDIUM = 1, LARGE = 2;

    /** Variant = kind + 16 * size. PZ's names: ironOre(Small|Medium|Large), copperOre..., FlintBoulder, LimestoneBoulder. */
    public static int boulderVariant(String customName) {
        if (customName == null) return BOULDER_PLAIN;
        int size = customName.endsWith("Large") ? LARGE : customName.endsWith("Medium") ? MEDIUM : SMALL;
        if (customName.contains("ironOre")) return BOULDER_IRON + 16 * size;
        if (customName.contains("copperOre")) return BOULDER_COPPER + 16 * size;
        if (customName.equals("FlintBoulder")) return BOULDER_FLINT;
        if (customName.equals("LimestoneBoulder")) return BOULDER_LIMESTONE;
        return BOULDER_PLAIN;
    }

    public static int boulderKind(int variant) { return variant & 15; }
    public static int boulderSize(int variant) { return (variant >> 4) & 3; }

    /**
     * One thing Minecraft can act on, on tile (x, y) at PZ level {@code level}. The box is relative to the tile's corner
     * and the level's floor, in blocks: x and z in 1/160 of a tile from -0.25 (an edge's box reaches into the next tile),
     * heights in 1/100 of a block. Use {@link #x0()} etc. for the values in blocks.
     */
    public record Target(int kind, int variant, int x, int y, int level, int key,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static final double XZ = 1 / 160.0, XZ_OFFSET = -0.25, Y = 1 / 100.0;

        /** A box from tile-relative blocks, clamped to what the wire carries (x, z -0.25..1.34; y 0..2.55). */
        public static Target of(int kind, int variant, int x, int y, int level, int key,
                                double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return new Target(kind, variant, x, y, level, key, xz(minX), h(minY), xz(minZ), xz(maxX), h(maxY), xz(maxZ));
        }

        private static int xz(double v) { return (int) Math.round((Math.max(XZ_OFFSET, Math.min(1.34, v)) - XZ_OFFSET) / XZ); }
        private static int h(double v) { return (int) Math.round(Math.max(0, Math.min(2.55, v)) / Y); }

        public double x0() { return minX * XZ + XZ_OFFSET; }
        public double x1() { return maxX * XZ + XZ_OFFSET; }
        public double z0() { return minZ * XZ + XZ_OFFSET; }
        public double z1() { return maxZ * XZ + XZ_OFFSET; }
        public double y0() { return minY * Y; }
        public double y1() { return maxY * Y; }

        /** Whether two snapshots point at the same PZ thing. */
        public boolean same(Target o) {
            return o != null && kind == o.kind && x == o.x && y == o.y && level == o.level && key == o.key;
        }
    }

    public static final int MAX_TARGETS = 2048;
    private static final int TARGET_BYTES = 1 + 1 + 4 + 4 + 1 + 4 + 6;

    public static byte[] encode(List<Target> targets) {
        if (targets.size() > MAX_TARGETS) throw new IllegalArgumentException("Too many targets");
        ByteBuffer b = ByteBuffer.allocate(4 + targets.size() * TARGET_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(targets.size());
        for (Target t : targets) {
            b.put((byte) t.kind).put((byte) t.variant).putInt(t.x).putInt(t.y).put((byte) t.level).putInt(t.key)
                    .put((byte) t.minX).put((byte) t.minY).put((byte) t.minZ).put((byte) t.maxX).put((byte) t.maxY).put((byte) t.maxZ);
        }
        return b.array();
    }

    public static List<Target> decode(byte[] bytes) {
        if (bytes.length < 4) throw new IllegalArgumentException("Truncated targets");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        if (n < 0 || n > MAX_TARGETS || bytes.length != 4 + n * TARGET_BYTES) throw new IllegalArgumentException("Invalid target count");
        List<Target> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Target(b.get() & 0xFF, b.get() & 0xFF, b.getInt(), b.getInt(), b.get(), b.getInt(),
                    b.get() & 0xFF, b.get() & 0xFF, b.get() & 0xFF, b.get() & 0xFF, b.get() & 0xFF, b.get() & 0xFF));
        }
        return List.copyOf(out);
    }

    /** Minecraft -> PZ: Steve finished mining this target; PZ removes it and answers with {@link Mined}. */
    public record Mine(int seq, int kind, int variant, int x, int y, int level, int key) {
        public static Mine of(int seq, Target t) { return new Mine(seq, t.kind, t.variant, t.x, t.y, t.level, t.key); }

        public byte[] encode() {
            return ByteBuffer.allocate(4 + 1 + 1 + 4 + 4 + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(seq).put((byte) kind).put((byte) variant).putInt(x).putInt(y).put((byte) level).putInt(key).array();
        }

        public static Mine decode(byte[] bytes) {
            if (bytes.length != 19) throw new IllegalArgumentException("Bad mine request");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Mine(b.getInt(), b.get() & 0xFF, b.get() & 0xFF, b.getInt(), b.getInt(), b.get(), b.getInt());
        }
    }

    /** Facing of placed furniture: the way its front looks (PZ's "Facing" N, E, S, W). */
    public static final int FACE_N = 0, FACE_E = 1, FACE_S = 2, FACE_W = 3;

    public static String faceName(int face) {
        return switch (face) { case FACE_E -> "E"; case FACE_S -> "S"; case FACE_W -> "W"; default -> "N"; };
    }

    /** Minecraft -> PZ: place the PZ item with this id from Steve's inventory on tile (x, y) at {@code level}, facing {@code face}. */
    public record Place(int seq, int itemId, int x, int y, int level, int face) {
        public byte[] encode() {
            return ByteBuffer.allocate(4 + 4 + 4 + 4 + 1 + 1).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(seq).putInt(itemId).putInt(x).putInt(y).put((byte) level).put((byte) face).array();
        }

        public static Place decode(byte[] bytes) {
            if (bytes.length != 18) throw new IllegalArgumentException("Bad place request");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Place(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.get(), b.get() & 3);
        }
    }

    /**
     * Minecraft -> PZ: place PZ's sprite {@code sprite} (a wall or fence, as exported for the creative tabs: its west-edge
     * one) on the north or west edge of tile (x, y) at {@code level}; on a north edge PZ uses the sprite's north twin.
     */
    public record PlaceTile(int seq, int x, int y, int level, boolean north, String sprite) {
        public byte[] encode() {
            byte[] s = sprite.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ByteBuffer.allocate(4 + 4 + 4 + 1 + 1 + s.length).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(seq).putInt(x).putInt(y).put((byte) level).put((byte) (north ? 1 : 0)).put(s).array();
        }

        public static PlaceTile decode(byte[] bytes) {
            if (bytes.length < 15 || bytes.length > 14 + 256) throw new IllegalArgumentException("Bad tile placing");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int seq = b.getInt(), x = b.getInt(), y = b.getInt(), level = b.get();
            boolean north = b.get() != 0;
            return new PlaceTile(seq, x, y, level, north, new String(bytes, 14, bytes.length - 14, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Which edge of which tile a wall goes on when the floor is clicked at tile-relative (fx, fz): the nearest edge of the
     * clicked tile; a south or east edge is the north or west edge of the next tile. Returns {dx, dz, north ? 1 : 0}.
     */
    public static int[] nearestEdge(double fx, double fz) {
        double n = fz, s = 1 - fz, w = fx, e = 1 - fx, min = Math.min(Math.min(n, s), Math.min(w, e));
        if (min == n) return new int[] {0, 0, 1};
        if (min == s) return new int[] {0, 1, 1};
        if (min == w) return new int[] {0, 0, 0};
        return new int[] {1, 0, 0};
    }

    /** PZ -> Minecraft: whether placing request {@code seq} worked (PZ's own placing rules decide). */
    public record Placed(int seq, boolean placed) {
        public byte[] encode() {
            return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(seq).put((byte) (placed ? 1 : 0)).array();
        }

        public static Placed decode(byte[] bytes) {
            if (bytes.length != 5) throw new IllegalArgumentException("Bad place answer");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Placed(b.getInt(), b.get() != 0);
        }
    }

    /** PZ -> Minecraft: whether the target of request {@code seq} was there and is now gone (only then does anything drop). */
    public record Mined(int seq, boolean removed) {
        public byte[] encode() {
            return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(seq).put((byte) (removed ? 1 : 0)).array();
        }

        public static Mined decode(byte[] bytes) {
            if (bytes.length != 5) throw new IllegalArgumentException("Bad mine answer");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Mined(b.getInt(), b.get() != 0);
        }
    }
}
