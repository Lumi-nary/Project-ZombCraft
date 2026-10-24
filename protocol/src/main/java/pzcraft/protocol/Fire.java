package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Fire between the two worlds. Minecraft fire on a PZ zombie or a PZ square starts PZ's own fire (PZ burns, spreads and
 * draws it); PZ's fires near Steve go back to Minecraft, which sets flammable Minecraft blocks next to them alight and
 * Steve when he stands in one. PZ's burning zombies carry {@link Wire.Actor#FLAG_BURNING}.
 */
public final class Fire {
    public static final int MSG_IGNITE_ACTOR = 92; // MC -> PZ: Minecraft set this PZ actor (int id) alight: PZ burns it its own way
    public static final int MSG_IGNITE = 93;       // MC -> PZ: Minecraft fire at a PZ square (Ignite)
    public static final int MSG_FIRES = 94;        // PZ -> MC: PZ's burning squares near Steve (Spot list), when they change
    public static final int MAX_FIRES = 512;

    /** A PZ square: tile x, tile y and level. */
    public record Spot(int x, int y, int level) {}

    /**
     * Fire at a PZ square. {@code anywhere}: it burns even where nothing burns (Steve's flint and steel, a fire charge, a
     * burning arrow, as PZ's molotov); else only where PZ finds something to burn (Minecraft fire spreading next to it).
     */
    public record Ignite(Spot spot, int energy, boolean anywhere) {
        public byte[] encode() {
            return ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).putInt(spot.x).putInt(spot.y).putInt(spot.level)
                    .putInt(energy).put((byte) (anywhere ? 1 : 0)).array();
        }

        public static Ignite decode(byte[] data) {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            return new Ignite(new Spot(b.getInt(), b.getInt(), b.getInt()), b.getInt(), b.get() != 0);
        }
    }

    public static byte[] encodeSpots(List<Spot> spots) {
        int n = Math.min(spots.size(), MAX_FIRES);
        ByteBuffer b = ByteBuffer.allocate(4 + n * 12).order(ByteOrder.LITTLE_ENDIAN).putInt(n);
        for (int i = 0; i < n; i++) b.putInt(spots.get(i).x).putInt(spots.get(i).y).putInt(spots.get(i).level);
        return b.array();
    }

    public static List<Spot> decodeSpots(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        if (n < 0 || n > MAX_FIRES || b.remaining() < n * 12) throw new IllegalArgumentException("bad fire list: " + n);
        List<Spot> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Spot(b.getInt(), b.getInt(), b.getInt()));
        return out;
    }

    private Fire() {}
}
