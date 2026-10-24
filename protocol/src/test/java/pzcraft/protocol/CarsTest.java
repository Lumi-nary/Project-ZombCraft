package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CarsTest {
    @Test void sidesRoundTrip() {
        var sides = List.of(new Cars.Side(512, 0, 7, Cars.Side.LOCKED | Cars.Side.WINDOW_INTACT, 10700.5f, 0f, 9800.25f, 10702f, 1.8f, 9801f),
                new Cars.Side(-3, -1, -1, 0, 1, 2, 3, 4, 5, 6));
        var back = Cars.decodeSides(Cars.encodeSides(sides));
        assertEquals(sides, back);
        assertTrue(back.get(0).has(Cars.Side.LOCKED) && back.get(0).has(Cars.Side.WINDOW_INTACT) && !back.get(0).has(Cars.Side.OCCUPIED));
        assertEquals(-1, back.get(1).seat());
        assertEquals(-1, back.get(1).windowPart());
        assertEquals(List.of(), Cars.decodeSides(Cars.encodeSides(List.of())));
    }

    @Test void seatEnterHitRoundTrip() {
        var seat = new Cars.Seat(true, 77, 1, 10700.125, 0.3, -9800.5, -92.5f);
        assertEquals(seat, Cars.Seat.decode(seat.encode()));
        assertEquals(Cars.Seat.NONE, Cars.Seat.decode(Cars.Seat.NONE.encode()));
        var enter = new Cars.Enter(77, 3);
        assertEquals(enter, Cars.Enter.decode(enter.encode()));
        var hit = new Cars.Hit(77, 12, 60);
        assertEquals(hit, Cars.Hit.decode(hit.encode()));
    }

    @Test void malformedRejected() {
        assertThrows(IllegalArgumentException.class, () -> Cars.decodeSides(new byte[] {1, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> Cars.Seat.decode(new byte[33]));
        assertThrows(IllegalArgumentException.class, () -> Cars.Hit.decode(new byte[9]));
    }

    @Test void windowDamage() {
        assertEquals(10, Cars.windowDamage(1, 1f));   // a fist: ten hits for a whole window
        assertEquals(60, Cars.windowDamage(6, 1f));   // an iron sword
        assertEquals(5, Cars.windowDamage(1, 0f));    // spam-clicking still scratches it
        assertEquals(12, Cars.windowDamage(6, 0.2f));
    }

    @Test void idsDoNotClashWithWire() {
        for (int id : new int[] {Cars.MSG_SIDES, Cars.MSG_SEAT, Cars.MSG_ENTER, Cars.MSG_EXIT, Cars.MSG_HIT, Cars.MSG_REFUSED})
            assertTrue(id > Wire.MSG_FURNITURE_PLACED, "message " + id);
    }
}
