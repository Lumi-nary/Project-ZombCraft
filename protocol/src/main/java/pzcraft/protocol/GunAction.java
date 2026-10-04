package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Identity-only gun requests. PZ owns ammo, damage, wear and the outcome. */
public record GunAction(long sequence, int action, int itemId, int actorId, int impactKind,
                        double x, double y, double z, String weaponType) {
    public static final int FIRE = 0, RELOAD = 1, CANCEL = 2, LOAD_MAGAZINE = 3, UNLOAD_MAGAZINE = 4, LOAD_ONE_ROUND = 5;
    public static final int MISS = 0, PZ_SURFACE = 1, MC_BLOCK = 2;
    private static final int HEADER = 48;

    public byte[] encode() {
        byte[] type = weaponType.getBytes(StandardCharsets.UTF_8);
        validate(type.length);
        return ByteBuffer.allocate(HEADER + type.length).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(sequence).putInt(action).putInt(itemId).putInt(actorId).putInt(impactKind)
                .putDouble(x).putDouble(y).putDouble(z).put(type).array();
    }

    public static GunAction decode(byte[] bytes) {
        if (bytes.length <= HEADER || bytes.length > HEADER + 512) throw new IllegalArgumentException("gun request size");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var r = new GunAction(b.getLong(), b.getInt(), b.getInt(), b.getInt(), b.getInt(),
                b.getDouble(), b.getDouble(), b.getDouble(), new String(bytes, HEADER, bytes.length - HEADER, StandardCharsets.UTF_8));
        r.validate(bytes.length - HEADER);
        return r;
    }

    private void validate(int typeLength) {
        if (sequence <= 0 || action < FIRE || action > LOAD_ONE_ROUND || itemId < 0 || impactKind < MISS || impactKind > MC_BLOCK
                || typeLength < 1 || typeLength > 512 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new IllegalArgumentException("invalid gun request");
    }
}
