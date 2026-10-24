package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class MeleeHitTest {
    @Test void preservesExactItemAndSwingTargetIdentity() {
        var hit = new Wire.MeleeHit(123, 551789712, 3, "Base.Katana");
        assertEquals(hit, Wire.decodeMeleeHit(Wire.encodeMeleeHit(hit)));
        var unassigned = new Wire.MeleeHit(456, -1, 1, "Custom.Épée");
        assertEquals(unassigned, Wire.decodeMeleeHit(Wire.encodeMeleeHit(unassigned)));
    }
    @Test void refusesTruncatedOrInvalidTargetContacts() {
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeMeleeHit(new byte[16]));
        byte[] bytes = Wire.encodeMeleeHit(new Wire.MeleeHit(1, 2, 1, "Base.Hammer"));
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0);
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeMeleeHit(bytes));
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1).putInt(12, 8000);
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeMeleeHit(bytes));
        assertThrows(IllegalArgumentException.class, () -> Wire.encodeMeleeHit(new Wire.MeleeHit(1, 2, 0, "Base.Hammer")));
    }
}
