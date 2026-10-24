package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaterialSectionTest {
    @Test
    void emptyAndUniformSectionsAreTiny() {
        MaterialSection sky = new MaterialSection(3, -4, 2);
        assertTrue(sky.isEmpty());
        assertEquals(12 + 9, sky.encode().length);

        MaterialSection field = new MaterialSection(3, -4, 0);
        java.util.Arrays.fill(field.floor, (byte) Materials.FLOOR_GRASS);
        assertFalse(field.isEmpty());
        assertEquals(12 + 8 + 2, field.encode().length);
        MaterialSection back = MaterialSection.decode(field.encode());
        assertEquals(3, back.cx);
        assertEquals(-4, back.cy);
        assertEquals(0, back.level);
        assertArrayEquals(field.floor, back.floor);
        assertTrue(back.contentHash() == field.contentHash());
    }

    @Test
    void mixedSectionRoundTripsEveryField() {
        MaterialSection s = new MaterialSection(-1, 7, 1);
        for (int i = 0; i < MaterialSection.TILES; i++) {
            s.floor[i] = (byte) (i % Materials.FLOOR_CLASSES);
            s.flags[i] = (byte) (i % 5 == 0 ? Materials.TILE_TREE | Materials.TILE_BASEMENT : 0);
            s.solid[i] = (byte) (i % 7 == 0 ? Materials.MAT_METAL_HEAVY : 0);
            s.edgeN[i] = (byte) (i % 3 == 0 ? Materials.edge(Materials.KIND_WINDOW_FRAME, Materials.MAT_OTHER) : 0);
            s.edgeW[i] = (byte) (i % 4 == 0 ? Materials.edge(Materials.KIND_DOOR, Materials.MAT_WOOD) : 0);
            s.treeSize[i] = (byte) (i % 9);
            s.treeKind[i] = (byte) (i % Materials.TREE_KINDS);
            s.props[i] = (byte) (i % 3 == 0 ? Materials.MAT_FABRIC : 0);
            s.roof[i] = (byte) (i % 5 == 0 ? Materials.MAT_WOOD : 0);
        }
        MaterialSection d = MaterialSection.decode(s.encode());
        assertArrayEquals(s.floor, d.floor);
        assertArrayEquals(s.flags, d.flags);
        assertArrayEquals(s.solid, d.solid);
        assertArrayEquals(s.edgeN, d.edgeN);
        assertArrayEquals(s.edgeW, d.edgeW);
        assertArrayEquals(s.treeSize, d.treeSize);
        assertArrayEquals(s.treeKind, d.treeKind);
        assertArrayEquals(s.props, d.props);
        assertArrayEquals(s.roof, d.roof);
        assertEquals(s.key64(), d.key64());
        assertEquals(Materials.KIND_WINDOW_FRAME, Materials.edgeKind(MaterialSection.unsigned(d.edgeN[0])));
        assertEquals(Materials.MAT_OTHER, Materials.edgeMaterial(MaterialSection.unsigned(d.edgeN[0])));
    }

    @Test
    void oldSectionsAndHitsStillDecodeAndBlastOriginsSurvive() {
        MaterialSection old = MaterialSection.decode(new byte[19]);
        assertTrue(old.isEmpty());
        Wire.Hit melee = new Wire.Hit(4, 3, 1, 0);
        assertEquals(16, Wire.encodeHit(melee).length);
        assertEquals(melee, Wire.decodeHit(Wire.encodeHit(melee)));
        Wire.Hit blast = new Wire.Hit(4, 20, -1, 0, true, 100.25, 2.5, -31.75);
        assertEquals(blast, Wire.decodeHit(Wire.encodeHit(blast)));
    }

    @Test
    void anyChangeChangesTheHashAndSharesTheCollisionKey() {
        MaterialSection a = new MaterialSection(1, 2, 0), b = new MaterialSection(1, 2, 0);
        assertEquals(a.contentHash(), b.contentHash());
        b.treeSize[63] = 4;
        assertNotEquals(a.contentHash(), b.contentHash());
        assertEquals(new CollisionSection(1, 2, 0).key64(), a.key64());
    }

    @Test
    void resistancesFollowVanillaBlocks() {
        assertTrue(Materials.resistance(Materials.MAT_GLASS) < Materials.resistance(Materials.MAT_WOOD));
        assertTrue(Materials.resistance(Materials.MAT_WOOD) < Materials.resistance(Materials.MAT_BRICK));
        assertTrue(Materials.resistance(Materials.MAT_BRICK) < Materials.resistance(Materials.MAT_METAL_HEAVY));
        assertEquals(Materials.MAT_WOOD, Materials.edgeMaterial(Materials.edge(Materials.KIND_FENCE, Materials.MAT_WOOD)));
        assertEquals(Materials.KIND_FENCE, Materials.edgeKind(Materials.edge(Materials.KIND_FENCE, Materials.MAT_WOOD)));
    }

    @Test
    void groundEditRoundTrips() {
        assertArrayEquals(new int[] {2158, -6113, 1}, Wire.decodeGroundEdit(Wire.encodeGroundEdit(2158, -6113, true)));
        assertArrayEquals(new int[] {0, 7, 0}, Wire.decodeGroundEdit(Wire.encodeGroundEdit(0, 7, false)));
    }

    @Test
    void groundSurfaceRoundTrips() {
        byte[] m = Wire.encodeGroundSurface(10807, -42, -3.5f);
        assertArrayEquals(new int[] {10807, -42, Wire.GROUND_SURFACE}, Wire.decodeGroundEdit(m));
        org.junit.jupiter.api.Assertions.assertEquals(-3.5f, Wire.decodeGroundSurface(m));
    }

    @Test
    void tileMessageRoundTrips() {
        assertArrayEquals(new int[] {-3, 6113, 0}, Wire.decodeTile(Wire.encodeTile(-3, 6113, 0)));
    }
}
