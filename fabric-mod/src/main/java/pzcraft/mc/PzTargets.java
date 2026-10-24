package pzcraft.mc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Coords;
import pzcraft.protocol.WorldTargets;

/**
 * The PZ things near Steve that Minecraft can act on ({@link WorldTargets}), as PZ last reported them. Written by the
 * link thread, read by the client (aiming, mining) and the integrated server (checking a mining request). A target Steve
 * has just mined is hidden until PZ has answered, so the crosshair moves on at once.
 */
public final class PzTargets {
    public static volatile List<WorldTargets.Target> targets = List.of();
    public static volatile long receivedAt;
    /** Target -> System.nanoTime() until which it stays hidden. */
    private static final Map<Long, Long> hidden = new ConcurrentHashMap<>();
    private static final long HIDE_NANOS = 2_000_000_000L;

    private PzTargets() {}

    public static void receive(byte[] payload) {
        targets = WorldTargets.decode(payload);
        receivedAt = System.nanoTime();
    }

    public static void reset() { targets = List.of(); hidden.clear(); }

    /** Fresh enough to act on: PZ sends every 200 ms while something changes and every second regardless. */
    public static boolean fresh() { return Session.pzConnected && System.nanoTime() - receivedAt < 3_000_000_000L; }

    private static long id(WorldTargets.Target t) {
        return ((long) t.x() * 73856093L) ^ ((long) t.y() * 19349663L) ^ ((long) t.level() * 83492791L) ^ ((long) t.kind() << 56) ^ t.key();
    }

    public static void hide(WorldTargets.Target t) { hidden.put(id(t), System.nanoTime() + HIDE_NANOS); }
    public static void unhide(WorldTargets.Target t) { hidden.remove(id(t)); }

    private static boolean isHidden(WorldTargets.Target t) {
        Long until = hidden.get(id(t));
        if (until == null) return false;
        if (System.nanoTime() < until) return true;
        hidden.remove(id(t));
        return false;
    }

    /** Minecraft y of a PZ level's floor (the same as the collision floors). */
    public static double floorY(int level) { return level * Coords.BLOCKS_PER_LEVEL; }

    public static AABB box(WorldTargets.Target t) {
        double y = floorY(t.level());
        return new AABB(t.x() + t.x0(), y + t.y0(), t.y() + t.z0(), t.x() + t.x1(), y + t.y1(), t.y() + t.z1());
    }

    /** The block voxel the target stands in (where a block placed "on" grass goes, and where its drops pop out). */
    public static BlockPos voxel(WorldTargets.Target t) {
        return BlockPos.containing(t.x() + .5, floorY(t.level()) + .01, t.y() + .5);
    }

    public static Vec3 center(WorldTargets.Target t) { return box(t).getCenter(); }

    /**
     * The same PZ thing in the current snapshot, if PZ still reports it. Hidden targets count: in single player the
     * client hides what it just mined in this same store before the integrated server checks the request.
     */
    public static WorldTargets.Target find(int kind, int x, int y, int level, int key) {
        for (WorldTargets.Target t : targets) {
            if (t.kind() == kind && t.x() == x && t.y() == y && t.level() == level && t.key() == key) return t;
        }
        return null;
    }

    public record Hit(WorldTargets.Target target, Vec3 point, Direction face, double distance) {}

    /** The nearest visible target along the ray within {@code max}, or null. A ray starting inside a box does not hit it. */
    public static Hit raycast(Vec3 from, Vec3 dir, double max) {
        if (!fresh()) return null;
        Vec3 to = from.add(dir.scale(max));
        Hit best = null;
        for (WorldTargets.Target t : targets) {
            AABB box = box(t);
            if (box.contains(from)) continue;
            var clip = box.clip(from, to);
            if (clip.isEmpty()) continue;
            double d = from.distanceTo(clip.get());
            if ((best == null || d < best.distance()) && !isHidden(t)) best = new Hit(t, clip.get(), face(box, clip.get()), d);
        }
        return best;
    }

    private static Direction face(AABB b, Vec3 p) {
        double e = 1e-5;
        if (Math.abs(p.y - b.maxY) < e) return Direction.UP;
        if (Math.abs(p.y - b.minY) < e) return Direction.DOWN;
        if (Math.abs(p.x - b.minX) < e) return Direction.WEST;
        if (Math.abs(p.x - b.maxX) < e) return Direction.EAST;
        if (Math.abs(p.z - b.minZ) < e) return Direction.NORTH;
        return Direction.SOUTH;
    }

    public static Map<String, Object> stats() {
        return Map.of("targets", targets.size(), "hidden", hidden.size(),
                "ageMs", receivedAt == 0 ? -1 : (System.nanoTime() - receivedAt) / 1_000_000);
    }
}
