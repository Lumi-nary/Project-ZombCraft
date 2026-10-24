package pzcraft.protocol;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McStatusTest {
    @Test void roundTripKeepsPhaseProgressAndName() {
        var s = new McStatus(McStatus.LOADING, .421f, "LumiMaple");
        var back = McStatus.decode(s.encode());
        assertEquals(McStatus.LOADING, back.phase());
        assertEquals(.421f, back.progress(), 1e-3);
        assertEquals("LumiMaple", back.user());
        assertTrue(back.loading());
    }
    @Test void progressIsClampedAndSeparatorsLeaveTheName() {
        var back = McStatus.decode(new McStatus(McStatus.TITLE, 7f, "a;b=c").encode());
        assertEquals(1f, back.progress());
        assertEquals("abc", back.user());
        assertFalse(back.loading());
    }
    @Test void brokenOrForeignTextNeverThrows() {
        var back = McStatus.decode("progress=x;phase;=;zzz=1".getBytes(StandardCharsets.UTF_8));
        assertEquals("", back.phase());
        assertEquals(0f, back.progress());
        assertEquals("", back.user());
        assertEquals("", McStatus.decode(new byte[0]).phase());
    }
}
