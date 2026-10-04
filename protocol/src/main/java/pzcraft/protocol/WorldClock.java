package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Explicit Minecraft clock changes and PZ acknowledgements. MC tick zero is 06:00. */
public final class WorldClock {
    private WorldClock() {}
    public record State(long acknowledged, long ticks) {
        public byte[] encode() { return buffer(16).putLong(acknowledged).putLong(ticks).array(); }
        public static State decode(byte[] bytes) { var b = read(bytes, 16); return new State(b.getLong(), b.getLong()); }
    }
    public record Change(long sequence, long before, long after) {
        public byte[] encode() { return buffer(24).putLong(sequence).putLong(before).putLong(after).array(); }
        public static Change decode(byte[] bytes) { var b = read(bytes, 24); return new Change(b.getLong(), b.getLong(), b.getLong()); }
    }
    public static float hour(long ticks) { return (float) ((Math.floorMod(ticks, 24000L) / 1000.0 + 6) % 24); }
    public static long calendarDays(long before, long after) {
        return Math.floorDiv(after + 6000, 24000) - Math.floorDiv(before + 6000, 24000);
    }
    public static long nights(long before, long after) {
        return Math.floorDiv(after - 1000, 24000) - Math.floorDiv(before - 1000, 24000);
    }
    private static ByteBuffer buffer(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer read(byte[] bytes, int n) {
        if (bytes.length != n) throw new IllegalArgumentException("Invalid clock payload");
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
