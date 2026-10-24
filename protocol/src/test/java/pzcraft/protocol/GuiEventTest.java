package pzcraft.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuiEventTest {
    @Test void unicodeAndModifiersSurviveTransport() {
        GuiEvent text = new GuiEvent(37, GuiEvent.CHARACTER, 0x1f642, 0, 3, 0, 0);
        assertEquals(text, GuiEvent.decode(text.encode()));
        GuiEvent click = new GuiEvent(38, GuiEvent.BUTTON, 1, 1, 1, .345, .765);
        assertEquals(click, GuiEvent.decode(click.encode()));
    }
    @Test void truncatedEventCannotBecomeAKeyPress() {
        assertThrows(IllegalArgumentException.class, () -> GuiEvent.decode(new byte[35]));
    }
}
