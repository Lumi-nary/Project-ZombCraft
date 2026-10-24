package pzcraft.pz;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.zed_0xff.zombie_buddy.Exposer.LuaClass;

/**
 * A multiplayer client's side of PzCraft_Server.lua (Lua: {@code PzCraft.Multiplayer.*}, from PzCraft_Multiplayer.lua):
 * which Minecraft server to join, who plays as Steve (their PZ body is hidden: Minecraft draws their Steve), and whether
 * this player plays as Steve (told to the server: "steve", "falls"). Public: ZombieBuddy exposes it to Lua.
 */
@LuaClass(name = "PzCraft.Multiplayer")
public final class MultiplayerLua {
    private static volatile String minecraftServer = "";
    private static final Map<String, String> steves = new ConcurrentHashMap<>();

    private MultiplayerLua() {}

    /** The server's answer to "hello": the Minecraft server's port, and an address if players must use another name. */
    public static void server(double port, String address) {
        String host = address == null || address.isBlank() ? zombie.network.GameClient.ip : address.trim();
        String joined = host == null || host.isBlank() ? "" : host + ":" + (int) port;
        if (!joined.equals(minecraftServer)) Log.info("multiplayer: this server's Minecraft server is " + joined);
        minecraftServer = joined;
    }

    /** Another player started or stopped playing as Steve (with their Minecraft name). */
    public static void steve(String who, boolean on, String minecraftName) {
        if (who == null) return;
        if (on) steves.put(who, minecraftName == null ? "" : minecraftName); else steves.remove(who);
    }

    /** "host:port" of the Minecraft server to join, "" while unknown or in single player. */
    static String minecraftServer() { return Multiplayer.client() ? minecraftServer : ""; }

    static boolean playsSteve(String username) { return username != null && steves.containsKey(username); }

    /** This player plays as Steve now: Minecraft moves them and counts their falls. */
    public static boolean drivesSteve() { return SteveControl.drives(zombie.characters.IsoPlayer.getInstance()); }

    /** This player's name in Minecraft, "" while unknown. */
    public static String minecraftName() {
        pzcraft.protocol.McStatus s = LinkService.minecraftStatus();
        return s == null || s.user() == null ? "" : s.user();
    }
}
