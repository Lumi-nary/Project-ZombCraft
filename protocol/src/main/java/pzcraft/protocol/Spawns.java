package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft's creative spawners for PZ things: a car of a PZ vehicle script (repaired, full tank, its key to the player) and a
 * PZ zombie (the Zomboid spawn egg). PZ makes them with its own code where Steve used the item. Tile x/y and level are PZ's.
 */
public final class Spawns {
    /** Minecraft -> PZ: {@link Vehicle}. */
    public static final int MSG_SPAWN_VEHICLE = 96;
    /** Minecraft -> PZ: {@link Zombie}. */
    public static final int MSG_SPAWN_ZOMBIE = 97;

    private Spawns() {}

    /** Spawn PZ vehicle script {@code script} (e.g. "Base.CarNormal") on tile (x, y, level), its front towards {@code yawDeg} (Minecraft's). */
    public record Vehicle(String script, int x, int y, int level, float yawDeg) {
        public byte[] encode() {
            byte[] name = script.getBytes(StandardCharsets.UTF_8);
            if (name.length < 1 || name.length > 200) throw new IllegalArgumentException("vehicle script name");
            return ByteBuffer.allocate(13 + 2 + name.length).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).put((byte) level)
                    .putFloat(yawDeg).putShort((short) name.length).put(name).array();
        }

        public static Vehicle decode(byte[] bytes) {
            if (bytes.length < 16 || bytes.length > 15 + 200) throw new IllegalArgumentException("vehicle spawn size");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int x = b.getInt(), y = b.getInt(), level = b.get();
            float yaw = b.getFloat();
            int n = b.getShort();
            if (n < 1 || n != b.remaining()) throw new IllegalArgumentException("vehicle script name");
            byte[] name = new byte[n];
            b.get(name);
            return new Vehicle(new String(name, StandardCharsets.UTF_8), x, y, level, yaw);
        }
    }

    /** Spawn one PZ zombie at (x, y) on {@code level} (PZ's coordinates: x east, y south). */
    public record Zombie(double x, double y, int level) {
        public byte[] encode() {
            return ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).putDouble(x).putDouble(y).put((byte) level).array();
        }

        public static Zombie decode(byte[] bytes) {
            if (bytes.length != 17) throw new IllegalArgumentException("zombie spawn size");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Zombie(b.getDouble(), b.getDouble(), b.get());
        }
    }
}
