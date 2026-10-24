package pzcraft.pz;

import java.nio.file.Files;
import java.nio.file.Path;

/** Entry point ZombieBuddy runs when the mod loads. */
public class Main {
    public static void main(String[] args) {
        if (Multiplayer.serverProcess()) {
            // no player link, no Minecraft launch, no control API in a PZ server: the host link to the Minecraft server instead
            Log.info("Project-ZombCraft " + version() + " loaded in a PZ server process (" + (Multiplayer.hosted() ? "hosted" : "dedicated")
                    + ", pid " + ProcessHandle.current().pid() + "): multiplayer, the host link to the Minecraft server");
            HostLink.start();
            return;
        }
        Log.info("Project-ZombCraft " + version() + " loading (pid " + ProcessHandle.current().pid() + ")");
        // B42.21's native pathfinder repeatedly crashed after MC blasts removed PZ floors.
        // Choose PZ's Java backend before any save loads so chunk updates and path requests use the same backend.
        if (!Boolean.getBoolean("pzcraft.nativePathfinding")) {
            PathfindingPolicy.select();
            Log.info("using PZ's Java pathfinder for destructible Minecraft terrain");
        }
        LinkService.start();   // PZ never starts Minecraft itself: the player does (the start screen's Play, or their launcher)
        PzUi.register(ChoiceScreen.INSTANCE, StartScreen.INSTANCE);   // PzCraft's own screens on PZ's menu
        Control.start();
    }

    /**
     * The installed mod's version: modversion in the mod.info beside this jar (42/media/java/PzCraft.jar -> 42/mod.info), which
     * the release packaging sets; "?" when it cannot be read. (The log used to print a fixed "0.13.0" whatever was installed.)
     */
    static String version() {
        try {
            Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (String line : Files.readAllLines(jar.getParent().getParent().getParent().resolve("mod.info")))
                if (line.startsWith("modversion=")) return line.substring("modversion=".length()).trim();
        } catch (Exception e) {
            // no code source, not a file, no mod.info: the version is only for the log
        }
        return "?";
    }
}
