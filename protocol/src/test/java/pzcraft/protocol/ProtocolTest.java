package pzcraft.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    @Test
    void coordsRoundTrip() {
        assertEquals(10.5, Coords.pzX(Coords.mcX(10.5)), 1e-9);
        assertEquals(-3.25, Coords.pzY(Coords.mcZ(-3.25)), 1e-9);
        assertEquals(2.0, Coords.pzZ(Coords.mcY(2.0)), 1e-9);
        assertEquals(Math.sqrt(6.0) * (1.0 - Coords.Z_EPS), Coords.mcY(1.0), 1e-9);
        assertEquals(0.5f, Coords.mcYawDegToPzRad(Coords.pzRadToMcYawDeg(0.5f)), 1e-5f);
        assertEquals(1.0, Math.floor(Coords.pzZ(Coords.mcY(1.0))), 0, "standing on level 1 is still level 1");
        assertEquals(1.0, Math.floor(Coords.pzZ(Math.sqrt(6.0))), 0, "float noise at the floor stays on that level");
    }

    @Test
    void yawAndPitchConventionsAgreeWithViewpoint() {
        // MC south (+z, yaw 0) must be PZ south (+y): Viewpoint forward (cos, sin) = (0, 1) -> yaw = +90 deg.
        assertEquals(Math.PI / 2, Coords.mcYawDegToPzRad(0f), 1e-6);
        // MC east (+x) is yaw -90 -> PZ yaw 0 (east).
        assertEquals(0.0, Coords.mcYawDegToPzRad(-90f), 1e-6);
        // MC north (-z) is yaw 180 -> PZ yaw 270 deg = -90 deg: forward (0, -1) = north in PZ.
        assertEquals(-Math.PI / 2, Coords.mcYawDegToPzRad(180f), 1e-6);
        // MC west (-x) is yaw 90 -> PZ yaw 180 deg.
        assertEquals(Math.PI, Math.abs(Coords.mcYawDegToPzRad(90f)), 1e-6);
        for (float yaw : new float[] {-170f, -90f, 0f, 45f, 90f, 179f}) {
            assertEquals(yaw, Coords.pzRadToMcYawDeg(Coords.mcYawDegToPzRad(yaw)), 1e-3f);
        }
        // looking up in Viewpoint (positive pitch) is negative pitch in MC
        assertEquals(-30f, Coords.pzPitchRadToMcDeg((float) Math.toRadians(30)), 1e-4f);
        assertEquals(0.5f, Coords.mcPitchDegToPzRad(Coords.pzPitchRadToMcDeg(0.5f)), 1e-6f);
    }

    @Test
    void ringKeepsOrderAcrossManyWraps() throws Exception {
        Path p = Files.createTempFile("pzcraft-ring", ".shm");
        try (SharedLink a = SharedLink.open(SharedLink.Role.PZ, p); SharedLink b = SharedLink.open(SharedLink.Role.MC, p)) {
            int sent = 0, got = 0;
            // 3000 messages of varied size force the 256 KiB ring to wrap many times.
            while (got < 3000) {
                while (sent < 3000) {
                    byte[] payload = new byte[(sent * 37) % 5000 + 1];
                    java.util.Arrays.fill(payload, (byte) sent);
                    if (!a.tx().write(7, payload)) break;
                    sent++;
                    if (sent % 50 == 0) break;
                }
                Ring.Message m;
                while ((m = b.rx().poll()) != null) {
                    assertEquals(7, m.type());
                    assertEquals((got * 37) % 5000 + 1, m.payload().length);
                    assertEquals((byte) got, m.payload()[0]);
                    got++;
                }
            }
            assertEquals(3000, got);
        }
    }

    @Test
    void playerStateSeqlockAndHeartbeat() throws Exception {
        Path p = Files.createTempFile("pzcraft-state", ".shm");
        try (SharedLink pz = SharedLink.open(SharedLink.Role.PZ, p); SharedLink mc = SharedLink.open(SharedLink.Role.MC, p)) {
            PlayerState out = new PlayerState();
            assertFalse(pz.readPlayerState(out), "nothing written yet");

            PlayerState s = new PlayerState();
            s.tick = 42; s.x = 1.5; s.y = 64; s.z = -7.25; s.yawDeg = 90; s.pitchDeg = -10; s.flags = PlayerState.FLAG_ON_GROUND;
            s.eyeHeight = 1.62f;
            s.cameraMode = PlayerState.CAMERA_FRONT; s.cameraValid = true;
            s.cameraX = -1234.75; s.cameraY = 65.62; s.cameraZ = 9876.5;
            s.cameraYawDeg = 270; s.cameraPitchDeg = 10;
            mc.writePlayerState(s);
            assertTrue(pz.readPlayerState(out));
            assertEquals(42, out.tick);
            assertEquals(-7.25, out.z, 0);
            assertTrue(out.onGround());
            assertEquals(1.62f, out.eyeHeight, 0, "PZ's camera sits at Steve's own eye height");
            assertTrue(out.cameraValid);
            assertEquals(PlayerState.CAMERA_FRONT, out.cameraMode);
            assertEquals(s.cameraX, out.cameraX, 0); assertEquals(s.cameraY, out.cameraY, 0);
            assertEquals(s.cameraZ, out.cameraZ, 0); assertEquals(s.cameraYawDeg, out.cameraYawDeg, 0);
            assertEquals(s.cameraPitchDeg, out.cameraPitchDeg, 0);
            PlayerState copy = new PlayerState(); copy.set(out);
            assertEquals(s.cameraX, copy.cameraX, 0); assertEquals(s.cameraMode, copy.cameraMode);
            s.cameraValid = false; s.cameraMode = PlayerState.CAMERA_FIRST;
            mc.writePlayerState(s);
            assertTrue(pz.readPlayerState(out)); assertFalse(out.cameraValid);

            assertTrue(pz.peerAlive());
            assertTrue(mc.peerAlive());
        }
    }

    /** Minecraft closes itself when PZ's process has ended, but only waits when PZ merely stopped beating. */
    @Test
    void aPeerThatQuitIsToldFromOneThatStalled() throws Exception {
        Path p = Files.createTempFile("pzcraft-quit", ".shm");
        try (SharedLink mc = SharedLink.open(SharedLink.Role.MC, p)) {
            assertFalse(mc.peerProcessAlive(), "PZ never joined");
            assertFalse(mc.peerAlive());
            try (SharedLink pz = SharedLink.open(SharedLink.Role.PZ, p)) {
                assertTrue(mc.peerProcessAlive());
                assertTrue(mc.peerAlive());
                Thread.sleep(SharedLink.HEARTBEAT_TIMEOUT_MS + 200);
                assertTrue(mc.peerProcessAlive(), "stalled: the process is still there");
                assertFalse(mc.peerAlive());
            }
            // PZ's pid and heartbeat live at 8 and 24 (see SharedLink): a process that has ended, beating a moment ago.
            Process ended = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            ended.waitFor();
            try (java.io.RandomAccessFile raw = new java.io.RandomAccessFile(p.toFile(), "rw")) {
                ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                raw.seek(8); raw.write(b.putLong(0, ended.pid()).array());
                raw.seek(24); raw.write(b.putLong(0, System.currentTimeMillis()).array());
            }
            assertFalse(mc.peerProcessAlive(), "quit: no process, whatever the heartbeat says");
            assertFalse(mc.peerAlive());
        }
    }

    @Test
    void pingPongRoundTrip() throws Exception {
        Path p = Files.createTempFile("pzcraft-ping", ".shm");
        try (SharedLink pz = SharedLink.open(SharedLink.Role.PZ, p); SharedLink mc = SharedLink.open(SharedLink.Role.MC, p)) {
            assertTrue(pz.sendPing());
            Ring.Message m = mc.rx().poll();
            assertNotNull(m);
            assertEquals(SharedLink.MSG_PING, m.type());
            assertTrue(mc.tx().write(SharedLink.MSG_PONG, m.payload()));
            Ring.Message r = pz.rx().poll();
            assertNotNull(r);
            assertEquals(SharedLink.MSG_PONG, r.type());
            long sent = ByteBuffer.wrap(r.payload()).order(ByteOrder.LITTLE_ENDIAN).getLong();
            assertTrue(System.nanoTime() - sent >= 0);
        }
    }
}
