package pzcraft.protocol;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleHullTest {
    @Test void snapshotRoundTripAndRemoval() {
        var hulls = List.of(new VehicleHull(0, 1.6, 10, 20, 12, 20, 12, 24, 10, 24));
        assertEquals(hulls, VehicleHull.decode(VehicleHull.encode(hulls)));
        assertEquals(List.of(), VehicleHull.decode(VehicleHull.encode(List.of())));
        assertThrows(IllegalArgumentException.class, () -> VehicleHull.decode(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> VehicleHull.decode(new byte[] {65, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> new VehicleHull(0, 1, Double.NaN, 0, 1, 0, 1, 1, 0, 1));
    }
    @Test void diagonalCarHasNoCollisionInEmptyBoundingBoxCorners() {
        double[] b = new VehicleHull(0.1, 1.7, 0, 2, 2, 0, 4, 2, 2, 4).boxes();
        assertTrue(contains(b, 2, 1, 2));
        assertTrue(contains(b, .6, 1, 2));
        assertFalse(contains(b, .2, 1, .2));
        assertFalse(contains(b, 3.8, 1, 3.8));
        assertFalse(contains(b, 2, 1.8, 2));
        for (int i = 0; i < b.length; i += 6) assertTrue(b[i + 5] - b[i + 2] <= .125001);
    }
    @Test void carCanMoveWithoutLeavingOldShapesAndHasRealHeight() {
        var before = new VehicleHull(2.5, 4, 100, 200, 102, 200, 102, 204, 100, 204);
        var after = new VehicleHull(2.5, 4, 110, 200, 112, 200, 112, 204, 110, 204);
        assertTrue(contains(before.boxes(), 101, 3, 202));
        assertFalse(contains(after.boxes(), 101, 3, 202));
        assertTrue(contains(after.boxes(), 111, 3, 202));
        assertFalse(contains(after.boxes(), 111, 2, 202));
    }
    private static boolean contains(double[] b, double x, double y, double z) {
        for (int i = 0; i < b.length; i += 6)
            if (x >= b[i] && x <= b[i + 3] && y >= b[i + 1] && y <= b[i + 4] && z >= b[i + 2] && z <= b[i + 5]) return true;
        return false;
    }
}
