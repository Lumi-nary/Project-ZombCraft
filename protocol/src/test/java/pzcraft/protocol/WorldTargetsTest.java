package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorldTargetsTest {
    @Test void targetsRoundTripWithNegativeLevelsAndFullBoxes() {
        var targets = List.of(
                WorldTargets.Target.of(WorldTargets.KIND_GRASS, 0, 10700, 9800, 0, 0, .1, 0, .1, .9, .5, .9),
                WorldTargets.Target.of(WorldTargets.KIND_COVER, WorldTargets.COVER_LARGE_STONE, -3, 12, -1, "d_generic_1_14".hashCode(), .25, 0, .25, .75, .3, .75),
                WorldTargets.Target.of(WorldTargets.KIND_BOULDER, WorldTargets.boulderVariant("ironOreLarge"), 1, 2, 7, 42, 0, 0, 0, 1, 2.449, 1));
        assertEquals(targets, WorldTargets.decode(WorldTargets.encode(targets)));
        assertEquals(List.of(), WorldTargets.decode(WorldTargets.encode(List.of())));
        var t = targets.get(2);
        assertEquals(1.0, t.x1(), 1e-9);
        assertEquals(2.45, t.y1(), 1e-9);
        var g = targets.get(0);
        assertEquals(0.1, g.x0(), 0.004);
        assertEquals(0.9, g.z1(), 0.004);
        assertTrue(t.same(WorldTargets.Target.of(t.kind(), t.variant(), 1, 2, 7, 42, 0, 0, 0, .5, 1, .5)));
        assertFalse(t.same(WorldTargets.Target.of(t.kind(), t.variant(), 1, 2, 6, 42, 0, 0, 0, 1, 1, 1)));
    }

    @Test void boxesAreClampedToTheWire() {
        var t = WorldTargets.Target.of(WorldTargets.KIND_BUSH, 0, 0, 0, 0, 0, -1, -1, -1, 2, 9, 2);
        assertEquals(List.of(0, 0, 0, 254, 255, 254), List.of(t.minX(), t.minY(), t.minZ(), t.maxX(), t.maxY(), t.maxZ()));
        assertEquals(-0.25, t.x0(), 1e-9);
        assertEquals(1.34, t.x1(), 0.004);
        assertEquals(2.55, t.y1(), 1e-9);
    }

    @Test void edgeBoxesMatchTheCollisionEdgesExactly() {
        // CollisionBoxes: a wall is EDGE_HALF (0.1) either side of the tile's edge, a storey tall; a low fence LOW_EDGE high.
        double h = Coords.BLOCKS_PER_LEVEL;
        var wallN = WorldTargets.Target.of(WorldTargets.KIND_WALL, WorldTargets.edgeVariant(Materials.MAT_BRICK, true, false), 5, 6, 0, 1,
                0, 0, -CollisionBoxes.EDGE_HALF, 1, h, CollisionBoxes.EDGE_HALF);
        assertEquals(-0.1, wallN.z0(), 1e-9);
        assertEquals(0.1, wallN.z1(), 1e-9);
        assertEquals(0.0, wallN.x0(), 1e-9);
        assertTrue(wallN.y1() >= h && wallN.y1() - h < 0.01);
        var fence = WorldTargets.Target.of(WorldTargets.KIND_FENCE, WorldTargets.edgeVariant(Materials.MAT_WOOD, false, true), 5, 6, 0, 2,
                -CollisionBoxes.EDGE_HALF, 0, 0, CollisionBoxes.EDGE_HALF, CollisionBoxes.LOW_EDGE, 1);
        assertEquals(CollisionBoxes.LOW_EDGE, fence.y1(), 1e-9);
        assertEquals(fence, WorldTargets.decode(WorldTargets.encode(List.of(fence))).get(0));
    }

    @Test void edgeVariants() {
        int v = WorldTargets.edgeVariant(Materials.MAT_METAL_HEAVY, true, true);
        assertEquals(Materials.MAT_METAL_HEAVY, WorldTargets.edgeMaterial(v));
        assertTrue(WorldTargets.edgeNorth(v));
        assertTrue(WorldTargets.edgeFlag(v));
        int w = WorldTargets.edgeVariant(Materials.MAT_WOOD, false, false);
        assertFalse(WorldTargets.edgeNorth(w));
        assertFalse(WorldTargets.edgeFlag(w));
        int b = WorldTargets.barricadeVariant(false, true, 4);
        assertEquals(Materials.MAT_WOOD, WorldTargets.edgeMaterial(b));
        assertTrue(WorldTargets.edgeNorth(b));
        assertEquals(4, WorldTargets.barricadePlanks(b));
        assertEquals(7, WorldTargets.barricadePlanks(WorldTargets.barricadeVariant(true, false, 12)));
        assertEquals(Materials.MAT_METAL, WorldTargets.edgeMaterial(WorldTargets.barricadeVariant(true, false, 0)));
        assertTrue(WorldTargets.isEdge(WorldTargets.KIND_WALL) && WorldTargets.isEdge(WorldTargets.KIND_BARRICADE));
        assertFalse(WorldTargets.isEdge(WorldTargets.KIND_BOULDER));
    }

    @Test void mineRequestAndAnswerRoundTrip() {
        var target = WorldTargets.Target.of(WorldTargets.KIND_COVER, WorldTargets.COVER_STONE, 10700, 9800, -2, -123456, 0, 0, 0, 1, 1, 1);
        var mine = WorldTargets.Mine.of(77, target);
        assertEquals(mine, WorldTargets.Mine.decode(mine.encode()));
        assertEquals(-2, WorldTargets.Mine.decode(mine.encode()).level());
        var mined = new WorldTargets.Mined(77, true);
        assertEquals(mined, WorldTargets.Mined.decode(mined.encode()));
    }

    @Test void placeRequestAndAnswerRoundTrip() {
        var place = new WorldTargets.Place(9, 123456789, 10700, -9800, -1, WorldTargets.FACE_W);
        assertEquals(place, WorldTargets.Place.decode(place.encode()));
        var placed = new WorldTargets.Placed(9, false);
        assertEquals(placed, WorldTargets.Placed.decode(placed.encode()));
        assertEquals("W", WorldTargets.faceName(WorldTargets.FACE_W));
        assertEquals("N", WorldTargets.faceName(WorldTargets.FACE_N));
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.Place.decode(new byte[17]));
    }

    @Test void pieceVariants() {
        int couch = WorldTargets.pieceVariant(Materials.MAT_FABRIC, true, true, WorldTargets.face("E"));
        assertEquals(Materials.MAT_FABRIC, WorldTargets.edgeMaterial(couch));
        assertTrue(WorldTargets.isSeat(couch) && WorldTargets.isBed(couch));
        assertEquals(WorldTargets.FACE_E, WorldTargets.pieceFace(couch));
        int chair = WorldTargets.pieceVariant(Materials.MAT_WOOD, true, false, WorldTargets.face("W"));
        assertTrue(WorldTargets.isSeat(chair) && !WorldTargets.isBed(chair));
        assertEquals(WorldTargets.FACE_W, WorldTargets.pieceFace(chair));
        assertEquals(WorldTargets.FACE_S, WorldTargets.face(null));
        assertEquals(WorldTargets.FACE_N, WorldTargets.face("N"));
        assertTrue(couch < 256);
        var t = WorldTargets.Target.of(WorldTargets.KIND_FURNITURE, couch, 1, 2, 0, 3, 0, 0, 0, 1, .43, 1);
        assertEquals(t, WorldTargets.decode(WorldTargets.encode(List.of(t))).get(0));
    }

    @Test void tilePlacing() {
        var place = new WorldTargets.PlaceTile(5, 10700, -9800, 1, true, "walls_exterior_house_01_12");
        assertEquals(place, WorldTargets.PlaceTile.decode(place.encode()));
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.PlaceTile.decode(new byte[14]));
        assertArrayEquals(new int[] {0, 0, 1}, WorldTargets.nearestEdge(.5, .1));  // near the north edge
        assertArrayEquals(new int[] {0, 1, 1}, WorldTargets.nearestEdge(.5, .9));  // south edge: the next tile's north
        assertArrayEquals(new int[] {0, 0, 0}, WorldTargets.nearestEdge(.05, .5)); // west
        assertArrayEquals(new int[] {1, 0, 0}, WorldTargets.nearestEdge(.95, .5)); // east edge: the next tile's west
    }

    @Test void malformedMessagesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.decode(new byte[] {1, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.decode(new byte[] {-1, -1, -1, -1}));
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.decode(new byte[] {0, 0}));
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.Mine.decode(new byte[18]));
        assertThrows(IllegalArgumentException.class, () -> WorldTargets.Mined.decode(new byte[4]));
    }

    @Test void pzGroundCoverNames() {
        assertEquals(WorldTargets.COVER_STONE, WorldTargets.coverVariant("Stone"));
        assertEquals(WorldTargets.COVER_STONE, WorldTargets.coverVariant("Stone2"));
        assertEquals(WorldTargets.COVER_STONES, WorldTargets.coverVariant("4Stones"));
        assertEquals(WorldTargets.COVER_STONES, WorldTargets.coverVariant("Stones"));
        assertEquals(WorldTargets.COVER_STONES, WorldTargets.coverVariant("SmallStones")); // PZ takes any "...Stones"
        assertEquals(WorldTargets.COVER_LARGE_STONE_TWIGS, WorldTargets.coverVariant("LargeStoneTwigs"));
        assertEquals(WorldTargets.COVER_FLAT_STONE, WorldTargets.coverVariant("flatRock"));
        assertEquals(WorldTargets.COVER_LOG, WorldTargets.coverVariant("Log"));
        assertEquals(0, WorldTargets.coverVariant("Chair"));
        assertEquals(0, WorldTargets.coverVariant(null));
    }

    @Test void pzOreNames() {
        int large = WorldTargets.boulderVariant("ironOreLarge");
        assertEquals(WorldTargets.BOULDER_IRON, WorldTargets.boulderKind(large));
        assertEquals(WorldTargets.LARGE, WorldTargets.boulderSize(large));
        int medium = WorldTargets.boulderVariant("copperOreMedium");
        assertEquals(WorldTargets.BOULDER_COPPER, WorldTargets.boulderKind(medium));
        assertEquals(WorldTargets.MEDIUM, WorldTargets.boulderSize(medium));
        assertEquals(WorldTargets.SMALL, WorldTargets.boulderSize(WorldTargets.boulderVariant("ironOreSmall")));
        assertEquals(WorldTargets.BOULDER_FLINT, WorldTargets.boulderVariant("FlintBoulder"));
        assertEquals(WorldTargets.BOULDER_LIMESTONE, WorldTargets.boulderVariant("LimestoneBoulder"));
        assertEquals(WorldTargets.BOULDER_PLAIN, WorldTargets.boulderVariant(null));
        assertEquals(WorldTargets.BOULDER_PLAIN, WorldTargets.boulderVariant("boulder"));
    }
}
