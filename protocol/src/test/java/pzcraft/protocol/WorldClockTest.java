package pzcraft.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldClockTest {
    @Test void calendarRolloverIsMidnightNotMinecraftDawn() {
        assertEquals(0, WorldClock.calendarDays(17000, 17999));
        assertEquals(1, WorldClock.calendarDays(17999, 18000));
        assertEquals(-1, WorldClock.calendarDays(18000, 0));
        assertEquals(0, WorldClock.nights(0, 999));
        assertEquals(1, WorldClock.nights(999, 1000));
        assertEquals(6, WorldClock.hour(0));
        assertEquals(0, WorldClock.hour(18000));
    }
    @Test void clocksAndRespawnPreserveLargeCoordinatesAndSequences() {
        var change = new WorldClock.Change(9000000000000L, 18000, 52000);
        assertEquals(change, WorldClock.Change.decode(change.encode()));
        var state = new WorldClock.State(change.sequence(), change.after());
        assertEquals(state, WorldClock.State.decode(state.encode()));
        var target = new RespawnTarget(9000000000001L, 10888.25, 2.47, 9999.125, 45, -30);
        assertEquals(target, RespawnTarget.decode(target.encode()));
        assertThrows(IllegalArgumentException.class, () -> RespawnTarget.decode(new byte[32]));
        assertThrows(IllegalArgumentException.class, () -> WorldClock.Change.decode(new byte[16]));
    }
}
