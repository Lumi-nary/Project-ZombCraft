package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CollisionTest {
    private static final double H = Coords.BLOCKS_PER_LEVEL;

    @Test
    void sectionEncodeDecodeRoundTrip() {
        CollisionSection s = new CollisionSection(-3, 1250, 2);
        s.flags[0] = CollisionSection.FLOOR;
        s.flags[9] = CollisionSection.FLOOR | CollisionSection.EDGE_N | CollisionSection.EDGE_W;
        s.flags[63] = CollisionSection.RAMP | CollisionSection.EDGE_W;
        short[] ramp = new short[16];
        for (int i = 0; i < 16; i++) ramp[i] = (short) (i * 16);
        s.rampHeights[63] = ramp;

        CollisionSection d = CollisionSection.decode(s.encode());
        assertEquals(-3, d.cx);
        assertEquals(1250, d.cy);
        assertEquals(2, d.level);
        assertArrayEquals(s.flags, d.flags);
        assertArrayEquals(ramp, d.rampHeights[63]);
        assertNull(d.rampHeights[0]);
        assertEquals(s.contentHash(), d.contentHash());
        assertEquals(s.key64(), d.key64());
    }

    @Test
    void emptySectionHasNoBoxes() {
        CollisionSection s = new CollisionSection(0, 0, 0);
        assertTrue(s.isEmpty());
        assertEquals(0, CollisionBoxes.build(s).length);
    }

    @Test
    void floorTileBoxSitsUnderTheWalkingSurface() {
        CollisionSection s = new CollisionSection(2, 3, 1); // tiles x 16..23, y 24..31, level 1
        s.flags[10] = CollisionSection.FLOOR;               // local (2,1) -> tile (18, 25)
        double[] b = CollisionBoxes.build(s);
        assertEquals(6, b.length);
        assertEquals(18, b[0], 0);
        assertEquals(H - CollisionBoxes.FLOOR_THICKNESS, b[1], 1e-9);
        assertEquals(25, b[2], 0);
        assertEquals(19, b[3], 0);
        assertEquals(H, b[4], 1e-9);          // walking surface is exactly one level up
        assertEquals(26, b[5], 0);
    }

    @Test
    void floorOverAStairwellStopsShortOfItAndKeepsItsSidesThroughTheWire() {
        CollisionSection s = new CollisionSection(0, 0, 1);
        s.flags[9] = (byte) (CollisionSection.FLOOR | CollisionSection.STAIRWELL); // tile (1,1): stairs start north and west of it
        s.heights[9] = (byte) (CollisionSection.TRIM_N | CollisionSection.TRIM_W);
        s.flags[10] = CollisionSection.FLOOR;                               // tile (2,1): a whole slab
        double[] b = CollisionBoxes.build(CollisionSection.decode(s.encode()));
        assertEquals(12, b.length);
        assertEquals(1 + CollisionBoxes.STAIRWELL_GAP, b[0], 1e-9);  // west side trimmed
        assertEquals(1 + CollisionBoxes.STAIRWELL_GAP, b[2], 1e-9);  // north side trimmed
        assertEquals(2, b[3], 0);                                     // east and south sides whole
        assertEquals(2, b[5], 0);
        assertEquals(H, b[4], 1e-9);
        assertEquals(2, b[6], 0);                                     // the neighbour is untouched
        assertEquals(3, b[9], 0);
        assertTrue(CollisionBoxes.STAIRWELL_GAP < 0.6, "Steve must not fit through the gap");
    }

    @Test
    void wallOverAStairwellEdgeStartsAboveTheFirstSteps() {
        CollisionSection s = new CollisionSection(0, 0, 1);
        s.flags[0] = (byte) (CollisionSection.FLOOR | CollisionSection.EDGE_N | CollisionSection.EDGE_W | CollisionSection.STAIRWELL);
        s.heights[0] = (byte) CollisionSection.LIFT_N;                      // stairs below the north edge, not the west one
        double[] b = CollisionBoxes.build(CollisionSection.decode(s.encode()));
        assertEquals(18, b.length);                                         // floor, north wall, west wall
        assertEquals(H, b[4], 1e-9);                                        // the floor is whole (no TRIM bits)
        assertEquals(H + CollisionBoxes.STAIRWELL_HEADROOM, b[7], 1e-9);    // north wall lifted
        assertEquals(2 * H, b[10], 1e-9);
        assertEquals(H, b[13], 1e-9);                                       // west wall from the floor
        assertTrue(CollisionBoxes.STAIRWELL_HEADROOM < 1.8, "nobody walks under it upstairs");
    }

    @Test
    void wallEdgesAreThinFullHeightPlanesOnTheTileBorder() {
        CollisionSection s = new CollisionSection(0, 0, 0);
        s.flags[0] = CollisionSection.EDGE_N | CollisionSection.EDGE_W; // tile (0,0)
        double[] b = CollisionBoxes.build(s);
        assertEquals(12, b.length);
        // north edge: spans the tile in x, thin around z=0
        assertEquals(-CollisionBoxes.EDGE_HALF, b[2], 1e-9);
        assertEquals(CollisionBoxes.EDGE_HALF, b[5], 1e-9);
        assertEquals(H, b[4], 1e-9);
        // west edge: thin around x=0, spans the tile in z
        assertEquals(-CollisionBoxes.EDGE_HALF, b[6], 1e-9);
        assertEquals(CollisionBoxes.EDGE_HALF, b[9], 1e-9);
        assertEquals(1, b[11], 0);
    }

    @Test
    void lowFencesAreJumpableAndFurnitureIsOnlyAsTallAsItsTop() {
        CollisionSection s = new CollisionSection(0, 0, 0);
        s.flags[0] = (byte) (CollisionSection.EDGE_N | CollisionSection.EDGE_N_LOW);   // a low fence on tile (0,0)'s north edge
        s.flags[1] = CollisionSection.SOLID;                                           // a counter on tile (1,0)
        s.heights[1] = (byte) 40;                                                      // 40/128 of a storey ~ 0.77 blocks
        s.flags[2] = CollisionSection.SOLID;                                           // a fridge: full storey
        CollisionSection d = CollisionSection.decode(s.encode());
        assertArrayEquals(s.heights, d.heights);
        assertEquals(s.contentHash(), d.contentHash());

        double[] b = CollisionBoxes.build(d);
        assertEquals(18, b.length);
        assertEquals(CollisionBoxes.LOW_EDGE, b[4], 1e-9);              // fence top
        assertTrue(CollisionBoxes.LOW_EDGE < 1.25, "Minecraft's jump must clear a low fence");
        assertEquals(H * 40 / CollisionSection.HEIGHT_STEPS, b[6 + 4], 1e-9); // counter top
        assertTrue(b[6 + 4] < 1.25, "Steve must be able to jump onto a counter");
        assertEquals(H, b[12 + 4], 1e-9);                                // fridge
    }

    @Test
    void storeyAboveLeavesRoomToJumpIndoors() {
        // Steve is 1.8 tall; with the next storey's floor slab above him he must still be able to hop a little
        double ceiling = H - CollisionBoxes.FLOOR_THICKNESS;
        assertTrue(ceiling - 1.8 > 0.5, "headroom " + (ceiling - 1.8));
    }

    @Test
    void rampBuildsSixteenColumnsFollowingTheHeightSamples() {
        CollisionSection s = new CollisionSection(0, 0, 0);
        s.flags[0] = CollisionSection.RAMP;
        short[] ramp = new short[16];
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) ramp[y * 4 + x] = (short) (21 * (y + 1)); // rises with y, ~PZ stair slope (1 level / 3 tiles)
        s.rampHeights[0] = ramp;
        double[] b = CollisionBoxes.build(s);
        assertEquals(16 * 6, b.length);
        double lastTop = 0;
        for (int row = 0; row < 4; row++) {
            double top = b[(row * 4) * 6 + 4];
            assertEquals(H * 21.0 * (row + 1) / 256.0, top, 1e-9);
            assertTrue(top > lastTop);
            // each step up stays under Minecraft's 0.6 step-up so the player can walk it
            assertTrue(top - lastTop <= 0.6 + 1e-9, "rise " + (top - lastTop));
            lastTop = top;
        }
    }

    @Test
    void inputStateSlotRoundTrip() throws Exception {
        Path p = Files.createTempFile("pzcraft-input", ".shm");
        try (SharedLink pz = SharedLink.open(SharedLink.Role.PZ, p); SharedLink mc = SharedLink.open(SharedLink.Role.MC, p)) {
            InputState out = new InputState();
            assertFalse(mc.readInputState(out));
            InputState in = new InputState();
            in.frame = 99; in.yawDeg = 180; in.pitchDeg = -45; in.buttons = InputState.FWD | InputState.SPRINT; in.hotbar = 4; in.wheel = -3; in.active = true;
            in.hotbarSeq = 7; in.attackPresses = 11; in.usePresses = 3; in.dropPresses = 2; in.jumpPresses = 5;
            pz.writeInputState(in);
            assertTrue(mc.readInputState(out));
            assertEquals(99, out.frame);
            assertEquals(180f, out.yawDeg, 0);
            assertTrue(out.has(InputState.FWD));
            assertFalse(out.has(InputState.BACK));
            assertEquals(4, out.hotbar);
            assertEquals(7, out.hotbarSeq);
            assertEquals(11, out.attackPresses);
            assertEquals(3, out.usePresses);
            assertEquals(2, out.dropPresses);
            assertEquals(5, out.jumpPresses);
            assertEquals(-3, out.wheel);
            assertTrue(out.active);
            in.frame++; in.active = false; in.buttons = InputState.PAUSED;
            pz.writeInputState(in);
            assertTrue(mc.readInputState(out));
            assertTrue(out.has(InputState.PAUSED));
            assertFalse(out.active);
        }
    }

    @Test
    void frameLinkDeliversWholeFramesInOrder() throws Exception {
        Path p = Files.createTempFile("pzcraft-frame", ".shm");
        try (FrameLink w = FrameLink.open(p); FrameLink r = FrameLink.open(p)) {
            assertEquals(0, r.frameId());
            FrameLink.Meta meta = new FrameLink.Meta();
            for (int n = 1; n <= 5; n++) {
                long seq = w.reserve();
                assertEquals(n, seq);
                for (int layer = 0; layer < 2; layer++) {
                    java.nio.ByteBuffer dst = w.beginLayer(seq, layer, 64, 32);
                    assertEquals(64 * 32 * 4, dst.remaining());
                    for (int i = 0; i < dst.remaining(); i++) dst.put(i, (byte) (n * 10 + layer));
                }
                w.publish(seq, 64, 32, FrameLink.FLAG_WORLD, 10f * n, -5f, 70f, 2f, 1234L + n);
                assertEquals(n, r.frameId());
                r.readMeta(meta);
                assertEquals(64, meta.width);
                assertEquals(32, meta.height);
                assertTrue(meta.hasWorld());
                assertEquals(10f * n, meta.yawDeg, 0);
                assertEquals(-5f, meta.pitchDeg, 0);
                assertEquals(70f, meta.fovDeg, 0);
                assertEquals(2f, meta.aspect, 0);
                assertEquals(1234L + n, meta.renderNanos);
                for (int layer = 0; layer < 2; layer++) {
                    java.nio.ByteBuffer src = r.frontLayer(layer, 64, 32);
                    assertEquals(64 * 32 * 4, src.remaining());
                    assertEquals((byte) (n * 10 + layer), src.get(0));
                    assertEquals((byte) (n * 10 + layer), src.get(src.remaining() - 1));
                }
            }
        }
    }

    @Test
    void warpIsIdentityForTheSameCameraAndShiftsInTheTurnDirection() {
        float[] c = new float[12];
        assertTrue(Warp.corners(30f, 10f, 70f, 1.7f, 30f, 10f, 70f, 1.7f, c));
        float[][] corner = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            assertEquals(corner[i][0], c[i * 3] / c[i * 3 + 2], 1e-5);
            assertEquals(corner[i][1], c[i * 3 + 1] / c[i * 3 + 2], 1e-5);
        }
        // The camera has turned right (yaw grows) since the picture was rendered: the picture content that now belongs
        // at the screen's left edge sits further right in the old picture, so every s/q grows.
        assertTrue(Warp.corners(30f, 0f, 70f, 1.7f, 33f, 0f, 70f, 1.7f, c));
        for (int i = 0; i < 4; i++) assertTrue(c[i * 3] / c[i * 3 + 2] > corner[i][0] + 0.01f, "corner " + i);
        // Looking further down since the render: the new top edge is the old picture's higher-up content, t/q shrinks.
        assertTrue(Warp.corners(30f, 0f, 70f, 1.7f, 30f, 3f, 70f, 1.7f, c));
        for (int i = 0; i < 4; i++) assertTrue(c[i * 3 + 1] / c[i * 3 + 2] > corner[i][1] + 0.01f || c[i * 3 + 1] / c[i * 3 + 2] < corner[i][1] - 0.01f);
        // A big jump (a teleport, a snap) is not warped; the picture shows for its own direction.
        assertFalse(Warp.corners(0f, 0f, 70f, 1.7f, 90f, 0f, 70f, 1.7f, c));
        for (int i = 0; i < 4; i++) assertEquals(corner[i][0], c[i * 3] / c[i * 3 + 2], 1e-5);
        // A picture rendered with a wider field of view (overscan) shows its centre: the corners sit inside [0,1].
        assertTrue(Warp.corners(10f, 0f, 80f, 1.7f, 10f, 0f, 70f, 1.7f, c));
        for (int i = 0; i < 4; i++) {
            float s = c[i * 3] / c[i * 3 + 2];
            assertTrue(s > 0.02f && s < 0.98f && Math.abs(s - corner[i][0]) > 0.02f, "corner " + i + " s=" + s);
        }
        // Yaw wraps around 360.
        assertTrue(Warp.corners(359f, 0f, 70f, 1.7f, 1f, 0f, 70f, 1.7f, c));
    }

    @Test
    void wireMessagesRoundTrip() {
        java.util.List<Wire.Actor> in = java.util.List.of(
                new Wire.Actor(7, 10998.5, 0.0, 9700.25, 90f, 0.75f, 0),
                new Wire.Actor(8, -1, 2.449, 3, -45f, 1f, Wire.Actor.FLAG_CRAWLING));
        java.util.List<Wire.Actor> out = Wire.decodeActors(Wire.encodeActors(in));
        assertEquals(in, out);
        assertEquals(0, Wire.decodeActors(Wire.encodeActors(java.util.List.of())).size());

        Wire.ZombieAttack za = new Wire.ZombieAttack(42, 11000.5, 1.25, 9700.75);
        assertEquals(za, Wire.decodeZombieAttack(Wire.encodeZombieAttack(za)));
        assertArrayEquals(new float[] {14.5f, 20f}, Wire.decodeFloats(Wire.encodeFloats(14.5f, 20f)));

        Wire.Hit h = new Wire.Hit(7, 6.5f, 0.5f, -0.25f);
        assertEquals(h, Wire.decodeHit(Wire.encodeHit(h)));
        Wire.Vitals v = new Wire.Vitals(0.4f, 0.9f);
        assertEquals(v, Wire.decodeVitals(Wire.encodeVitals(v)));
        assertEquals(3.5f, Wire.decodeHurt(Wire.encodeHurt(3.5f)));
        assertEquals(13.25f, Wire.decodeTime(Wire.encodeTime(13.25f)));
    }
}
