package pzcraft.mc;

import pzcraft.protocol.InputState;
import pzcraft.protocol.SharedLink;

/** State shared between the link thread and the game's main thread. */
public final class Session {
    public record Teleport(double x, double y, double z, float yawDeg, float pitchDeg) {}

    public static volatile SharedLink link;
    /** Minecraft -> PZ video (hand + HUD). */
    public static volatile pzcraft.protocol.FrameLink frames;
    private static final pzcraft.protocol.InputReader inputReader = new pzcraft.protocol.InputReader();
    public static volatile boolean pzConnected;
    public static volatile Teleport pendingTeleport;
    /** Set by the main thread when Steve is placed and released; the link thread turns it into MSG_READY. */
    public static volatile boolean readyToAnnounce;
    /** Set by the main thread when a fresh Steve needs placing; the link thread turns it into MSG_NEED_PLACEMENT. */
    public static volatile boolean needPlacement;
    /** False while Steve is held in place waiting for PZ to place him and its collision to arrive. */
    public static volatile boolean released;
    /** Fixed for this Minecraft frame: PZ acknowledged drawing the block geometry itself. */
    public static boolean nativeWorld;
    public static volatile boolean pzPaused;
    public static volatile boolean steveDrives;
    public static volatile java.util.UUID playerUuid;

    /** Latest PZ actors / vitals, written by the link thread, read on the server thread. */
    public static volatile java.util.List<pzcraft.protocol.Wire.Actor> actors = java.util.List.of();
    public static volatile pzcraft.protocol.Wire.Vitals vitals;
    public static volatile float timeHour = -1f;
    /** Viewpoint's camera: eye height above the feet (blocks), vertical FOV (degrees), window aspect. 0 = unknown. */
    public static volatile float eyeHeight, fovDeg, aspect;

    /** Client thread: sample shared memory at the point of use, including the camera projection fields. */
    public static InputState readInput() {
        InputState in = inputReader.read(link, pzConnected);
        pzPaused = in != null && in.has(InputState.PAUSED);
        steveDrives = in != null && in.has(InputState.STEVE_DRIVES);
        if (in != null) {
            eyeHeight = in.eyeHeight;
            fovDeg = in.fovDeg;
            aspect = in.aspect;
        }
        return in;
    }

    /** The world layer is rendered this much wider than PZ shows (tangent of half the FOV), so PZ can re-project it when its camera has turned. */
    public static final double OVERSCAN = 1.15;

    /** Vertical FOV Minecraft renders the world layer with: Viewpoint's, widened by the overscan margin. */
    public static float renderFov() {
        float f = fovDeg;
        if (f <= 10f || f >= 170f) return f;
        return Math.min(170f, (float) Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(f) / 2) * OVERSCAN)));
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<Object[]> OUTBOX = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Queue a message for PZ from any thread; the link thread owns the ring and drains this. */
    public static void send(int type, byte[] payload) {
        if (OUTBOX.size() < 4096) OUTBOX.add(new Object[] {type, payload});
    }

    static Object[] pollOutbox() { return OUTBOX.poll(); }

    private Session() {}
}

