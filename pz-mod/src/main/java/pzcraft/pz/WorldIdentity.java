package pzcraft.pz;

import java.nio.charset.StandardCharsets;

/**
 * Tells Minecraft which PZ save is loaded (or loading), about once a second, so it opens that save's own Minecraft world
 * (each PZ save has one: Steve's inventory, placed blocks and the rest stay with the save). Empty on PZ's main menu, which
 * leaves whatever world Minecraft has open as it is.
 */
final class WorldIdentity {
    private static long last;

    private WorldIdentity() {}

    /** Game thread, every frame. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (now - last < 1000) return;
        last = now;
        if (!LinkService.connected()) return;
        LinkService.send(pzcraft.protocol.Wire.MSG_PZ_WORLD, key().getBytes(StandardCharsets.UTF_8));
        // multiplayer: the host's Minecraft server to join ("" in single player: Minecraft keeps to each save's own world)
        LinkService.send(pzcraft.protocol.Wire.MSG_JOIN_SERVER, MultiplayerLua.minecraftServer().getBytes(StandardCharsets.UTF_8));
    }

    /** Minecraft has this save's own world open, so Steve can be placed in it (always true for a Minecraft that does not say). */
    static boolean minecraftHasThisSave() {
        String open = LinkService.minecraftWorld();
        return open == null || open.equals(key());
    }

    /** "<game mode>/<save>" while a save is loading or being played, else "". */
    static String key() {
        Object state = zombie.GameWindow.states == null ? null : zombie.GameWindow.states.current;
        if (!(state instanceof zombie.gameStates.IngameState || state instanceof zombie.gameStates.GameLoadingState)) return "";
        if (Multiplayer.active()) {                         // multiplayer: the host's Minecraft server is the world
            String server = MultiplayerLua.minecraftServer();
            return server.isEmpty() ? "" : "server:" + server;
        }
        String mode = zombie.core.Core.gameMode, save = zombie.core.Core.gameSaveWorld;
        return mode == null || save == null || save.isEmpty() ? "" : mode + "/" + save;
    }
}
