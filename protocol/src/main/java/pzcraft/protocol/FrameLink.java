package pzcraft.protocol;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minecraft -> PZ video: the latest rendered Minecraft frame, in a second memory-mapped file next to the control link.
 * A frame has two premultiplied-alpha RGBA layers on transparent backgrounds:
 * <ul>
 *   <li>{@link #WORLD}: Steve's blocks, rendered through the camera whose yaw/pitch/fov/aspect are stored with the
 *       frame, so PZ can re-project it to the camera it is drawing <em>now</em> (rotation time-warp);</li>
 *   <li>{@link #HUD}: the hand, crosshair, hotbar and hearts, which are fixed to the screen and never warped.</li>
 * </ul>
 * Four slots, so the reader always sees a complete frame while the writer has up to three more in flight (read-backs from
 * the GPU land two or three frames after they were issued, because PZ and Minecraft share the GPU).
 *
 * <pre>
 *   0 magic, 4 version, 16 frameId (long, written last)
 *   64 + 64*slot   slot metadata: 0 width, 4 height, 8 flags, 12 yawDeg, 16 pitchDeg, 20 fovDeg (vertical), 24 aspect,
 *                  32 renderNanos (long, System.nanoTime() when the frame started rendering)
 *   4096 + (slot*2 + layer) * MAX_BYTES    layer pixels (width*height*4, RGBA8, bottom row first)
 * </pre>
 */
public final class FrameLink implements AutoCloseable {
    public static final int MAGIC = 0x4D524650; // "PFRM"
    public static final int VERSION = 2;
    public static final int MAX_WIDTH = 1280, MAX_HEIGHT = 1080;
    public static final int MAX_BYTES = MAX_WIDTH * MAX_HEIGHT * 4;
    public static final int WORLD = 0, HUD = 1;
    /** Frame flag: the world layer holds a rendered level (otherwise ignore it). */
    public static final int FLAG_WORLD = 1;
    /** Blocks are rendered by PZ; the world bitmap contains only particles, dropped items and mining effects. */
    public static final int FLAG_NATIVE_EFFECTS = 2;
    private static final int HEADER = 4096;
    private static final int META = 64, META_SIZE = 64;
    public static final int SLOTS = 4;
    private static final int FILE_SIZE = HEADER + SLOTS * 2 * MAX_BYTES;

    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private final FileChannel channel;
    private final MappedByteBuffer buf;

    /** What the world layer of a frame was rendered with. */
    public static final class Meta {
        public int width, height, flags;
        public float yawDeg, pitchDeg, fovDeg, aspect;
        public long renderNanos;
        public boolean hasWorld() { return (flags & FLAG_WORLD) != 0; }
        public boolean nativeEffects() { return (flags & FLAG_NATIVE_EFFECTS) != 0; }
    }

    public static Path defaultPath() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "PzCraft_frame_v" + VERSION + ".shm");
    }

    public static FrameLink open() throws IOException {
        return open(defaultPath());
    }

    public static FrameLink open(Path path) throws IOException {
        FileChannel ch = new RandomAccessFile(path.toFile(), "rw").getChannel();
        MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
        b.order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != MAGIC || b.getInt(4) != VERSION) {
            b.putInt(4, VERSION);
            b.putInt(0, MAGIC);
        }
        return new FrameLink(ch, b);
    }

    private FrameLink(FileChannel ch, MappedByteBuffer b) {
        this.channel = ch;
        this.buf = b;
    }

    private static int slotOf(long id) { return (int) (id % SLOTS); }

    private ByteBuffer layerSlice(int slot, int layer, int bytes) {
        int off = HEADER + (slot * 2 + layer) * MAX_BYTES;
        ByteBuffer slice = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        slice.limit(off + bytes).position(off);
        return slice.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---- writer (Minecraft) ----

    /** Reserve the next frame number; its layers are written with {@link #beginLayer} and it goes live with {@link #publish}. */
    public long reserve() { return ++writerSeq; }

    private long writerSeq;

    /** A buffer to fill with exactly width*height*4 bytes for one layer of frame {@code seq}. */
    public ByteBuffer beginLayer(long seq, int layer, int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("frame size out of range: " + width + "x" + height);
        }
        return layerSlice(slotOf(seq), layer, width * height * 4);
    }

    /**
     * Make frame {@code seq} visible once both layers have been filled. Frames must be published in the order they were
     * reserved, and at most {@code SLOTS - 1} may be unpublished at once.
     */
    public void publish(long seq, int width, int height, int flags, float yawDeg, float pitchDeg, float fovDeg, float aspect, long renderNanos) {
        long next = seq;
        int m = META + META_SIZE * slotOf(next);
        buf.putInt(m, width);
        buf.putInt(m + 4, height);
        buf.putInt(m + 8, flags);
        buf.putFloat(m + 12, yawDeg);
        buf.putFloat(m + 16, pitchDeg);
        buf.putFloat(m + 20, fovDeg);
        buf.putFloat(m + 24, aspect);
        LONG.set(buf, m + 32, renderNanos);
        LONG.setRelease(buf, 16, next);
    }

    // ---- reader (PZ) ----

    public long frameId() { return (long) LONG.getAcquire(buf, 16); }

    /** Metadata of the most recently published frame, into {@code out}. */
    public Meta readMeta(Meta out) {
        int m = META + META_SIZE * slotOf(frameId());
        out.width = buf.getInt(m);
        out.height = buf.getInt(m + 4);
        out.flags = buf.getInt(m + 8);
        out.yawDeg = buf.getFloat(m + 12);
        out.pitchDeg = buf.getFloat(m + 16);
        out.fovDeg = buf.getFloat(m + 20);
        out.aspect = buf.getFloat(m + 24);
        out.renderNanos = (long) LONG.get(buf, m + 32);
        return out;
    }

    /** One layer of the most recently published frame (a view into the mapping, valid until the writer laps it). */
    public ByteBuffer frontLayer(int layer, int width, int height) {
        return layerSlice(slotOf(frameId()), layer, width * height * 4);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
