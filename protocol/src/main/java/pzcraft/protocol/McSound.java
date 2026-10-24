package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft's sounds played by PZ: Minecraft hands over a one-shot sound as its .ogg file (cached on disk), where it is in
 * Minecraft's coordinates, its final volume (Minecraft's own volume sliders applied) and pitch, and PZ plays it through its
 * own audio (FMOD), so it follows PZ's volume and comes out of PZ's window. Looping, moving and streamed sounds (music,
 * records, rain, minecarts) stay with Minecraft.
 */
public final class McSound {
    /** Minecraft -> PZ: {@link Play}. */
    public static final int MSG_SOUND = 98;
    /** PZ -> Minecraft: PZ cannot play Minecraft's sounds (UTF-8 reason): Minecraft plays them itself again. */
    public static final int MSG_SOUND_FAILED = 99;
    /** No distance falloff (interface clicks, the player's own sounds heard "at the ear"). */
    public static final int FLAG_RELATIVE = 1;

    private McSound() {}

    /** {@code range}: where it fades out, in blocks (Minecraft's 16 x volume for the usual linear falloff). */
    public record Play(String file, double x, double y, double z, float volume, float pitch, float range, int flags) {
        public boolean relative() { return (flags & FLAG_RELATIVE) != 0; }

        public byte[] encode() {
            byte[] path = file.getBytes(StandardCharsets.UTF_8);
            if (path.length < 1 || path.length > 1024) throw new IllegalArgumentException("sound file path");
            return ByteBuffer.allocate(24 + 12 + 1 + 2 + path.length).order(ByteOrder.LITTLE_ENDIAN).putDouble(x).putDouble(y).putDouble(z)
                    .putFloat(volume).putFloat(pitch).putFloat(range).put((byte) flags).putShort((short) path.length).put(path).array();
        }

        public static Play decode(byte[] bytes) {
            if (bytes.length < 40 || bytes.length > 39 + 1024) throw new IllegalArgumentException("sound size");
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
            float volume = b.getFloat(), pitch = b.getFloat(), range = b.getFloat();
            int flags = b.get() & 255, n = b.getShort();
            if (n < 1 || n != b.remaining()) throw new IllegalArgumentException("sound file path");
            byte[] path = new byte[n];
            b.get(path);
            if (!Float.isFinite(volume) || !Float.isFinite(pitch) || !Float.isFinite(range)) throw new IllegalArgumentException("sound values");
            return new Play(new String(path, StandardCharsets.UTF_8), x, y, z, volume, pitch, range, flags);
        }
    }
}
