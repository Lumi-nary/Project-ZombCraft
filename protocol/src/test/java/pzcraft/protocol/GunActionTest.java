package pzcraft.protocol;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GunActionTest {
    @Test void requestPreservesNativeIdentityAndServerImpact() {
        var r = new GunAction(19, GunAction.FIRE, 1234567, 77, GunAction.PZ_SURFACE, 2100.25, 1.62, 6000.75, "Base.Pistol");
        assertEquals(r, GunAction.decode(r.encode()));
    }
    @Test void everyTargetOfOneShotSurvivesTheWire() {
        var r = new GunAction(20, GunAction.FIRE, 55, 12, GunAction.MC_BLOCK, 2100.25, 1.62, 6000.75, "Base.Shotgun", List.of(14, 301, 7, 99));
        var back = GunAction.decode(r.encode());
        assertEquals(r, back);
        assertEquals(List.of(14, 301, 7, 99), back.targets());
        var none = new GunAction(21, GunAction.FIRE, 55, -1, GunAction.MISS, 0, 0, 0, "Base.Shotgun", List.of());
        assertEquals(none, GunAction.decode(none.encode()));
    }
    @Test void magazineRequestsCarryTheMagazineIdentity() {
        for (int action : new int[] {GunAction.LOAD_MAGAZINE, GunAction.UNLOAD_MAGAZINE, GunAction.LOAD_ONE_ROUND, GunAction.EJECT_MAGAZINE, GunAction.INSPECT, GunAction.FIRE_MODE, GunAction.RACK}) {
            var r = new GunAction(5, action, 987654, -1, GunAction.MISS, 0, 0, 0, "Base.9mmClip");
            assertEquals(r, GunAction.decode(r.encode()));
        }
    }
    @Test void malformedRequestCannotReachGameplay() {
        assertThrows(IllegalArgumentException.class, () -> GunAction.decode(new byte[48]));
        assertThrows(IllegalArgumentException.class, () -> GunAction.decode(new byte[49]));
        assertThrows(IllegalArgumentException.class, () -> new GunAction(0, 0, 7, -1, 0, 0, 0, 0, "Base.Pistol").encode());
        assertThrows(IllegalArgumentException.class, () -> new GunAction(1, GunAction.RACK + 1, 7, -1, 0, 0, 0, 0, "Base.Pistol").encode());
        assertThrows(IllegalArgumentException.class, () -> new GunAction(1, 0, 7, -1, 0, Double.NaN, 0, 0, "Base.Pistol").encode());
        assertThrows(IllegalArgumentException.class, () -> new GunAction(1, 0, 7, -1, 0, 0, 0, 0, "Base.Pistol", java.util.Collections.nCopies(GunAction.MAX_TARGETS + 1, 3)).encode());
        // a target count that claims more actors than the message holds
        byte[] lie = new GunAction(1, 0, 7, -1, 0, 0, 0, 0, "Base.Pistol").encode();
        lie[48] = 9;
        assertThrows(IllegalArgumentException.class, () -> GunAction.decode(lie));
    }

    @Test
    void timingHintsRoundTrip() {
        var normal = new GunAction.Timing(1.82, new double[][] {{.15, GunAction.CUE_EJECT}, {.6667, GunAction.CUE_INSERT}, {1.1, GunAction.CUE_REST}});
        var empty = new GunAction.Timing(2.9, new double[][] {{.8667, GunAction.CUE_EJECT}, {1.3833, GunAction.CUE_INSERT}, {2.15, GunAction.CUE_RACK}});
        var back = GunAction.decode(new GunAction(5, GunAction.RELOAD, 7, -1, 0, 0, 0, 0, "Base.AssaultRifle", GunAction.timing(normal, empty)).encode());
        assertEquals(1.82, back.timing(false).phase(), 1e-9);
        assertEquals(3, back.timing(false).cues().length);
        assertEquals(.6667, back.timing(false).cues()[1][0], 1e-3);
        assertEquals(GunAction.CUE_RACK, (int) back.timing(true).cues()[2][1]);
        assertEquals(2.9, back.timing(true).phase(), 1e-9);
        // a model without the empty variant: PZ falls back to the other one itself
        var only = GunAction.decode(new GunAction(6, GunAction.INSPECT, 7, -1, 0, 0, 0, 0, "Base.Shotgun", GunAction.timing(normal, null)).encode());
        assertEquals(3, only.timing(false).cues().length);
        assertNull(only.timing(true));
        // a shot's target list is never taken for a timing list that says more than it holds
        assertNull(new GunAction(8, GunAction.FIRE, 7, 3, 0, 0, 0, 0, "Base.Shotgun", java.util.List.of(5, 301)).timing(false));
        assertNull(new GunAction(9, GunAction.RELOAD, 7, -1, 0, 0, 0, 0, "Base.Pistol").timing(false));
    }
}
