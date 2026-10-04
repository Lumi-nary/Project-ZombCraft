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

/** Minecraft's texture atlas and baked triangles. Camera-independent snapshots, separate from video frames. */
public final class SceneLink implements AutoCloseable {
    public static final int MAX_ATLAS_SIDE = 4096;
    public static final int MAX_VERTICES = 196608;
    /** Position xyz, normal xyz, atlas uv, tint rgb, emissive strength (all floats). */
    public static final int VERTEX_BYTES = 12 * Float.BYTES;
    private static final int MAGIC = 0x53435A50, VERSION = 1;
    private static final int ATLAS = 64, MESH = 128, SELECTION = 192, DATA = 4096;
    private static final int ATLAS_BYTES = MAX_ATLAS_SIDE * MAX_ATLAS_SIDE * 4;
    private static final int MESH_DATA = DATA + ATLAS_BYTES;
    private static final int FILE_BYTES = MESH_DATA + MAX_VERTICES * VERTEX_BYTES;
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private final FileChannel channel;
    private final MappedByteBuffer buffer;
    private final boolean writer;

    public record Atlas(long revision, int width, int height, ByteBuffer pixels) {}
    public record Mesh(long revision, long atlasRevision, int x, int y, int z, int vertexCount, ByteBuffer vertices) {}
    public record Selection(long revision, boolean active, int x, int y, int z,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

    public static SceneLink open(boolean writer) throws IOException {
        return open(writer, Path.of(System.getProperty("java.io.tmpdir"), "PzCraft_scene_v1.shm"));
    }

    public static SceneLink open(boolean writer, Path path) throws IOException {
        var ch = new RandomAccessFile(path.toFile(), "rw").getChannel();
        try (var lock = ch.lock()) {
            var b = ch.map(FileChannel.MapMode.READ_WRITE, 0, FILE_BYTES);
            b.order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != MAGIC || b.getInt(4) != VERSION) {
                b.putInt(4, VERSION);
                b.putInt(ATLAS, 0); b.putInt(MESH, 0); b.putInt(SELECTION, 0);
                INT.setRelease(b, 0, MAGIC);
            }
            if (writer) {
                // Restarted Minecraft cannot expose the previous process's geometry or texture coordinates.
                INT.setRelease(b, ATLAS, 0); INT.setRelease(b, MESH, 0); INT.setRelease(b, SELECTION, 0);
            }
            return new SceneLink(ch, b, writer);
        } catch (Throwable t) {
            ch.close();
            throw t;
        }
    }

    private SceneLink(FileChannel ch, MappedByteBuffer b, boolean writer) {
        channel = ch; buffer = b; this.writer = writer;
    }

    /** Single atlas producer. Returns the identity that must accompany meshes using this atlas. */
    public long writeAtlas(int width, int height, ByteBuffer pixels) {
        requireWriter();
        int size = atlasSize(width, height);
        if (pixels.remaining() != size) throw new IllegalArgumentException("atlas byte count");
        long revision = System.nanoTime();
        int seq = begin(ATLAS);
        buffer.putLong(ATLAS + 8, revision);
        buffer.putInt(ATLAS + 16, width); buffer.putInt(ATLAS + 20, height);
        buffer.put(DATA, pixels, pixels.position(), size);
        finish(ATLAS, seq);
        return revision;
    }

    /** Single mesh producer. An empty mesh explicitly removes all blocks from the current scene. */
    public long writeMesh(long atlasRevision, int x, int y, int z, ByteBuffer vertices) {
        requireWriter();
        int bytes = vertices.remaining();
        if (bytes % (3 * VERTEX_BYTES) != 0 || bytes > MAX_VERTICES * VERTEX_BYTES)
            throw new IllegalArgumentException("triangle byte count");
        long revision = System.nanoTime();
        int seq = begin(MESH);
        buffer.putLong(MESH + 8, revision); buffer.putLong(MESH + 16, atlasRevision);
        buffer.putInt(MESH + 24, x); buffer.putInt(MESH + 28, y); buffer.putInt(MESH + 32, z);
        buffer.putInt(MESH + 36, bytes / VERTEX_BYTES);
        buffer.put(MESH_DATA, vertices, vertices.position(), bytes);
        finish(MESH, seq);
        return revision;
    }

    /** Returns a stable private copy on change, or null if unchanged/unavailable. Never uploads a live mapped view. */
    public Atlas readAtlas(long previousRevision) {
        for (int i = 0; i < 2; i++) {
            int seq = (int) INT.getAcquire(buffer, ATLAS);
            if (seq == 0 || (seq & 1) != 0) return null;
            long revision = buffer.getLong(ATLAS + 8);
            if (revision == previousRevision) return null;
            int w = buffer.getInt(ATLAS + 16), h = buffer.getInt(ATLAS + 20);
            if (w <= 0 || h <= 0 || w > MAX_ATLAS_SIDE || h > MAX_ATLAS_SIDE) return null;
            var pixels = copy(DATA, w * h * 4);
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(buffer, ATLAS) == seq) return new Atlas(revision, w, h, pixels);
        }
        return null;
    }

    public Mesh readMesh(long previousRevision) {
        for (int i = 0; i < 2; i++) {
            int seq = (int) INT.getAcquire(buffer, MESH);
            if (seq == 0 || (seq & 1) != 0) return null;
            long revision = buffer.getLong(MESH + 8);
            if (revision == previousRevision) return null;
            long atlasRevision = buffer.getLong(MESH + 16);
            int x = buffer.getInt(MESH + 24), y = buffer.getInt(MESH + 28), z = buffer.getInt(MESH + 32);
            int count = buffer.getInt(MESH + 36);
            if (count < 0 || count > MAX_VERTICES || count % 3 != 0) return null;
            var vertices = copy(MESH_DATA, count * VERTEX_BYTES);
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(buffer, MESH) == seq)
                return new Mesh(revision, atlasRevision, x, y, z, count, vertices);
        }
        return null;
    }

    public void writeSelection(boolean active, int x, int y, int z, float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ) {
        requireWriter();
        int seq = begin(SELECTION);
        buffer.putLong(SELECTION + 8, System.nanoTime()); buffer.putInt(SELECTION + 16, active ? 1 : 0);
        buffer.putInt(SELECTION + 20, x); buffer.putInt(SELECTION + 24, y); buffer.putInt(SELECTION + 28, z);
        buffer.putFloat(SELECTION + 32, minX); buffer.putFloat(SELECTION + 36, minY); buffer.putFloat(SELECTION + 40, minZ);
        buffer.putFloat(SELECTION + 44, maxX); buffer.putFloat(SELECTION + 48, maxY); buffer.putFloat(SELECTION + 52, maxZ);
        finish(SELECTION, seq);
    }

    public Selection readSelection() {
        int seq = (int)INT.getAcquire(buffer, SELECTION);
        if (seq == 0 || (seq & 1) != 0) return null;
        var result = new Selection(buffer.getLong(SELECTION + 8), buffer.getInt(SELECTION + 16) != 0,
                buffer.getInt(SELECTION + 20), buffer.getInt(SELECTION + 24), buffer.getInt(SELECTION + 28),
                buffer.getFloat(SELECTION + 32), buffer.getFloat(SELECTION + 36), buffer.getFloat(SELECTION + 40),
                buffer.getFloat(SELECTION + 44), buffer.getFloat(SELECTION + 48), buffer.getFloat(SELECTION + 52));
        VarHandle.loadLoadFence();
        return (int)INT.getAcquire(buffer, SELECTION) == seq ? result : null;
    }

    private ByteBuffer copy(int offset, int size) {
        var out = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        out.put(buffer.slice(offset, size));
        return out.flip();
    }

    public boolean available() { return (int)INT.getAcquire(buffer, ATLAS) != 0 && (int)INT.getAcquire(buffer, MESH) != 0; }
    public void invalidate() { requireWriter(); INT.setRelease(buffer, MESH, 0); }

    private int begin(int slot) {
        int seq = (int) INT.getAcquire(buffer, slot);
        INT.setRelease(buffer, slot, seq + 1);
        VarHandle.storeStoreFence();
        return seq;
    }
    private void finish(int slot, int seq) { INT.setRelease(buffer, slot, seq + 2); }
    private void requireWriter() { if (!writer) throw new IllegalStateException("scene reader cannot publish"); }
    private static int atlasSize(int w, int h) {
        if (w <= 0 || h <= 0 || w > MAX_ATLAS_SIDE || h > MAX_ATLAS_SIDE)
            throw new IllegalArgumentException("atlas dimensions");
        return w * h * 4;
    }
    @Override public void close() throws IOException { channel.close(); }
}
