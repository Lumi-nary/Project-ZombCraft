package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * An explosion in Minecraft and the PZ objects its rays reached with power to spare, Minecraft -> PZ. Minecraft traces the
 * blast through PZ's walls, doors, windows, furniture and upper floors the way vanilla traces it through blocks; PZ then
 * smashes, removes or topples exactly those. Coordinates of the blast are Minecraft's; targets are PZ tiles.
 */
public final class PzExplosion {
    private PzExplosion() {}

    /** Target kinds. */
    public static final int KIND_EDGE_N = 1, KIND_EDGE_W = 2, KIND_SOLID = 3, KIND_FLOOR = 4;
    public static final int MAX_TARGETS = 2048;

    /** What was hit: its kind, PZ tile (x, y, level) and the material value Minecraft saw ({@link Materials#edge(int, int)} for edges). */
    public record Target(int kind, int x, int y, int level, int value) {}

    public record Blast(double x, double y, double z, float radius, boolean fire, List<Target> targets) {}

    public static byte[] encode(Blast b) {
        int n = Math.min(b.targets().size(), MAX_TARGETS);
        ByteBuffer out = ByteBuffer.allocate(8 * 3 + 4 + 1 + 4 + n * 14).order(ByteOrder.LITTLE_ENDIAN);
        out.putDouble(b.x()).putDouble(b.y()).putDouble(b.z()).putFloat(b.radius()).put((byte) (b.fire() ? 1 : 0)).putInt(n);
        for (int i = 0; i < n; i++) {
            Target t = b.targets().get(i);
            out.put((byte) t.kind()).putInt(t.x()).putInt(t.y()).putInt(t.level()).put((byte) t.value());
        }
        return out.array();
    }

    public static Blast decode(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        double x = in.getDouble(), y = in.getDouble(), z = in.getDouble();
        float radius = in.getFloat();
        boolean fire = in.get() != 0;
        int n = in.getInt();
        if (n < 0 || n > MAX_TARGETS || in.remaining() != n * 14) throw new IllegalArgumentException("Bad explosion payload");
        List<Target> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) targets.add(new Target(in.get(), in.getInt(), in.getInt(), in.getInt(), in.get() & 0xFF));
        return new Blast(x, y, z, radius, fire, List.copyOf(targets));
    }
}
