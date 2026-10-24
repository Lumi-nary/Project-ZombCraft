package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FireTest {
    @Test void igniteRoundTrip() {
        var ignite = new Fire.Ignite(new Fire.Spot(10712, -9803, 1), 60, true);
        assertEquals(ignite, Fire.Ignite.decode(ignite.encode()));
        var spread = new Fire.Ignite(new Fire.Spot(0, 0, -1), 30, false);
        assertEquals(spread, Fire.Ignite.decode(spread.encode()));
    }

    @Test void spotsRoundTrip() {
        var spots = List.of(new Fire.Spot(10712, 9803, 0), new Fire.Spot(-4, 7, 2));
        assertEquals(spots, Fire.decodeSpots(Fire.encodeSpots(spots)));
        assertEquals(List.of(), Fire.decodeSpots(Fire.encodeSpots(List.of())));
    }

    @Test void spotListIsCapped() {
        var many = new ArrayList<Fire.Spot>();
        for (int i = 0; i < Fire.MAX_FIRES + 10; i++) many.add(new Fire.Spot(i, i, 0));
        assertEquals(Fire.MAX_FIRES, Fire.decodeSpots(Fire.encodeSpots(many)).size());
    }

    @Test void malformedRejected() {
        assertThrows(IllegalArgumentException.class, () -> Fire.decodeSpots(new byte[] {5, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> Fire.decodeSpots(new byte[] {-1, -1, -1, -1}));
    }

    @Test void messageIdsAreFree() {
        assertTrue(Fire.MSG_IGNITE_ACTOR > Wire.MSG_ZOMBIE_THUMP);
        assertNotEquals(Fire.MSG_IGNITE, Fire.MSG_FIRES);
        assertEquals(32, Wire.Actor.FLAG_BURNING);
    }
}
