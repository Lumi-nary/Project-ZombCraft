package pzcraft.pz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.InputState;
import pzcraft.protocol.PlayerState;
import pzcraft.protocol.SharedLink;

/**
 * Stand-in for PZ during development, so the Minecraft half can be tested against a real Minecraft process without
 * launching PZ.
 *
 * <pre>
 *   java -cp PzCraft.jar pzcraft.pz.DevHarness watch [seconds]
 *   java -cp PzCraft.jar pzcraft.pz.DevHarness walk
 * </pre>
 *
 * {@code walk} builds a 16x16-tile floor with a wall along the north edge of tile row 6, places Steve in it and drives
 * him with synthetic input; it exits non-zero if physics, walls, strafing or jumping misbehave.
 */
public final class DevHarness {
    private static int failures;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "watch";
        LinkService.startHarness();
        if (mode.equals("walk")) {
            walk();
        } else {
            int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 60;
            long end = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < end) {
                System.out.println(String.format("[harness] connected=%b ready=%b rtt=%.2fms %s",
                        LinkService.connected(), LinkService.minecraftReady(), LinkService.rttMs(), LinkService.pullState()));
                Thread.sleep(2000);
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void walk() throws Exception {
        waitFor("Minecraft connected", 300, LinkService::connected);
        waitFor("Steve spawned and asking for placement", 1200, LinkService::takePlacementRequest);

        // 16x16 floor at level 0 (sections (0..1, 0..1)), plus a wall on the north edge of tile row 6 (the plane z = 6).
        for (int cy = 0; cy < 2; cy++) {
            for (int cx = 0; cx < 2; cx++) {
                CollisionSection s = new CollisionSection(cx, cy, 0);
                for (int i = 0; i < CollisionSection.TILES; i++) s.flags[i] = CollisionSection.FLOOR;
                if (cy == 0) for (int tx = 0; tx < 8; tx++) s.flags[6 * 8 + tx] |= CollisionSection.EDGE_N;
                check("send collision section", LinkService.send(SharedLink.MSG_COLLISION, s.encode()));
            }
        }
        // Place Steve in the middle of the south part, facing north (MC yaw 180 = -z).
        PlayerState p = place(8.5, 0.0, 12.5, 180f);
        check("Steve starts on the floor (y=" + p.y + ")", Math.abs(p.y) < 0.05 && p.onGround());
        System.out.println("[harness] start: " + p);

        // 1. Walk forward into the wall. Walking speed ~4.3 b/s, so 3 s would cover ~13 blocks of the 6.5 available.
        drive(InputState.FWD, 180f, 3000);
        p = awaitState(50);
        System.out.println("[harness] after forward: " + p);
        // wall plane z=6, half thickness 0.1, player half-width 0.3 -> rests at z ~= 6.4
        check("stopped by the wall (z=" + p.z + ")", p.z > 6.3 && p.z < 6.55);
        check("did not drift sideways (x=" + p.x + ")", Math.abs(p.x - 8.5) < 0.05);
        check("still on the floor (y=" + p.y + ")", Math.abs(p.y) < 0.05);

        // 2. Strafe right while facing north: that is +x.
        double x0 = p.x;
        drive(InputState.RIGHT, 180f, 800);
        p = awaitState(50);
        check("strafe right moves +x (dx=" + (p.x - x0) + ")", p.x - x0 > 2.0);
        check("strafing keeps z against the wall (z=" + p.z + ")", p.z > 6.3 && p.z < 6.55);

        // 3. Back off south, then jump: apex must be sane, and he lands on the floor again.
        drive(InputState.BACK, 180f, 600);
        double maxY = 0;
        long end = System.currentTimeMillis() + 1500;
        long frame = 1000;
        while (System.currentTimeMillis() < end) {
            frame = pushInput(frame, InputState.JUMP, 180f, 0f);
            p = LinkService.pullState();
            if (p != null) maxY = Math.max(maxY, p.y);
            Thread.sleep(5);
        }
        check("jump apex plausible (max y=" + maxY + ")", maxY > 0.9 && maxY < 1.6);
        drive(0, 180f, 1200);
        p = awaitState(50);
        check("landed back on the floor (y=" + p.y + ")", Math.abs(p.y) < 0.05 && p.onGround());

        // 4. Walk off the +x edge of the 16-tile floor: nothing under him past x=16, so he must fall.
        place(15.5, 0.0, 12.5, 270f); // yaw 270 = +x
        drive(InputState.FWD, 270f, 1500);
        p = awaitState(50);
        check("walked off the edge and is falling (y=" + p.y + ")", p.y < -0.5);

        // leave Steve standing on the floor rather than falling into the void
        place(8.5, 0.0, 12.5, 180f);
        System.out.println(failures == 0 ? "[harness] ALL PASSED" : "[harness] " + failures + " FAILED");
    }

    // ---------------------------------------------------------------------------------------------------------

    private static long inputFrame = 1;

    private static long pushInput(long frame, int buttons, float yaw, float pitch) {
        InputState in = new InputState();
        in.frame = inputFrame++;
        in.yawDeg = yaw;
        in.pitchDeg = pitch;
        in.buttons = buttons;
        in.active = true;
        LinkService.sendInput(in);
        return frame + 1;
    }

    /** Holds {@code buttons} for {@code ms}, refreshing the input slot like a render loop would. */
    private static void drive(int buttons, float yaw, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        long f = 0;
        while (System.currentTimeMillis() < end) {
            f = pushInput(f, buttons, yaw, 0f);
            LinkService.pullState();
            Thread.sleep(5);
        }
        pushInput(f, 0, yaw, 0f);
        Thread.sleep(150);
    }

    /**
     * Places Steve and confirms he is really there. A Steve that is still mid-fall or respawning from an earlier
     * scenario can swallow a teleport, so retry until the pose matches (and re-answer any placement request).
     */
    private static PlayerState place(double x, double y, double z, float yaw) throws InterruptedException {
        for (int attempt = 1; attempt <= 6; attempt++) {
            LinkService.takePlacementRequest();
            sendTeleport(x, y, z, yaw, 0f);
            sleep(1200);
            if (LinkService.takePlacementRequest()) {
                System.out.println("[harness] Minecraft asked for placement again (respawn); retrying");
                continue;
            }
            PlayerState p = awaitState(200);
            if (Math.abs(p.x - x) < 1 && Math.abs(p.y - y) < 1 && Math.abs(p.z - z) < 1 && LinkService.minecraftReady()) {
                sleep(500); // let him settle: gravity was off while he was held
                return awaitState(200);
            }
            System.out.println("[harness] placement attempt " + attempt + " not confirmed: " + p);
        }
        throw new IllegalStateException("could not place Steve at " + x + "," + y + "," + z);
    }

    private static void sendTeleport(double x, double y, double z, float yaw, float pitch) {
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(x).putDouble(y).putDouble(z).putFloat(yaw).putFloat(pitch);
        check("send teleport", LinkService.send(SharedLink.MSG_TELEPORT, b.array()));
    }

    private static PlayerState awaitState(int tries) throws InterruptedException {
        for (int i = 0; i < tries; i++) {
            PlayerState p = LinkService.pullState();
            if (p != null) {
                PlayerState copy = new PlayerState();
                copy.set(p);
                return copy;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("no PlayerState arrived");
    }

    private static void waitFor(String what, int tries, java.util.function.BooleanSupplier cond) throws InterruptedException {
        System.out.println("[harness] waiting for: " + what);
        for (int i = 0; i < tries; i++) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("timed out waiting for: " + what);
    }

    private static void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }

    private static void check(String what, boolean ok) {
        System.out.println("[harness] " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
