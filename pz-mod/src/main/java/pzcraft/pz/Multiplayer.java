package pzcraft.pz;

/**
 * Multiplayer is being built in steps (HANDOFF.md, "Multiplayer"; the host runs a PZ server and a Minecraft server; players'
 * guide release/multiplayer-test.md). In a PZ server process (dedicated, or the one PZ's Host button starts) only the host
 * link to the Minecraft server starts ({@link HostLink}); on a multiplayer client Minecraft joins the host's Minecraft server
 * and Steve moves with the others (step M1). Zombies, world edits, items and the character choice come in later steps; the
 * inventory mirror and container screens stay off on a client until then. Single player is unchanged.
 */
final class Multiplayer {
    private Multiplayer() {}

    /** This JVM is a PZ server (dedicated or hosted). Checked when the mod loads and later. */
    static boolean serverProcess() {
        try {
            if (zombie.network.GameServer.server) return true;
        } catch (Throwable t) {
            // PZ classes not there (the development harness)
        }
        return System.getProperty("sun.java.command", "").contains("zombie.network.GameServer");
    }

    /** A server started by PZ's Host button (as opposed to a dedicated one). */
    static boolean hosted() {
        try {
            return zombie.network.GameServer.coop;
        } catch (Throwable t) {
            return false;
        }
    }

    /** This PZ is a client of a multiplayer server. */
    static boolean client() {
        try {
            return zombie.network.GameClient.client;
        } catch (Throwable t) {
            return false;
        }
    }

    /** PzCraft must stay out: a multiplayer game, client or server. */
    static boolean active() {
        return client() || serverProcess();
    }
}
