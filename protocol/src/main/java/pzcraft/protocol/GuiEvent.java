package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Ordered window events. Coordinates are fractions of the PZ client area, measured from the top left. */
public record GuiEvent(int sequence, int type, int value, int action, int modifiers, double x, double y) {
    public static final int KEY = 1, CHARACTER = 2, BUTTON = 3, MOVE = 4, SCROLL = 5;
    public static final int CAMERA_MODE = 6; // explicit local control command; value = vanilla camera ordinal
    public static final int WINDOW_MODE = 7; // local dev A/B: 1 hidden, 0 minimized; never takes focus
    public byte[] encode() {
        return ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence).putInt(type).putInt(value)
                .putInt(action).putInt(modifiers).putDouble(x).putDouble(y).array();
    }
    public static GuiEvent decode(byte[] bytes) {
        if (bytes.length != 36) throw new IllegalArgumentException("invalid GUI event size");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return new GuiEvent(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getDouble(), b.getDouble());
    }
}
