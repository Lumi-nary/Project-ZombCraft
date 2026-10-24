package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Server-selected respawn location. Retransmission uses the same sequence until PZ has streamed collision. */
public record RespawnTarget(long sequence, double x, double y, double z, float yaw, float pitch) {
    public byte[] encode() { return ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sequence).putDouble(x).putDouble(y).putDouble(z).putFloat(yaw).putFloat(pitch).array(); }
    public static RespawnTarget decode(byte[] bytes) {
        if (bytes.length != 40) throw new IllegalArgumentException("Invalid respawn target");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var target = new RespawnTarget(b.getLong(), b.getDouble(), b.getDouble(), b.getDouble(), b.getFloat(), b.getFloat());
        if (!Double.isFinite(target.x) || !Double.isFinite(target.y) || !Double.isFinite(target.z))
            throw new IllegalArgumentException("Non-finite respawn target");
        return target;
    }
}
