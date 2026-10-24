package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Identity-only gun requests. PZ owns ammo, damage, wear and the outcome. {@code actorId} is the nearest native actor the ray
 * found (or -1) and {@code targets} the other distinct actors the same shot reached (shotgun pellets, bullets that pierce), in
 * order of distance; PZ rolls and applies each of them itself, up to the weapon's own maximum hit count.
 *
 * <p>For {@link #RELOAD} and {@link #INSPECT} the same list carries timing hints from the gun model's clips instead (see
 * {@link #timing}): when in the clip PZ's own sounds belong, and how long the clip's reload phase is. They only place sounds and
 * set how long the gun is busy; PZ still decides whether and what is reloaded. For a gun that is loaded round by round the two
 * variants carry, as their phases, how long the clip that opens the action is and how long one round's clip is. For
 * {@link #EJECT_MAGAZINE} of a gun with a magazine the first variant is the clip that takes it out: its length and, as its first
 * cue, when the magazine leaves.
 */
public record GunAction(long sequence, int action, int itemId, int actorId, int impactKind,
                        double x, double y, double z, String weaponType, List<Integer> targets) {
    public static final int FIRE = 0, RELOAD = 1, CANCEL = 2, LOAD_MAGAZINE = 3, UNLOAD_MAGAZINE = 4, LOAD_ONE_ROUND = 5,
            /** Shift+R with the pistol: the loaded magazine leaves the gun and returns to the inventory with the rounds it holds. */
            EJECT_MAGAZINE = 6,
            /** I: look the gun over. Plays the inspect clip and its sounds; any shot, reload or swap ends it. */
            INSPECT = 7,
            /** B: cycle a select-fire gun between single shots and full auto. */
            FIRE_MODE = 8,
            /** X: work the action by hand (native rack: the spent round goes out, the next one comes in from the magazine). */
            RACK = 9;
    public static final int MISS = 0, PZ_SURFACE = 1, MC_BLOCK = 2;
    public static final int MAX_TARGETS = 32;
    /** Sound kinds of a timing hint: the magazine leaving, the magazine going in, the action being worked, the gun settling. */
    public static final int CUE_EJECT = 0, CUE_INSERT = 1, CUE_RACK = 2, CUE_REST = 3;

    /** One clip's timing: how long its reload phase is (0 for a clip without one) and when which sound belongs, in seconds. */
    public record Timing(double phase, double[][] cues) {}

    /**
     * Timing hints as the integer list of a request: for each of the two variants (gun not empty, gun empty) the cue count, the
     * phase in milliseconds, then time (ms) and kind per cue. A variant the model has no clip for is sent with a count of -1.
     */
    public static List<Integer> timing(Timing normal, Timing empty) {
        List<Integer> out = new ArrayList<>();
        for (Timing t : new Timing[] {normal, empty}) {
            if (t == null) { out.add(-1); out.add(0); continue; }
            int n = Math.min(t.cues().length, 6);
            out.add(n); out.add((int) Math.round(t.phase() * 1000));
            for (int i = 0; i < n; i++) { out.add((int) Math.round(t.cues()[i][0] * 1000)); out.add((int) t.cues()[i][1]); }
        }
        return out;
    }

    /** The hint for one variant out of a request's list, or null when it was not sent (or the list is not a timing list). */
    public Timing timing(boolean empty) {
        int at = 0;
        for (int variant = 0; variant < 2; variant++) {
            if (at + 2 > targets.size()) return null;
            int n = targets.get(at), phase = targets.get(at + 1);
            if (n > 6 || at + 2 + 2 * Math.max(0, n) > targets.size()) return null;
            if (variant == (empty ? 1 : 0)) {
                if (n < 0) return null;
                double[][] cues = new double[n][];
                for (int i = 0; i < n; i++) {
                    int ms = targets.get(at + 2 + 2 * i), kind = targets.get(at + 3 + 2 * i);
                    if (ms < 0 || ms > 20_000 || kind < CUE_EJECT || kind > CUE_REST) return null;
                    cues[i] = new double[] {ms / 1000.0, kind};
                }
                return new Timing(phase / 1000.0, cues);
            }
            at += 2 + 2 * Math.max(0, n);
        }
        return null;
    }
    private static final int HEADER = 48;

    public GunAction(long sequence, int action, int itemId, int actorId, int impactKind, double x, double y, double z, String weaponType) {
        this(sequence, action, itemId, actorId, impactKind, x, y, z, weaponType, List.of());
    }

    public byte[] encode() {
        byte[] type = weaponType.getBytes(StandardCharsets.UTF_8);
        validate(type.length);
        var b = ByteBuffer.allocate(HEADER + 1 + 4 * targets.size() + type.length).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(sequence).putInt(action).putInt(itemId).putInt(actorId).putInt(impactKind)
                .putDouble(x).putDouble(y).putDouble(z).put((byte) targets.size());
        for (int t : targets) b.putInt(t);
        return b.put(type).array();
    }

    public static GunAction decode(byte[] bytes) {
        if (bytes.length <= HEADER + 1 || bytes.length > HEADER + 1 + 4 * MAX_TARGETS + 512) throw new IllegalArgumentException("gun request size");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long sequence = b.getLong();
        int action = b.getInt(), itemId = b.getInt(), actorId = b.getInt(), impactKind = b.getInt();
        double x = b.getDouble(), y = b.getDouble(), z = b.getDouble();
        int n = b.get() & 255;
        if (n > MAX_TARGETS || bytes.length < HEADER + 1 + 4 * n + 1) throw new IllegalArgumentException("gun request targets");
        List<Integer> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) targets.add(b.getInt());
        int offset = HEADER + 1 + 4 * n;
        var r = new GunAction(sequence, action, itemId, actorId, impactKind, x, y, z,
                new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8), List.copyOf(targets));
        r.validate(bytes.length - offset);
        return r;
    }

    private void validate(int typeLength) {
        if (sequence <= 0 || action < FIRE || action > RACK || itemId < 0 || impactKind < MISS || impactKind > MC_BLOCK
                || typeLength < 1 || typeLength > 512 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || targets == null || targets.size() > MAX_TARGETS)
            throw new IllegalArgumentException("invalid gun request");
    }
}
