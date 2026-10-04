package pzcraft.protocol;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Both processes map the same file ({@code %TEMP%/PzCraft_v3.shm}). Header with pids and heartbeats, latest-value
 * seqlock slots for per-frame state, and two SPSC rings for events.
 *
 * <pre>
 *   0   magic, 4 version, 8 pzPid, 16 mcPid, 24 pzHeartbeatMs, 32 mcHeartbeatMs
 *   64  PlayerState slot (MC -> PZ)
 *   4096 ring PZ -> MC   (256 KiB)
 *   +    ring MC -> PZ   (256 KiB)
 * </pre>
 */
public final class SharedLink implements AutoCloseable {
    public static final int MAGIC = 0x52435A50; // "PZCR"
    public static final int VERSION = 3; // 0.12 requires complete-column collision coverage before movement
    public static final long HEARTBEAT_TIMEOUT_MS = 3000;

    public enum Role { PZ, MC }

    /** Ring message types. */
    public static final int MSG_PING = 1;
    public static final int MSG_PONG = 2;
    /** PZ -> MC: a {@link CollisionSection}. */
    public static final int MSG_COLLISION = 10;
    /** PZ -> MC: drop a section (level, cx, cy as encoded by {@link CollisionSection#encodeClear}). */
    public static final int MSG_COLLISION_CLEAR = 11;
    /** PZ -> MC: place Steve. Payload: double x, y, z (MC coordinates), float yawDeg, pitchDeg. */
    public static final int MSG_TELEPORT = 12;
    /** PZ -> MC: cx, cy, ready (three ints). Ready follows all collision messages for the loaded column. */
    public static final int MSG_TERRAIN_COLUMN = 13;
    /** MC -> PZ: the Minecraft world is loaded and Steve is spawned. */
    public static final int MSG_READY = 20;
    /** MC -> PZ: a fresh Steve exists (join or respawn) and is waiting to be placed with MSG_TELEPORT. */
    public static final int MSG_NEED_PLACEMENT = 21;

    private static final int OFF_MAGIC = 0, OFF_VERSION = 4, OFF_PZ_PID = 8, OFF_MC_PID = 16, OFF_PZ_BEAT = 24, OFF_MC_BEAT = 32;
    private static final int OFF_PLAYER_SLOT = 64;
    private static final int OFF_NATIVE_WORLD = 40;
    private static final int OFF_INPUT_SLOT = 256;
    private static final int SLOT_PAYLOAD = 8;
    private static final int RING_CAP = 256 * 1024;
    private static final int OFF_RING_PZ_TO_MC = 4096;
    private static final int OFF_RING_MC_TO_PZ = OFF_RING_PZ_TO_MC + Ring.sizeFor(RING_CAP);
    private static final int FILE_SIZE = OFF_RING_MC_TO_PZ + Ring.sizeFor(RING_CAP);

    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final Role role;
    private final FileChannel channel;
    private final MappedByteBuffer buf;
    private final Ring tx;
    private final Ring rx;

    public static Path defaultPath() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "PzCraft_v" + VERSION + ".shm");
    }

    public static SharedLink open(Role role) throws IOException {
        return open(role, defaultPath());
    }

    public static SharedLink open(Role role, Path path) throws IOException {
        FileChannel ch = new RandomAccessFile(path.toFile(), "rw").getChannel();
        try (FileLock ignored = ch.lock()) { // serialize first-time initialisation
            if (ch.size() < FILE_SIZE) ch.truncate(0).position(0);
            MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_WRITE, 0, FILE_SIZE);
            b.order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(OFF_MAGIC) != MAGIC || b.getInt(OFF_VERSION) != VERSION) {
                b.put(0, new byte[FILE_SIZE]); // fresh or stale layout
                b.putInt(OFF_VERSION, VERSION);
                INT.setRelease(b, OFF_MAGIC, MAGIC);
            }
            SharedLink link = new SharedLink(role, ch, b);
            link.claim();
            return link;
        }
    }

    private SharedLink(Role role, FileChannel ch, MappedByteBuffer b) {
        this.role = role;
        this.channel = ch;
        this.buf = b;
        this.tx = new Ring(b, role == Role.PZ ? OFF_RING_PZ_TO_MC : OFF_RING_MC_TO_PZ, RING_CAP);
        this.rx = new Ring(b, role == Role.PZ ? OFF_RING_MC_TO_PZ : OFF_RING_PZ_TO_MC, RING_CAP);
    }

    private void claim() {
        // A restarted process takes over its half; the ring it consumes is drained so it never replays stale events.
        INT.setRelease(buf, role == Role.PZ ? OFF_INPUT_SLOT : OFF_PLAYER_SLOT, 0);
        if (role == Role.PZ) LONG.setRelease(buf, OFF_NATIVE_WORLD, 0L);
        LONG.setRelease(buf, role == Role.PZ ? OFF_PZ_PID : OFF_MC_PID, ProcessHandle.current().pid());
        heartbeat();
        while (rx.poll() != null) { /* drain */ }
    }

    public Role role() { return role; }
    public Ring tx() { return tx; }
    public Ring rx() { return rx; }

    public void heartbeat() {
        LONG.setRelease(buf, role == Role.PZ ? OFF_PZ_BEAT : OFF_MC_BEAT, System.currentTimeMillis());
    }

    private long peerPid() { return (long) LONG.getAcquire(buf, role == Role.PZ ? OFF_MC_PID : OFF_PZ_PID); }
    private long peerBeat() { return (long) LONG.getAcquire(buf, role == Role.PZ ? OFF_MC_BEAT : OFF_PZ_BEAT); }

    /** Peer has beaten recently and its process still exists. */
    public boolean peerAlive() {
        return peerProcessAlive() && System.currentTimeMillis() - peerBeat() < HEARTBEAT_TIMEOUT_MS;
    }

    /** The peer's process still exists, whatever its heartbeat says: tells a stalled peer from one that has quit. */
    public boolean peerProcessAlive() {
        long pid = peerPid();
        if (pid == 0) return false;
        Optional<ProcessHandle> ph = ProcessHandle.of(pid);
        return ph.isPresent() && ph.get().isAlive();
    }

    public long peerPidOrZero() { return peerPid(); }

    // ---- PlayerState seqlock slot (written by MC, read by PZ) ----

    public void writePlayerState(PlayerState s) {
        int base = OFF_PLAYER_SLOT;
        int seq = (int) INT.getAcquire(buf, base);
        INT.setRelease(buf, base, seq + 1); // odd: write in progress
        VarHandle.storeStoreFence();
        int p = base + SLOT_PAYLOAD;
        buf.putLong(p, s.tick);
        buf.putDouble(p + 8, s.x);
        buf.putDouble(p + 16, s.y);
        buf.putDouble(p + 24, s.z);
        buf.putFloat(p + 32, s.yawDeg);
        buf.putFloat(p + 36, s.pitchDeg);
        buf.putInt(p + 40, s.flags);
        buf.putFloat(p + 44, s.eyeHeight);
        buf.putInt(p + 48, s.cameraMode);
        buf.putInt(p + 52, s.cameraValid ? 1 : 0);
        buf.putDouble(p + 56, s.cameraX);
        buf.putDouble(p + 64, s.cameraY);
        buf.putDouble(p + 72, s.cameraZ);
        buf.putFloat(p + 80, s.cameraYawDeg);
        buf.putFloat(p + 84, s.cameraPitchDeg);
        INT.setRelease(buf, base, seq + 2); // even: stable
    }

    /** Copies the latest state into {@code out}; returns false if nothing has been written yet. */
    public boolean readPlayerState(PlayerState out) {
        int base = OFF_PLAYER_SLOT;
        for (int attempt = 0; attempt < 1000; attempt++) {
            int s1 = (int) INT.getAcquire(buf, base);
            if (s1 == 0) return false;
            if ((s1 & 1) != 0) { Thread.onSpinWait(); continue; }
            int p = base + SLOT_PAYLOAD;
            out.tick = buf.getLong(p);
            out.x = buf.getDouble(p + 8);
            out.y = buf.getDouble(p + 16);
            out.z = buf.getDouble(p + 24);
            out.yawDeg = buf.getFloat(p + 32);
            out.pitchDeg = buf.getFloat(p + 36);
            out.flags = buf.getInt(p + 40);
            out.eyeHeight = buf.getFloat(p + 44);
            out.cameraMode = buf.getInt(p + 48);
            out.cameraValid = buf.getInt(p + 52) == 1;
            out.cameraX = buf.getDouble(p + 56);
            out.cameraY = buf.getDouble(p + 64);
            out.cameraZ = buf.getDouble(p + 72);
            out.cameraYawDeg = buf.getFloat(p + 80);
            out.cameraPitchDeg = buf.getFloat(p + 84);
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(buf, base) == s1) return true;
        }
        return false;
    }

    // ---- InputState seqlock slot (written by PZ, read by MC) ----

    public void writeInputState(InputState s) {
        int base = OFF_INPUT_SLOT;
        int seq = (int) INT.getAcquire(buf, base);
        INT.setRelease(buf, base, seq + 1);
        VarHandle.storeStoreFence();
        int p = base + SLOT_PAYLOAD;
        buf.putLong(p, s.frame);
        buf.putFloat(p + 8, s.yawDeg);
        buf.putFloat(p + 12, s.pitchDeg);
        buf.putInt(p + 16, s.buttons);
        buf.putInt(p + 20, s.hotbar);
        buf.putInt(p + 24, s.wheel);
        buf.putInt(p + 28, s.active ? 1 : 0);
        buf.putFloat(p + 32, s.eyeHeight);
        buf.putFloat(p + 36, s.fovDeg);
        buf.putFloat(p + 40, s.aspect);
        buf.putInt(p + 44, s.hotbarSeq);
        buf.putInt(p + 48, s.attackPresses);
        buf.putInt(p + 52, s.usePresses);
        buf.putInt(p + 56, s.dropPresses);
        buf.putInt(p + 60, s.jumpPresses);
        INT.setRelease(buf, base, seq + 2);
    }

    public boolean readInputState(InputState out) {
        int base = OFF_INPUT_SLOT;
        for (int attempt = 0; attempt < 1000; attempt++) {
            int s1 = (int) INT.getAcquire(buf, base);
            if (s1 == 0) return false;
            if ((s1 & 1) != 0) { Thread.onSpinWait(); continue; }
            int p = base + SLOT_PAYLOAD;
            out.frame = buf.getLong(p);
            out.yawDeg = buf.getFloat(p + 8);
            out.pitchDeg = buf.getFloat(p + 12);
            out.buttons = buf.getInt(p + 16);
            out.hotbar = buf.getInt(p + 20);
            out.wheel = buf.getInt(p + 24);
            out.active = buf.getInt(p + 28) != 0;
            out.eyeHeight = buf.getFloat(p + 32);
            out.fovDeg = buf.getFloat(p + 36);
            out.aspect = buf.getFloat(p + 40);
            out.hotbarSeq = buf.getInt(p + 44);
            out.attackPresses = buf.getInt(p + 48);
            out.usePresses = buf.getInt(p + 52);
            out.dropPresses = buf.getInt(p + 56);
            out.jumpPresses = buf.getInt(p + 60);
            VarHandle.loadLoadFence();
            if ((int) INT.getAcquire(buf, base) == s1) return true;
        }
        return false;
    }

    public boolean inputAvailable() { return (int)INT.getAcquire(buf, OFF_INPUT_SLOT) != 0; }

    /** PZ renews this only after drawing a native scene successfully. A missing render hook expires automatically. */
    public void nativeWorldRendered(boolean active) {
        if (role != Role.PZ) throw new IllegalStateException("only PZ can acknowledge native rendering");
        LONG.setRelease(buf, OFF_NATIVE_WORLD, active ? System.currentTimeMillis() : 0L);
    }
    public boolean nativeWorldActive() {
        long rendered = (long)LONG.getAcquire(buf, OFF_NATIVE_WORLD);
        long age = System.currentTimeMillis() - rendered;
        return rendered != 0 && age >= 0 && age < 500;
    }

    // ---- convenience for the Phase 0 ping/pong ----

    public boolean sendPing() {
        return tx.write(MSG_PING, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(System.nanoTime()).array());
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
