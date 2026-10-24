package pzcraft.pz;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.joml.Vector3f;
import pzcraft.protocol.Cars;
import pzcraft.protocol.Coords;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleWindow;

/**
 * PZ's cars for Steve (W5). Steve right-clicks a car's side: the PZ player gets into that seat the way PZ's
 * ISEnterVehicle ends (no animation: PZ's body is hidden while Steve drives). From then on PZ leads: PZ's controls,
 * physics, keys, fuel and engine drive the car, which carries the PZ player; {@link PuppetDriver} leaves him to the car,
 * and Minecraft is told where he sits so Steve rides along. Sneaking in Minecraft gets him out as ISExitVehicle does, and
 * Steve is put where PZ left him. A window Steve hits loses condition by PZ's own window rules (alarm, smash, glass, noise).
 * Game thread, except {@link #receive}.
 */
final class CarBridge {
    private record Request(int type, byte[] payload) {}
    private static final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private static final double RANGE = 10;
    private static long lastSides, lastSeat, lastDashboard;
    private static float dashboard;
    private static boolean sentSides, wasInCar;
    private static volatile int entered, exited, refused, hits, failures;
    private static volatile String last = "";
    private static volatile Cars.Seat renderSeat = Cars.Seat.NONE;

    static Cars.Seat renderSeat() { return renderSeat; }

    private CarBridge() {}

    /** Link thread. */
    static void receive(int type, byte[] payload) {
        if (requests.size() < 32) requests.add(new Request(type, payload));
    }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null || !LinkService.connected()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        Request r;
        while ((r = requests.poll()) != null) {
            try {
                switch (r.type()) {
                    case Cars.MSG_ENTER -> enter(cell, player, Cars.Enter.decode(r.payload()));
                    case Cars.MSG_EXIT -> exit(player);
                    case Cars.MSG_HIT -> hit(cell, player, Cars.Hit.decode(r.payload()));
                    case Cars.MSG_MENU -> {
                        BaseVehicle v = find(cell, java.nio.ByteBuffer.wrap(r.payload()).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
                        if (v != null && player.getVehicle() == null) PzMenus.requestVehicle(v);
                    }
                    default -> { }
                }
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 3) Log.error("car request " + r.type() + " failed", e);
            }
        }
        long now = System.currentTimeMillis();
        BaseVehicle in = player.getVehicle();
        renderSeat = in == null ? Cars.Seat.NONE : new Cars.Seat(true, in.getId(), in.getSeat(player),
                Coords.mcX(player.getX()), Coords.mcY(player.getZ()), Coords.mcZ(player.getY()), yaw(in), dashboard);
        if (in != null && now - lastSeat >= 50) {
            lastSeat = now;
            wasInCar = true;
            if (now - lastDashboard >= 500) {
                lastDashboard = now;
                Object f = PzMenus.callVehicles("dashboardFraction");
                dashboard = f instanceof Double d ? (float) Math.max(0, Math.min(.5, d)) : 0;
            }
            LinkService.send(Cars.MSG_SEAT, new Cars.Seat(true, in.getId(), in.getSeat(player), Coords.mcX(player.getX()),
                    Coords.mcY(player.getZ()), Coords.mcZ(player.getY()), yaw(in), dashboard).encode());
        } else if (in == null && wasInCar) {
            if (LinkService.send(Cars.MSG_SEAT, Cars.Seat.NONE.encode())) {
                wasInCar = false;
                PuppetDriver.requestReplace(); // Steve is put where PZ left the PZ player, beside the car
            }
        }
        if (now - lastSides >= 200) {
            lastSides = now;
            List<Cars.Side> sides = sides(cell, player);
            if (!sides.isEmpty() || sentSides) sentSides = LinkService.send(Cars.MSG_SIDES, Cars.encodeSides(sides)) && !sides.isEmpty();
        }
    }

    /** The car's heading as a Minecraft yaw: from its local forward axis (the one PZ's areas run along). */
    private static float yaw(BaseVehicle v) {
        Vector3f o = v.getWorldPos(0, 0, 0, new Vector3f()), f = v.getWorldPos(0, 0, 1, new Vector3f());
        double dx = f.x - o.x, dz = f.y - o.y;   // PZ's world y is Minecraft's z
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ---- what Minecraft can click on a car ----

    private static List<Cars.Side> sides(IsoCell cell, IsoPlayer player) {
        List<Cars.Side> out = new ArrayList<>();
        for (BaseVehicle v : cell.getVehicles()) {
            if (v == null || v.getScript() == null || Math.hypot(v.getX() - player.getX(), v.getY() - player.getY()) > RANGE) continue;
            Map<String, int[]> areas = new LinkedHashMap<>(); // area -> {seat, window part}
            for (int seat = 0; seat < v.getMaxPassengers(); seat++) {
                VehiclePart door = v.getPassengerDoor(seat);
                VehicleScript.Passenger p = v.getScript().getPassenger(seat);
                String area = door != null && door.getArea() != null ? door.getArea() : p != null ? p.area : null;
                if (area != null) areas.computeIfAbsent(area, a -> new int[] {-1, -1})[0] = seat;
            }
            for (int i = 0; i < v.getPartCount(); i++) {
                VehiclePart part = v.getPartByIndex(i);
                if (part != null && part.getWindow() != null && part.getArea() != null) areas.computeIfAbsent(part.getArea(), a -> new int[] {-1, -1})[1] = i;
                // the hood, the trunk: no seat or window, but Shift+right-click on them opens PZ's car menu
                else if (part != null && part.getDoor() != null && part.getArea() != null) areas.computeIfAbsent(part.getArea(), a -> new int[] {-1, -1});
            }
            double bottom = Coords.mcY(v.getZ()), top = bottom + Math.max(.5, v.getScript().getExtents().y);
            for (var e : areas.entrySet()) {
                VehicleScript.Area area = v.getScript().getAreaById(e.getKey());
                if (area == null) continue;
                int seat = e.getValue()[0], windowPart = e.getValue()[1], flags = 0;
                if (seat >= 0) {
                    VehiclePart door = v.getPassengerDoor(seat);
                    if (door != null && door.getDoor() != null && door.getDoor().isLocked()) flags |= Cars.Side.LOCKED;
                    if (v.isSeatOccupied(seat)) flags |= Cars.Side.OCCUPIED;
                }
                if (windowPart >= 0) {
                    VehicleWindow w = v.getPartByIndex(windowPart).getWindow();
                    if (w != null && w.isHittable()) flags |= Cars.Side.WINDOW_INTACT;
                }
                float[] box = areaBox(v, area);
                out.add(new Cars.Side(v.getId(), seat, windowPart, flags, box[0], (float) bottom, box[1], box[2], (float) top, box[3]));
                if (out.size() >= Cars.MAX_SIDES) return out;
            }
        }
        return out;
    }

    /** The area's rectangle (local to the car, turned with it) as a box in Minecraft x/z: {minX, minZ, maxX, maxZ}. */
    private static float[] areaBox(BaseVehicle v, VehicleScript.Area a) {
        float minX = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        Vector3f w = new Vector3f();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                v.getWorldPos(a.x + sx * a.w / 2, 0, a.y + sz * a.h / 2, w);
                minX = Math.min(minX, w.x); maxX = Math.max(maxX, w.x);
                minZ = Math.min(minZ, w.y); maxZ = Math.max(maxZ, w.y);
            }
        }
        return new float[] {minX, minZ, maxX, maxZ};
    }

    private static BaseVehicle find(IsoCell cell, int id) {
        for (BaseVehicle v : cell.getVehicles()) if (v != null && v.getId() == id) return v;
        return null;
    }

    // ---- getting in and out, hitting windows ----

    private static void refuse(String why) {
        refused++;
        last = why;
        LinkService.send(Cars.MSG_REFUSED, why.getBytes(StandardCharsets.UTF_8));
    }

    /** ISEnterVehicle's start and perform, without the animation; a locked door opens with the car's key or a smashed window. */
    private static void enter(IsoCell cell, IsoPlayer player, Cars.Enter req) {
        BaseVehicle v = find(cell, req.vehicle());
        int seat = req.seat();
        if (v == null || player.getVehicle() != null || seat < 0 || seat >= v.getMaxPassengers()) return;
        if (Math.hypot(v.getX() - player.getX(), v.getY() - player.getY()) > 6) return;
        if (v.isSeatOccupied(seat)) { refuse("That seat is taken"); return; }
        VehiclePart door = v.getPassengerDoor(seat);
        if (door != null && door.getDoor() != null && door.getDoor().isLocked()) {
            VehiclePart window = door.getChildWindow();
            boolean key = player.getInventory().haveThisKeyId(v.getKeyId()) != null;
            boolean smashed = window != null && window.getWindow() != null && window.getWindow().isDestroyed();
            if (key) door.getDoor().setLocked(false);
            else if (!smashed) { refuse("The door is locked"); return; }
        }
        if (!v.enter(seat, player)) { refuse("PZ would not let you in"); return; }
        v.playPassengerSound(seat, "enter");
        v.setCharacterPosition(player, seat, "inside");
        v.playPassengerAnim(seat, "idle");
        LuaEventManager.triggerEvent("OnEnterVehicle", player);
        entered++;
        last = "entered " + v.getScript().getName() + " seat " + seat;
        Log.info("car: " + last);
    }

    /** ISExitVehicle's perform. */
    private static void exit(IsoPlayer player) {
        BaseVehicle v = player.getVehicle();
        if (v == null) return;
        int seat = v.getSeat(player);
        v.exit(player);
        v.setCharacterPosition(player, seat, "outside");
        // PZ's narrow body can leave at a point still inside Minecraft's car hull once Steve's 0.6-wide
        // body is considered. Give that native exit point clearance on the same side of the car.
        double dx = player.getX() - v.getX(), dy = player.getY() - v.getY();
        double distance = Math.hypot(dx, dy);
        if (distance > .01) {
            float x = (float) (player.getX() + .6 * dx / distance);
            float y = (float) (player.getY() + .6 * dy / distance);
            var cell = IsoWorld.instance.getCell();
            var from = cell.getGridSquare((int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) player.getZ());
            var to = cell.getGridSquare((int) Math.floor(x), (int) Math.floor(y), (int) player.getZ());
            if (to != null && !to.isSolid() && !to.isSolidTrans() && (from == null || from == to || !from.isSomethingTo(to))) {
                player.setX(x);
                player.setY(y);
            }
        }
        player.setForceX(player.getX());
        player.setForceY(player.getY());
        var square = player.findCurrentGridSquare();
        if (square != null) { player.setCurrent(square); player.setMovingSquare(square); }
        PuppetDriver.requestReplace(); // protect the outside position from the old, seated Minecraft pose immediately
        LuaEventManager.triggerEvent("OnExitVehicle", player);
        v.updateHasExtendOffsetForExitEnd(player);
        exited++;
        last = "left " + v.getScript().getName() + " seat " + seat;
        Log.info("car: " + last);
    }

    private static void hit(IsoCell cell, IsoPlayer player, Cars.Hit req) {
        BaseVehicle v = find(cell, req.vehicle());
        if (v == null || req.part() < 0 || req.part() >= v.getPartCount() || Math.hypot(v.getX() - player.getX(), v.getY() - player.getY()) > 6) return;
        VehicleWindow w = v.getPartByIndex(req.part()).getWindow();
        if (w == null || !w.isHittable()) return;
        w.damage(Math.max(1, Math.min(100, req.amount())));
        hits++;
        last = "hit window " + req.part() + " of " + v.getScript().getName() + ": " + w.getHealth() + " left";
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entered", entered);
        m.put("exited", exited);
        m.put("refused", refused);
        m.put("windowHits", hits);
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}
