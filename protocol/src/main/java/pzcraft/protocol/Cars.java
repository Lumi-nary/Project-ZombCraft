package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * PZ's cars in Steve's hands (W5). PZ -> Minecraft: the sides of the cars near Steve ({@link Side}: where you get in and
 * which window you can hit there) and, while the PZ player sits in a car, where ({@link Seat}). Minecraft -> PZ: get in
 * ({@link Enter}), get out (an empty message), hit a window ({@link Hit}). Cars are PZ's (its physics, controls, keys,
 * fuel); Minecraft only carries Steve along. Coordinates are Minecraft's.
 */
public final class Cars {
    private Cars() {}

    /** PZ -> MC: {@link Side}s of the cars near Steve, five times a second while there are any. */
    public static final int MSG_SIDES = 83;
    /** PZ -> MC: {@link Seat}: the PZ player sits in a car (or not any more), 20 times a second while in one. */
    public static final int MSG_SEAT = 84;
    /** MC -> PZ: {@link Enter}: get into this seat. */
    public static final int MSG_ENTER = 85;
    /** MC -> PZ: get out of the car (no payload). */
    public static final int MSG_EXIT = 86;
    /** MC -> PZ: {@link Hit}: Steve hit this car window. */
    public static final int MSG_HIT = 87;
    /** PZ -> MC: why getting in did not work (UTF-8 text for the hotbar line). */
    public static final int MSG_REFUSED = 88;

    /** One side of a car: the area PZ lets you get in from (its seat, -1 none) and the window there (part index, -1 none). */
    public record Side(int vehicle, int seat, int windowPart, int flags,
                       float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        public static final int LOCKED = 1, WINDOW_INTACT = 2, OCCUPIED = 4;

        public boolean has(int flag) { return (flags & flag) != 0; }
    }

    public static final int MAX_SIDES = 256;
    private static final int SIDE_BYTES = 4 + 1 + 2 + 1 + 6 * 4;

    public static byte[] encodeSides(List<Side> sides) {
        if (sides.size() > MAX_SIDES) throw new IllegalArgumentException("Too many car sides");
        ByteBuffer b = ByteBuffer.allocate(4 + sides.size() * SIDE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(sides.size());
        for (Side s : sides) {
            b.putInt(s.vehicle).put((byte) s.seat).putShort((short) s.windowPart).put((byte) s.flags)
                    .putFloat(s.minX).putFloat(s.minY).putFloat(s.minZ).putFloat(s.maxX).putFloat(s.maxY).putFloat(s.maxZ);
        }
        return b.array();
    }

    public static List<Side> decodeSides(byte[] bytes) {
        if (bytes.length < 4) throw new IllegalArgumentException("Truncated car sides");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        if (n < 0 || n > MAX_SIDES || bytes.length != 4 + n * SIDE_BYTES) throw new IllegalArgumentException("Invalid car side count");
        List<Side> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Side(b.getInt(), b.get(), b.getShort(), b.get() & 0xFF,
                    b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat(), b.getFloat()));
        }
        return List.copyOf(out);
    }

    /** PZ -> MC: whether the PZ player sits in a car, which one and seat, where (Minecraft's x, y, z) and the car's yaw. */
    /**
     * Where the PZ player sits. {@code dashboard}: how much of the screen's height PZ's dashboard covers at the bottom (0 when
     * it is not shown): Minecraft lifts its hotbar and bars above it.
     */
    public record Seat(boolean inCar, int vehicle, int seat, double x, double y, double z, float yawDeg, float dashboard) {
        public static final Seat NONE = new Seat(false, -1, -1, 0, 0, 0, 0, 0);

        public Seat(boolean inCar, int vehicle, int seat, double x, double y, double z, float yawDeg) {
            this(inCar, vehicle, seat, x, y, z, yawDeg, 0);
        }

        public byte[] encode() {
            return ByteBuffer.allocate(1 + 4 + 1 + 3 * 8 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN).put((byte) (inCar ? 1 : 0)).putInt(vehicle)
                    .put((byte) seat).putDouble(x).putDouble(y).putDouble(z).putFloat(yawDeg).putFloat(dashboard).array();
        }

        public static Seat decode(byte[] bytes) {
            if (bytes.length != 34 && bytes.length != 38) throw new IllegalArgumentException("Bad car seat");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Seat(b.get() != 0, b.getInt(), b.get(), b.getDouble(), b.getDouble(), b.getDouble(), b.getFloat(),
                    bytes.length == 38 ? b.getFloat() : 0);
        }
    }

    /** Minecraft -> PZ: Shift+right-click on this car: open PZ's own menu for it (hood, trunk, doors, mechanics...). */
    public static final int MSG_MENU = 95;

    public record Enter(int vehicle, int seat) {
        public byte[] encode() { return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(vehicle).put((byte) seat).array(); }

        public static Enter decode(byte[] bytes) {
            if (bytes.length != 5) throw new IllegalArgumentException("Bad car enter");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Enter(b.getInt(), b.get());
        }
    }

    /** Steve hit window part {@code part} of car {@code vehicle} for {@code amount} of its condition (100 = a whole window). */
    public record Hit(int vehicle, int part, int amount) {
        public byte[] encode() { return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(vehicle).putShort((short) part).putInt(amount).array(); }

        public static Hit decode(byte[] bytes) {
            if (bytes.length != 10) throw new IllegalArgumentException("Bad car hit");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return new Hit(b.getInt(), b.getShort(), b.getInt());
        }
    }

    /**
     * How much of a window's condition one hit takes: ten times Minecraft's attack damage, scaled by how charged the attack
     * was, at least 5. A fist (1) needs ten full hits, an iron sword (6) two, an axe (9) two.
     */
    public static int windowDamage(double attackDamage, float charge) {
        return (int) Math.max(5, Math.round(attackDamage * 10 * Math.max(0.2, Math.min(1, charge))));
    }
}
