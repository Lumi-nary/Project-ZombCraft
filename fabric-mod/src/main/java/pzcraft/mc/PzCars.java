package pzcraft.mc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Cars;

/**
 * PZ's cars (W5), the parts both sides use: the car sides PZ reports (where to get in, which window to hit), where the PZ
 * player sits while in a car, aiming at a car, and the server half: while PZ says the PZ player sits in a car, Steve rides
 * a {@link PzSeat} that follows that seat every tick; when PZ says he got out, Steve is let go (PZ then puts him where it
 * left the PZ player); when Steve gets off himself (sneak), PZ is told to get out.
 */
public final class PzCars {
    public static volatile List<Cars.Side> sides = List.of();
    public static volatile long sidesAt;
    public static volatile Cars.Seat seat = Cars.Seat.NONE;
    private static volatile int rides, letGo;
    private static volatile boolean exitRequested;
    private static volatile PzSeat currentRide;

    private PzCars() {}

    /** Main entrypoint (both sides). */
    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(PzCars::tick);
    }

    public static void reset() { sides = List.of(); seat = Cars.Seat.NONE; exitRequested = false; currentRide = null; }

    /** Server thread: wait for PZ's exit acknowledgement instead of mounting the rider again on its old seat report. */
    static void requestExit() {
        if (exitRequested) return;
        exitRequested = true;
        Session.send(Cars.MSG_EXIT, new byte[0]);
    }

    /** The car whose sides lie nearest to {@code point} (within 2 blocks of one): a click anywhere on its body. -1 for none. */
    public static int vehicleNear(Vec3 point) {
        List<Cars.Side> list = sides;
        if (list.isEmpty() || System.nanoTime() - sidesAt > 3_000_000_000L) return -1;
        int best = -1;
        double bestD = 4.0;   // squared: 2 blocks
        for (Cars.Side s : list) {
            AABB b = box(s);
            double dx = Math.max(Math.max(b.minX - point.x, 0), point.x - b.maxX), dz = Math.max(Math.max(b.minZ - point.z, 0), point.z - b.maxZ);
            double dy = Math.max(Math.max(b.minY - point.y, 0), point.y - b.maxY);
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) { bestD = d; best = s.vehicle(); }
        }
        return best;
    }

    public static AABB box(Cars.Side s) { return new AABB(s.minX(), s.minY(), s.minZ(), s.maxX(), s.maxY(), s.maxZ()); }

    /** A car side under the crosshair: where the ray meets the car (its hull, from PZ's collision) and how far. */
    public record Aim(Cars.Side side, Vec3 point, net.minecraft.core.Direction face, double distance) {}

    /**
     * The car side whose area the ray's hit on a car hull lies in (Steve stands inside a door's area when he is at the
     * door, so the area box itself cannot be aimed at from there); else a side's area box hit from outside.
     */
    public static Aim aim(Vec3 from, Vec3 dir, double range) {
        List<Cars.Side> list = sides;
        if (list.isEmpty() || !Session.pzConnected || System.nanoTime() - sidesAt > 3_000_000_000L) return null;
        CollisionField.Hit hull = CollisionField.raycast(from, dir, range);
        if (hull != null) {
            Cars.Side best = null;
            double bestD = Double.MAX_VALUE;
            for (Cars.Side s : list) {
                AABB b = box(s).inflate(0.4, 0.2, 0.4);
                if (!b.contains(hull.point())) continue;
                double d = b.getCenter().distanceToSqr(hull.point());
                if (d < bestD) { bestD = d; best = s; }
            }
            if (best != null) return new Aim(best, hull.point(), hull.face(), hull.distance());
        }
        Vec3 to = from.add(dir.scale(range));
        Aim best = null;
        for (Cars.Side s : list) {
            AABB b = box(s);
            if (b.contains(from)) continue;
            var clip = b.clip(from, to);
            if (clip.isEmpty()) continue;
            double d = from.distanceTo(clip.get());
            if (best == null || d < best.distance()) best = new Aim(s, clip.get(), net.minecraft.core.Direction.UP, d);
        }
        return best;
    }

    /** Server thread: carry Steve with the seat PZ reports, or let him go. Single player (the player link's PZ). */
    private static void tick(MinecraftServer server) {
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        ServerPlayer p = players.getFirst();
        Cars.Seat s = seat;
        PzSeat ride = p.getVehicle() instanceof PzSeat ps && ps.isCar() ? ps : null;
        if (!s.inCar()) { exitRequested = false; currentRide = null; }
        else if (currentRide != null && ride != currentRide) requestExit(); // player ticks can dismount after the seat entity tick
        if (s.inCar() && !exitRequested && Session.pzConnected && p.isAlive()) {
            if (ride == null) {
                if (p.isPassenger()) p.stopRiding();
                ride = new PzSeat(PzSeat.TYPE, p.level());
                ride.car();
                ride.setPos(s.x(), s.y() + 0.6, s.z());
                p.level().addFreshEntity(ride);
                if (!p.startRiding(ride, true, true)) { ride.discard(); return; }
                currentRide = ride;
                rides++;
            }
            ride.setPos(s.x(), s.y() + 0.6, s.z()); // keep feet on PZ's storey; the native rider model is lowered for the cabin
            ride.setYRot(s.yawDeg());
        } else if (ride != null) {
            ride.letGo();
            p.stopRiding();
            ride.discard();
            letGo++;
        }
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sides", sides.size());
        m.put("inCar", seat.inCar());
        m.put("rides", rides);
        m.put("letGo", letGo);
        return m;
    }
}
