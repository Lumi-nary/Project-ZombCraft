package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WeatherTest {
    @Test
    void commandAndStateRoundTrip() {
        Weather.Command c = Weather.Command.decode(new Weather.Command(Weather.BLIZZARD, 0.8f, 6f).encode());
        assertEquals(Weather.BLIZZARD, c.kind());
        assertEquals(0.8f, c.intensity());
        assertEquals(6f, c.hours());
        assertEquals("blizzard", Weather.name(c.kind()));
        assertEquals(Weather.FOG, Weather.NAMES.indexOf("fog"), "kinds and command words stay in step");

        Weather.State s = Weather.State.decode(new Weather.State(0.6f, true, false, 0.3f, 0.5f, 0.9f).encode());
        assertTrue(s.raining());
        assertTrue(s.snow());
        assertFalse(s.thunder());
        assertEquals(0.3f, s.fog());
        assertFalse(new Weather.State(0.01f, false, false, 0, 0, 0).raining(), "a trace of rain is not rain");
    }
}
