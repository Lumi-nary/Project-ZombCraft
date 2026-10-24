package pzcraft.mc;

import java.nio.ByteBuffer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.Ring;
import pzcraft.protocol.SharedLink;
import pzcraft.protocol.Wire;

/**
 * The Minecraft server of a multiplayer game (the host runs a PZ server and this). It joins the PZ server on the host's
 * own link ({@link SharedLink#hostPath()}): the PZ server sends what every player's area holds and this server answers
 * with what Minecraft decided. The players' Minecraft clients join this server and keep their own link to their own PZ
 * client (overlay, input, camera, and the PZ world their Minecraft predicts with), as in single player.
 *
 * <p>Step M1: PZ's collision and materials around every player (so the server moves players as their clients do), PZ's
 * clock and weather (Minecraft's time and weather commands go back to the PZ server), the world rules, and players put at
 * their PZ character when they join or respawn ({@link ServerPlacement}). Here {@link Session} is the host link.
 */
public class PzCraftServer implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        try {
            Session.link = SharedLink.open(SharedLink.Role.MC, SharedLink.hostPath());
        } catch (Exception e) {
            PzCraftMain.LOG.error("Could not open the host link to the PZ server; PzCraft is inactive on this server", e);
            return;
        }
        PzCraftMain.LOG.info("Host link open at {} (pid {}): waiting for the PZ server", SharedLink.hostPath(), ProcessHandle.current().pid());
        if (Boolean.getBoolean("pzcraft.audit")) audit();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WorldRules.apply(server);
            checkWorldType(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(TimeBridge::tick);
        WeatherBridge.init();
        Thread t = new Thread(PzCraftServer::linkLoop, "pzcraft-host-link");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A check for a new Minecraft, a new mixin or new code ({@code -Dpzcraft.audit=true}): every mixin target is loaded and
     * our mixins applied to it, and every PzCraft class is loaded and initialised; the ones that need the client's classes
     * are logged (a server must never reach them).
     */
    private static void audit() {
        org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
        PzCraftMain.LOG.info("audit: mixins done (any problem is logged above)");
        int ok = 0;
        java.util.List<String> clientOnly = new java.util.ArrayList<>();
        for (java.nio.file.Path root : net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(PzBlocks.NAMESPACE).orElseThrow().getRootPaths()) {
            java.nio.file.Path dir = root.resolve("pzcraft/mc");
            if (!java.nio.file.Files.isDirectory(dir)) continue;
            try (var files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
                    String file = f.getFileName().toString();
                    if (!file.endsWith(".class")) continue;
                    String name = "pzcraft.mc." + file.substring(0, file.length() - 6);
                    try {
                        Class.forName(name, true, PzCraftServer.class.getClassLoader());
                        ok++;
                    } catch (Throwable t) {
                        clientOnly.add(name.substring(11) + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                    }
                }
            } catch (java.io.IOException e) {
                PzCraftMain.LOG.warn("audit: could not list {}: {}", dir, e.toString());
            }
        }
        java.util.Collections.sort(clientOnly);
        PzCraftMain.LOG.info("audit: {} PzCraft classes load on a server; {} need the client: {}", ok, clientOnly.size(), clientOnly);
    }

    /**
     * PZ is the ground: the world must be empty, as single player's is (a flat world of one layer of air). A normal world's
     * hills and caves would stand in PZ's streets; the host is told how to make the right one.
     */
    private static void checkWorldType(net.minecraft.server.MinecraftServer server) {
        var generator = server.overworld().getChunkSource().getGenerator();
        boolean empty;
        try {
            empty = generator instanceof net.minecraft.world.level.levelgen.FlatLevelSource flat
                    && flat.settings().getLayersInfo().stream().allMatch(l -> l.getBlockState() == null || l.getBlockState().isAir());
        } catch (RuntimeException e) {
            PzCraftMain.LOG.warn("could not check this world's terrain: {}", e.toString());
            return;
        }
        if (!empty) PzCraftMain.LOG.warn("This world is not empty ({}): PZ's world would stand in Minecraft's terrain. Make a new world with, in "
                + "server.properties, level-type=minecraft:flat and generator-settings={\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:plains\"}",
                generator.getClass().getSimpleName());
    }

    /** Heartbeat and message pump of the host link, independent of the server's tick. */
    private static void linkLoop() {
        SharedLink l = Session.link;
        boolean wasAlive = false;
        long lastBeat = 0;
        java.util.Set<Integer> unknown = new java.util.HashSet<>();
        while (true) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastBeat >= 100) {
                    l.heartbeat();
                    lastBeat = now;
                    boolean alive = l.peerAlive();
                    if (alive != wasAlive) {
                        PzCraftMain.LOG.info(alive ? "PZ server connected (pid {})" : "PZ server disconnected", l.peerPidOrZero());
                        wasAlive = alive;
                        // a PZ server that (re)connects sends its world again from nothing
                        TerrainCoverage.reset();
                        CollisionField.reset();
                        MaterialField.reset();
                        Session.pzConnected = alive;
                    }
                }
                Object[] out;
                while ((out = Session.pollOutbox()) != null) {
                    if (!l.tx().write((Integer) out[0], (byte[]) out[1])) break; // ring full: drop; the PZ server resyncs state anyway
                }
                Ring.Message m;
                while ((m = l.rx().poll()) != null) handle(m, unknown);
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                PzCraftMain.LOG.error("host link loop error", t);
            }
        }
    }

    private static void handle(Ring.Message m, java.util.Set<Integer> unknown) {
        switch (m.type()) {
            case SharedLink.MSG_COLLISION -> CollisionField.put(CollisionSection.decode(m.payload()));
            case Wire.MSG_MATERIALS -> MaterialField.put(pzcraft.protocol.MaterialSection.decode(m.payload()));
            case SharedLink.MSG_TERRAIN_COLUMN -> {
                ByteBuffer b = m.buffer();
                TerrainCoverage.column(b.getInt(), b.getInt(), b.getInt() != 0);
            }
            case SharedLink.MSG_COLLISION_CLEAR -> {
                ByteBuffer b = m.buffer();
                int cx = b.getInt(), cy = b.getInt(), level = b.getInt();
                CollisionField.remove(level, cx, cy);
                MaterialField.remove(level, cx, cy);
            }
            case Wire.MSG_CLOCK_STATE -> TimeBridge.state = pzcraft.protocol.WorldClock.State.decode(m.payload());
            case Wire.MSG_WEATHER_STATE -> WeatherBridge.pz = pzcraft.protocol.Weather.State.decode(m.payload());
            default -> {
                if (unknown.add(m.type())) PzCraftMain.LOG.info("host link: message {} from the PZ server not handled yet", m.type());
            }
        }
    }
}
