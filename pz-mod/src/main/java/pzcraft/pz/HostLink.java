package pzcraft.pz;

import pzcraft.protocol.Ring;
import pzcraft.protocol.SharedLink;
import pzcraft.protocol.Wire;

/**
 * Multiplayer, in the PZ server process: the host pair's link to the Minecraft server on the same PC
 * ({@link SharedLink#hostPath()}). The PZ server tells the Minecraft server what every player's area holds and applies what
 * Minecraft decided. Step M1: collision and materials around every player ({@link WorldExporter#hostTick}), PZ's clock and
 * weather (Minecraft's time and weather commands come back and are carried out here, for everyone).
 */
final class HostLink {
    private static final int MAX_FAILURES = 10;
    private static volatile SharedLink link;
    private static volatile boolean connected;
    /** Bumped whenever the Minecraft server (re)connects: it has nothing of what was sent before. */
    private static volatile int epoch;
    /** The tx ring is single-producer: the game thread and the link thread take turns under this lock. */
    private static final Object TX = new Object();
    private static int failures;

    static final McLink LINK = new McLink() {
        @Override public boolean send(int type, byte[] payload) {
            SharedLink l = link;
            if (l == null) return false;
            synchronized (TX) { return l.tx().write(type, payload); }
        }
        @Override public boolean connected() { return connected; }
        @Override public int epoch() { return epoch; }
    };

    private HostLink() {}

    static void start() {
        try {
            link = SharedLink.open(SharedLink.Role.PZ, SharedLink.hostPath());
        } catch (Exception e) {
            Log.error("could not open the host link to the Minecraft server", e);
            return;
        }
        WorldExporter.startHost(LINK);
        Log.info("host link open at " + SharedLink.hostPath() + ": waiting for the Minecraft server (Minecraft port "
                + (int) ServerSettings.minecraftPort() + ")");
        Thread t = new Thread(HostLink::run, "pzcraft-host-link");
        t.setDaemon(true);
        t.start();
    }

    static boolean connected() { return connected; }

    /** The PZ server's game thread, every tick (PzCraft_Server.lua, OnTick). Never lets an exception into PZ's loop. */
    static void tick() {
        if (link == null || failures >= MAX_FAILURES) return;
        try {
            WorldExporter.hostTick(zombie.network.GameServer.getPlayers());
            ClockBridge.serverTick(LINK);
            WeatherBridge.serverTick(LINK);
        } catch (Throwable t) {
            if (++failures >= MAX_FAILURES) Log.error("host link tick failed " + failures + " times; it stops (no more collision for the Minecraft server)", t);
            else Log.error("host link tick failed", t);
        }
    }

    private static void run() {
        SharedLink l = link;
        long lastBeat = 0;
        java.util.Set<Integer> unknown = new java.util.HashSet<>();
        while (true) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastBeat >= 100) {
                    l.heartbeat();
                    lastBeat = now;
                    boolean alive = l.peerAlive();
                    if (alive != connected) {
                        Log.info(alive ? "Minecraft server connected (pid " + l.peerPidOrZero() + ")" : "Minecraft server disconnected");
                        if (alive) epoch++;
                        connected = alive;
                    }
                }
                Ring.Message m;
                while ((m = l.rx().poll()) != null) {
                    switch (m.type()) {
                        case Wire.MSG_CLOCK_CHANGE -> ClockBridge.receive(pzcraft.protocol.WorldClock.Change.decode(m.payload()));
                        case Wire.MSG_WEATHER_COMMAND -> WeatherBridge.receive(pzcraft.protocol.Weather.Command.decode(m.payload()));
                        case Wire.MSG_CLOCK_CONTROL -> { }      // PZ's server keeps its own day length
                        default -> {
                            if (unknown.add(m.type())) Log.info("host link: message " + m.type() + " from the Minecraft server not handled yet");
                        }
                    }
                }
                Thread.sleep(5);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.error("host link loop error", t);
            }
        }
    }
}
