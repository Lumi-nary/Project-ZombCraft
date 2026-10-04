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
import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft -> PZ: moving geometry that PZ draws natively, like the blocks of {@link SceneLink}: Steve's model, mobs,
 * dropped items, falling blocks, moving pistons and particles, re-sent every Minecraft frame, plus the textures they use
 * (skins, the item and particle atlases). Vertices are {@link #VERTEX_BYTES}: position, normal, uv, colour with alpha,
 * emission (the block layout plus alpha), relative to a per-frame block origin.
 *
 * <pre>
 *   0 magic, 4 version
 *   64 + 64*slot       texture slots: 0 seq, 8 revision, 16 width, 20 height, 24 data offset, 28 kind
 *   TEXTURE_DATA       texture pixels (RGBA8), bump-allocated by the writer
 *   FRAME              0 seq, 8 revision, 16 origin xyz, 28 batch count, 32 vertex count
 *   FRAME + 36         captured camera/player pose, hand FOV and the aim amount (+112)
 *   FRAME + 192        batches: texture slot, first vertex, vertex count, flags (16 bytes each)
 *   FRAME_VERTICES     vertices
 * </pre>
 */
public final class EntityLink implements AutoCloseable {
    public static final int TEXTURE_SLOTS = 32;
    /** Texture kind: real pixels in this link, or "use the block atlas PZ already has from the scene link". */
    public static final int KIND_PIXELS = 1, KIND_BLOCK_ATLAS = 2;
    public static final int MAX_BATCHES = 256;
    public static final int MAX_VERTICES = 196608;
    /** Floats per vertex: position 3, normal 3, uv 2, rgba 4, emission 1. */
    public static final int VERTEX_FLOATS = 13, VERTEX_BYTES = VERTEX_FLOATS * Float.BYTES;
    /** Batch flag: cast a shadow only (Steve in first person: the camera is inside his head). */
    public static final int FLAG_SHADOW_ONLY = 1;
    /** Batch flag: never cast a sun shadow (particles). */
    public static final int FLAG_NO_SHADOW = 2;
    /** Batch flag: partly transparent (vertex alpha below 1): drawn with dithered coverage that PZ's TAA resolves. */
    public static final int FLAG_TRANSLUCENT = 4;
    /** Camera-space geometry, drawn once with its own depth after the world and before the HUD. */
    public static final int FLAG_HAND = 8;
    public static final int FLAG_DECAL = 16;
    public static final int FLAG_FLAME = 32;
    /** Steve moves with the camera; do not reproject him as stationary terrain in the host's temporal passes. */
    public static final int FLAG_PLAYER = 64;
    /** Light effects blend additively in the camera-space hand pass. */
    public static final int FLAG_ADDITIVE = 128;

    private static final int MAGIC = 0x4E455A50, VERSION = 5;
    private static final int TEXTURES = 64, TEXTURE_SLOT_BYTES = 64;
    private static final int TEXTURE_DATA = 4096;
    // The private catalogue now produces a 4096x4096 item atlas (64 MiB). Leave room
    // for it alongside particles, skins, equipment and private weapon textures.
    private static final int TEXTURE_DATA_BYTES = 128 * 1024 * 1024;
    private static final int FRAME = TEXTURE_DATA + TEXTURE_DATA_BYTES;
    private static final int BATCHES = FRAME + 192, BATCH_BYTES = 16;
    private static final int FRAME_VERTICES = BATCHES + MAX_BATCHES * BATCH_BYTES;
    private static final int FILE_BYTES = FRAME_VERTICES + MAX_VERTICES * VERTEX_BYTES;
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final FileChannel channel;
    private final MappedByteBuffer buffer;
    private final boolean writer;
    private int textureCursor;

    public record Texture(int slot, long revision, int kind, int width, int height, ByteBuffer pixels) {}
    public record Batch(int texture, int first, int count, int flags) {}
    public record Frame(long revision, int x, int y, int z, List<Batch> batches, int vertexCount, ByteBuffer vertices,
                        PlayerState pose, float handFov) {}

    public static Path defaultPath() {
        return Path.of(System.getProperty("java.io.tmpdir"), "PzCraft_entities_v" + VERSION + ".shm");
    }

    public static EntityLink open(boolean writer) throws IOException {
        return open(writer, defaultPath());
    }

    public static EntityLink open(boolean writer, Path path) throws IOException {
        var ch = new RandomAccessFile(path.toFile(), "rw").getChannel();
        try (var lock = ch.lock()) {
            var b = ch.map(FileChannel.MapMode.READ_WRITE, 0, FILE_BYTES);
            b.order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != MAGIC || b.getInt(4) != VERSION || writer) {
                // A (re)started Minecraft owns new texture slots: nothing from a previous process may be reused.
                for (int i = 0; i < TEXTURE_SLOTS; i++) INT.setRelease(b, TEXTURES + i * TEXTURE_SLOT_BYTES, 0);
                INT.setRelease(b, FRAME, 0);
                b.putInt(4, VERSION);
                INT.setRelease(b, 0, MAGIC);
            }
            return new EntityLink(ch, b, writer);
        } catch (Throwable t) {
            ch.close();
            throw t;
        }
    }

    private EntityLink(FileChannel ch, MappedByteBuffer b, boolean writer) {
        this.channel = ch;
        this.buffer = b;
        this.writer = writer;
    }

    // ---- writer (Minecraft) ----

    /** Room left for texture pixels; when a texture no longer fits the writer starts over with {@link #resetTextures}. */
    public int textureBytesFree() { return TEXTURE_DATA_BYTES - textureCursor; }

    /** Forget every texture (all slots invalid); the reader drops them on its next read. */
    public void resetTextures() {
        requireWriter();
        for (int i = 0; i < TEXTURE_SLOTS; i++) INT.setRelease(buffer, TEXTURES + i * TEXTURE_SLOT_BYTES, 0);
        textureCursor = 0;
    }

    /** Publish slot {@code slot}: the block atlas the reader already has, or {@code width*height*4} RGBA8 pixels. */
    public long writeTexture(int slot, int kind, int width, int height, ByteBuffer pixels) {
        requireWriter();
        if (slot < 0 || slot >= TEXTURE_SLOTS) throw new IllegalArgumentException("texture slot " + slot);
        int bytes = kind == KIND_PIXELS ? width * height * 4 : 0;
        if (kind == KIND_PIXELS && (pixels == null || pixels.remaining() != bytes)) throw new IllegalArgumentException("texture byte count");
        if (bytes > textureBytesFree()) throw new IllegalStateException("entity texture space exhausted");
        int offset = TEXTURE_DATA + textureCursor;
        textureCursor += (bytes + 255) & ~255;
        int base = TEXTURES + slot * TEXTURE_SLOT_BYTES;
        long revision = System.nanoTime();
        int seq = begin(base);
        buffer.putLong(base + 8, revision);
        buffer.putInt(base + 16, width);
        buffer.putInt(base + 20, height);
        buffer.putInt(base + 24, offset);
        buffer.putInt(base + 28, kind);
        if (bytes > 0) buffer.put(offset, pixels, pixels.position(), bytes);
        finish(base, seq);
        return revision;
    }

    /** Publish this frame's geometry: batches index into {@code vertices} (whole triangles). */
    public long writeFrame(int x, int y, int z, List<Batch> batches, ByteBuffer vertices) {
        return writeFrame(x,y,z,batches,vertices,new PlayerState(),70);
    }
    public long writeFrame(int x, int y, int z, List<Batch> batches, ByteBuffer vertices, PlayerState pose, float handFov) {
        requireWriter();
        int bytes = vertices.remaining();
        if (bytes % (3 * VERTEX_BYTES) != 0 || bytes > MAX_VERTICES * VERTEX_BYTES)
            throw new IllegalArgumentException("vertex byte count");
        if (batches.size() > MAX_BATCHES) throw new IllegalArgumentException("too many batches");
        long revision = System.nanoTime();
        int seq = begin(FRAME);
        buffer.putLong(FRAME + 8, revision);
        buffer.putInt(FRAME + 16, x).putInt(FRAME + 20, y).putInt(FRAME + 24, z);
        buffer.putInt(FRAME + 28, batches.size());
        buffer.putInt(FRAME + 32, bytes / VERTEX_BYTES);
        buffer.putInt(FRAME+36,pose.cameraValid?1:0).putInt(FRAME+40,pose.cameraMode);
        buffer.putDouble(FRAME+48,pose.cameraX).putDouble(FRAME+56,pose.cameraY).putDouble(FRAME+64,pose.cameraZ);
        buffer.putFloat(FRAME+72,pose.cameraYawDeg).putFloat(FRAME+76,pose.cameraPitchDeg);
        buffer.putDouble(FRAME+80,pose.x).putDouble(FRAME+88,pose.y).putDouble(FRAME+96,pose.z);
        buffer.putFloat(FRAME+104,pose.eyeHeight).putFloat(FRAME+108,handFov).putFloat(FRAME+112,pose.aim);
        for (int i = 0; i < batches.size(); i++) {
            Batch b = batches.get(i);
            int o = BATCHES + i * BATCH_BYTES;
            buffer.putInt(o, b.texture()).putInt(o + 4, b.first()).putInt(o + 8, b.count()).putInt(o + 12, b.flags());
        }
        buffer.put(FRAME_VERTICES, vertices, vertices.position(), bytes);
        finish(FRAME, seq);
        return revision;
    }

    // ---- reader (PZ) ----

    /** Slot {@code slot} if it changed since {@code previousRevision} (a private copy), else null. Revision 0 = slot empty. */
    public Texture readTexture(int slot, long previousRevision) {
        int base = TEXTURES + slot * TEXTURE_SLOT_BYTES;
        for (int attempt = 0; attempt < 2; attempt++) {
            int seq = (int) INT.getAcquire(buffer, base);
            if ((seq & 1) != 0) continue;
            if (seq == 0) return previousRevision == 0 ? null : new Texture(slot, 0, 0, 0, 0, null);
            long revision = buffer.getLong(base + 8);
            if (revision == previousRevision) return null;
            int w = buffer.getInt(base + 16), h = buffer.getInt(base + 20), offset = buffer.getInt(base + 24), kind = buffer.getInt(base + 28);
            ByteBuffer pixels = null;
            if (kind == KIND_PIXELS) {
                if (w <= 0 || h <= 0 || (long) w * h * 4 > TEXTURE_DATA_BYTES || offset < TEXTURE_DATA
                        || offset + w * h * 4 > TEXTURE_DATA + TEXTURE_DATA_BYTES) return null;
                pixels = copy(offset, w * h * 4);
            }
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(buffer, base) == seq) return new Texture(slot, revision, kind, w, h, pixels);
        }
        return null;
    }

    /** The latest frame if it changed since {@code previousRevision} (a private copy), else null. */
    public Frame readFrame(long previousRevision) {
        for (int attempt = 0; attempt < 2; attempt++) {
            int seq = (int) INT.getAcquire(buffer, FRAME);
            if (seq == 0 || (seq & 1) != 0) return null;
            long revision = buffer.getLong(FRAME + 8);
            if (revision == previousRevision) return null;
            int x = buffer.getInt(FRAME + 16), y = buffer.getInt(FRAME + 20), z = buffer.getInt(FRAME + 24);
            int batchCount = buffer.getInt(FRAME + 28), vertexCount = buffer.getInt(FRAME + 32);
            PlayerState pose=new PlayerState();
            pose.cameraValid=buffer.getInt(FRAME+36)!=0;pose.cameraMode=buffer.getInt(FRAME+40);
            pose.cameraX=buffer.getDouble(FRAME+48);pose.cameraY=buffer.getDouble(FRAME+56);pose.cameraZ=buffer.getDouble(FRAME+64);
            pose.cameraYawDeg=buffer.getFloat(FRAME+72);pose.cameraPitchDeg=buffer.getFloat(FRAME+76);
            pose.x=buffer.getDouble(FRAME+80);pose.y=buffer.getDouble(FRAME+88);pose.z=buffer.getDouble(FRAME+96);
            pose.eyeHeight=buffer.getFloat(FRAME+104);float handFov=buffer.getFloat(FRAME+108);pose.aim=buffer.getFloat(FRAME+112);
            if (batchCount < 0 || batchCount > MAX_BATCHES || vertexCount < 0 || vertexCount > MAX_VERTICES) return null;
            List<Batch> batches = new ArrayList<>(batchCount);
            for (int i = 0; i < batchCount; i++) {
                int o = BATCHES + i * BATCH_BYTES;
                batches.add(new Batch(buffer.getInt(o), buffer.getInt(o + 4), buffer.getInt(o + 8), buffer.getInt(o + 12)));
            }
            ByteBuffer vertices = copy(FRAME_VERTICES, vertexCount * VERTEX_BYTES);
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(buffer, FRAME) == seq) return new Frame(revision, x, y, z, batches, vertexCount, vertices,pose,handFov);
        }
        return null;
    }

    private ByteBuffer copy(int offset, int size) {
        var out = ByteBuffer.allocateDirect(Math.max(1, size)).order(ByteOrder.LITTLE_ENDIAN);
        out.put(buffer.slice(offset, size));
        return out.flip();
    }

    private int begin(int at) {
        int seq = (int) INT.getAcquire(buffer, at);
        if ((seq & 1) != 0) seq++; // a writer that died mid-write: start from a stable value
        INT.setRelease(buffer, at, seq + 1);
        VarHandle.storeStoreFence();
        return seq;
    }

    private void finish(int at, int seq) { INT.setRelease(buffer, at, seq + 2); }

    private void requireWriter() { if (!writer) throw new IllegalStateException("entity reader cannot publish"); }

    @Override
    public void close() throws IOException { channel.close(); }
}
