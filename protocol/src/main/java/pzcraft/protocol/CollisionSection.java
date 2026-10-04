package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * One 8x8 tile column of PZ collision at one level, PZ -> Minecraft.
 * Tile index = ty * 8 + tx (local tile coordinates inside the section).
 */
public final class CollisionSection {
    public static final int SIZE = 8;
    public static final int TILES = SIZE * SIZE;
    /** Height samples per axis inside a ramp tile (stairs, sloped surfaces). */
    public static final int RAMP_N = 4;
    /** Units of {@link #heights}: a storey is this many steps tall. */
    public static final int HEIGHT_STEPS = 128;

    public static final int FLOOR = 1;       // flat solid floor at this level
    public static final int SOLID = 1 << 1;  // the whole tile is blocked (up to heights[i], or the full storey)
    public static final int EDGE_N = 1 << 2; // movement across the tile's north edge is blocked
    public static final int EDGE_W = 1 << 3; // ... west edge
    public static final int RAMP = 1 << 4;   // floor is described by rampHeights instead of being flat
    public static final int EDGE_N_LOW = 1 << 5; // the north edge is only a low fence: Steve can jump it
    public static final int EDGE_W_LOW = 1 << 6; // ... west edge

    public final int cx, cy, level;
    public final byte[] flags = new byte[TILES];
    /** For tiles with RAMP: RAMP_N*RAMP_N floor heights as 1/256ths of a level above this level's floor, row-major (y then x). */
    public final short[][] rampHeights = new short[TILES][];
    /** For SOLID tiles: how tall the obstacle is in 1/HEIGHT_STEPS of a storey; 0 = the full storey (walls, fridges...). */
    public final byte[] heights = new byte[TILES];

    public CollisionSection(int cx, int cy, int level) {
        this.cx = cx; this.cy = cy; this.level = level;
    }

    public static long key64(int level, int cx, int cy) {
        return ((long) (level + 128) << 48) | ((long) (cx & 0xFFFFFF) << 24) | (cy & 0xFFFFFF);
    }

    public long key64() { return key64(level, cx, cy); }

    public boolean isEmpty() {
        for (byte b : flags) if (b != 0) return false;
        return true;
    }

    public int contentHash() {
        int h = Arrays.hashCode(flags) * 31 + Arrays.hashCode(heights);
        for (short[] r : rampHeights) h = h * 31 + Arrays.hashCode(r);
        return h;
    }

    public byte[] encode() {
        int ramps = 0, tall = 0;
        for (short[] r : rampHeights) if (r != null) ramps++;
        for (byte b : heights) if (b != 0) tall++;
        ByteBuffer b = ByteBuffer.allocate(12 + TILES + 1 + ramps * (1 + RAMP_N * RAMP_N * 2) + 1 + tall * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cx).putInt(cy).putInt(level);
        b.put(flags);
        b.put((byte) ramps);
        for (int i = 0; i < TILES; i++) {
            if (rampHeights[i] == null) continue;
            b.put((byte) i);
            for (short s : rampHeights[i]) b.putShort(s);
        }
        b.put((byte) tall);
        for (int i = 0; i < TILES; i++) {
            if (heights[i] == 0) continue;
            b.put((byte) i).put(heights[i]);
        }
        return b.array();
    }

    public static CollisionSection decode(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        CollisionSection s = new CollisionSection(b.getInt(), b.getInt(), b.getInt());
        b.get(s.flags);
        int ramps = b.get() & 0xFF;
        for (int i = 0; i < ramps; i++) {
            int idx = b.get() & 0xFF;
            short[] h = new short[RAMP_N * RAMP_N];
            for (int j = 0; j < h.length; j++) h[j] = b.getShort();
            s.rampHeights[idx] = h;
        }
        if (b.hasRemaining()) {
            int tall = b.get() & 0xFF;
            for (int i = 0; i < tall; i++) {
                int idx = b.get() & 0xFF;
                s.heights[idx] = b.get();
            }
        }
        return s;
    }

    public static byte[] encodeClear(int level, int cx, int cy) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(cx).putInt(cy).putInt(level).array();
    }
}
