package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpawnSoundTest {
    @Test
    void vehicleAndZombieSpawnsRoundTrip() {
        var v = new Spawns.Vehicle("Base.CarNormal", 10837, -10179, 1, -90f);
        assertEquals(v, Spawns.Vehicle.decode(v.encode()));
        var z = new Spawns.Zombie(10837.5, 10179.25, -1);
        assertEquals(z, Spawns.Zombie.decode(z.encode()));
        assertThrows(IllegalArgumentException.class, () -> Spawns.Vehicle.decode(new byte[15]));
        assertThrows(IllegalArgumentException.class, () -> new Spawns.Vehicle("", 0, 0, 0, 0).encode());
    }

    @Test
    void soundsRoundTripAndBadOnesAreRejected() {
        var s = new McSound.Play("C:/Users/x/.pzcraft/sounds/minecraft/block/stone/step1.ogg", 1.5, -2, 3.25, .4f, 1.2f, 16f, McSound.FLAG_RELATIVE);
        var d = McSound.Play.decode(s.encode());
        assertEquals(s, d);
        assertTrue(d.relative());
        byte[] bad = s.encode();
        bad[bad.length - 1 - s.file().length()] = 9;   // path length no longer matches
        assertThrows(IllegalArgumentException.class, () -> McSound.Play.decode(bad));
        var c = new Cars.Seat(true, 4, 0, 1, 2, 3, 90f, .18f);
        assertEquals(c, Cars.Seat.decode(c.encode()));
        assertEquals(0f, Cars.Seat.decode(java.util.Arrays.copyOf(c.encode(), 34)).dashboard());   // an older PZ's seat
    }
}
