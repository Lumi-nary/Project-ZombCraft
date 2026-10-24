package pzcraft.pz;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import me.zed_0xff.zombie_buddy.Exposer.LuaClass;

/**
 * A PZ server's PzCraft settings, in the server's {@code Zomboid/Lua/pzcraft-server.properties}: {@code minecraft.port}
 * (the host's Minecraft server, 25565 if not set) and {@code minecraft.address} (only when players must reach it under
 * another name than the PZ server's; otherwise each player uses the address they joined PZ with). Lua:
 * {@code PzCraft.Server.*} (PzCraft_Server.lua), which also drives the host link's work on PZ's game thread
 * ({@link #tick}). Public: ZombieBuddy exposes it to Lua.
 */
@LuaClass(name = "PzCraft.Server")
public final class ServerSettings {
    private ServerSettings() {}

    private static Properties read() {
        Properties p = new Properties();
        try {
            Path file = Path.of(zombie.ZomboidFileSystem.instance.getCacheDir(), "Lua", "pzcraft-server.properties");
            if (Files.isRegularFile(file)) try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        } catch (Exception e) {
            Log.info("could not read pzcraft-server.properties: " + e);
        }
        return p;
    }

    public static double minecraftPort() {
        try {
            return Integer.parseInt(read().getProperty("minecraft.port", "25565").trim());
        } catch (NumberFormatException e) {
            return 25565;
        }
    }

    public static String minecraftAddress() { return read().getProperty("minecraft.address", "").trim(); }

    /** The PZ server's game thread, every tick (OnTick): what the Minecraft server is sent (HostLink). */
    public static void tick() { HostLink.tick(); }

    /** The Minecraft server is up and joined on the host link. */
    public static boolean minecraftConnected() { return HostLink.connected(); }
}
