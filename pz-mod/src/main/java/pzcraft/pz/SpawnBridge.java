package pzcraft.pz;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Spawns;
import zombie.characters.IsoPlayer;
import zombie.ui.ISUIWrapper.LuaHelpers;

/**
 * Minecraft's creative spawners (Spawns): a PZ car of the chosen script, repaired with a full tank and its key
 * (PzCraft_Vehicles.lua), and a PZ zombie from the Zomboid spawn egg. Only near Steve. Game thread, except {@link #receive}.
 */
final class SpawnBridge {
    private record Request(int type, byte[] payload) {}
    private static final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private static final double RANGE = 12;
    private static volatile int vehicles, zombies, refused, failures;
    private static volatile String last = "";

    private SpawnBridge() {}

    /** Link thread. */
    static void receive(int type, byte[] payload) {
        if (requests.size() < 16) requests.add(new Request(type, payload));
    }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        Request r;
        while ((r = requests.poll()) != null) {
            if (player == null || !LinkService.connected()) continue;
            try {
                if (r.type() == Spawns.MSG_SPAWN_VEHICLE) vehicle(player, Spawns.Vehicle.decode(r.payload()));
                else zombie(player, Spawns.Zombie.decode(r.payload()));
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 3) Log.error("spawn request failed", e);
            }
        }
    }

    private static boolean near(IsoPlayer player, double x, double y, int level) {
        if (Math.hypot(x - player.getX(), y - player.getY()) <= RANGE && Math.abs(level - player.getZ()) <= 2) return true;
        refused++;
        last = "too far from Steve";
        return false;
    }

    private static void vehicle(IsoPlayer player, Spawns.Vehicle v) {
        if (!near(player, v.x() + .5, v.y() + .5, v.level())) return;
        // the car faces where Steve looks (Minecraft's yaw: 0 south, 90 west)
        float yaw = ((v.yawDeg() % 360) + 360) % 360;
        String dir = yaw < 45 || yaw >= 315 ? "S" : yaw < 135 ? "W" : yaw < 225 ? "N" : "E";
        Object made = LuaHelpers.callLuaClass("PzCraftVehicles", "spawn", null, v.script(), (double) v.x(), (double) v.y(), (double) v.level(), dir);
        last = "vehicle " + v.script() + ": " + made;
        if (made instanceof String s && s.equals(scriptName(v.script()))) vehicles++; else refused++;
        Log.info("spawn: " + last);
    }

    private static String scriptName(String full) {
        int dot = full.indexOf('.');
        return dot >= 0 ? full.substring(dot + 1) : full;
    }

    private static void zombie(IsoPlayer player, Spawns.Zombie z) {
        if (!near(player, z.x(), z.y(), z.level())) return;
        zombie.Lua.LuaManager.GlobalObject.addZombiesInOutfit((int) Math.floor(z.x()), (int) Math.floor(z.y()), z.level(), 1, null, 50);
        zombies++;
        last = String.format("zombie at %.1f,%.1f,%d", z.x(), z.y(), z.level());
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vehicles", vehicles);
        m.put("zombies", zombies);
        m.put("refused", refused);
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}
