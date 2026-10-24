package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VertexLightTest {
    @Test void roundTripsEveryLevel() {
        for (int sky = 0; sky <= 15; sky++)
            for (int block = 0; block <= 15; block++)
                for (int e = 0; e <= 15; e++) {
                    float v = VertexLight.pack(e / 15f, block, sky);
                    assertTrue(VertexLight.has(v));
                    assertEquals(sky, VertexLight.sky(v));
                    assertEquals(block, VertexLight.block(v));
                    assertEquals(e / 15f, VertexLight.emission(v), 1e-6);
                }
    }

    @Test void plainEmissionUnchanged() {
        for (float e : new float[] {0f, 7 / 15f, 1f}) {
            assertFalse(VertexLight.has(e));
            assertEquals(e, VertexLight.emission(e));
            assertEquals(0, VertexLight.sky(e));
        }
        assertEquals(0.25f, VertexLight.packCoords(0.25f, -1));
    }

    @Test void minecraftLightCoords() {
        int coords = 15 << 20 | 14 << 4;   // full sky, a torch's block light
        float v = VertexLight.packCoords(1f, coords);
        assertEquals(15, VertexLight.sky(v));
        assertEquals(14, VertexLight.block(v));
        assertEquals(1f, VertexLight.emission(v));
    }

    @Test void outOfRangeIsClamped() {
        float v = VertexLight.pack(2f, 99, -3);
        assertEquals(15, VertexLight.block(v));
        assertEquals(0, VertexLight.sky(v));
        assertEquals(1f, VertexLight.emission(v));
        assertEquals(VertexLight.MARK + 15 * 256 + 15 * 16 + 15, VertexLight.pack(1f, 15, 15));
    }
}
