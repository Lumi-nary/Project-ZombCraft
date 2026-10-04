package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One 8x8 tile column at one level, PZ -> Minecraft: what each tile floor, edge and object is made of. It uses the same
 * keys as {@link CollisionSection} and is sent whenever its content changes. Tile index = ty * 8 + tx.
 *
 * <p>Wire format: cx, cy, level (ints), then seven arrays in the order of {@link #arrays()}, each as one tag byte
 * (0 all zero, 1 one repeated value follows, 2 sixty-four values follow) so open grass fields cost a few bytes.
 */
public final class MaterialSection {
    public static final int SIZE = CollisionSection.SIZE;
    public static final int TILES = CollisionSection.TILES;

    public final int cx, cy, level;
    /** Floor class per tile ({@link Materials#FLOOR_NONE}...). */
    public final byte[] floor = new byte[TILES];
    /** {@link Materials#TILE_TREE} etc. */
    public final byte[] flags = new byte[TILES];
    /** Material class of the solid object on the tile (furniture, appliances, crates...), 0 = none. */
    public final byte[] solid = new byte[TILES];
    /** What stands on the north / west edge of the tile: {@link Materials#edge(int, int)}, 0 = nothing. */
    public final byte[] edgeN = new byte[TILES], edgeW = new byte[TILES];
    /** Tree size 1..8 (0 = no tree) and species ({@link Materials#TREE_OAK}...). */
    public final byte[] treeSize = new byte[TILES], treeKind = new byte[TILES];

    public MaterialSection(int cx, int cy, int level) {
        this.cx = cx; this.cy = cy; this.level = level;
    }

    public long key64() { return CollisionSection.key64(level, cx, cy); }

    private byte[][] arrays() { return new byte[][] {floor, flags, solid, edgeN, edgeW, treeSize, treeKind}; }

    public boolean isEmpty() {
        for (byte[] a : arrays()) for (byte b : a) if (b != 0) return false;
        return true;
    }

    public int contentHash() {
        int h = 17;
        for (byte[] a : arrays()) h = h * 31 + Arrays.hashCode(a);
        return h;
    }

    public byte[] encode() {
        ByteBuffer b = ByteBuffer.allocate(12 + 7 * (1 + TILES)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cx).putInt(cy).putInt(level);
        for (byte[] a : arrays()) {
            byte first = a[0];
            boolean uniform = true;
            for (byte v : a) if (v != first) { uniform = false; break; }
            if (uniform && first == 0) b.put((byte) 0);
            else if (uniform) b.put((byte) 1).put(first);
            else b.put((byte) 2).put(a);
        }
        return Arrays.copyOf(b.array(), b.position());
    }

    public static MaterialSection decode(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        MaterialSection s = new MaterialSection(b.getInt(), b.getInt(), b.getInt());
        for (byte[] a : s.arrays()) {
            int tag = b.get();
            switch (tag) {
                case 0 -> { }
                case 1 -> Arrays.fill(a, b.get());
                case 2 -> b.get(a);
                default -> throw new IllegalArgumentException("Bad material array tag " + tag);
            }
        }
        return s;
    }

    /** Reads one tile's edge value as an unsigned int. */
    public static int unsigned(byte v) { return v & 0xFF; }
}
