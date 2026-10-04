package pzcraft.pz;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Starts the hidden Minecraft process for you when PZ starts, so only PZ has to be launched. The command comes from
 * {@code <Zomboid cache>/Lua/pzcraft.properties} (written by {@code gradlew installMod}); with no file, or with
 * {@code autolaunch=false}, nothing is started and you run Minecraft yourself. It never starts a second copy: it only
 * launches if no Minecraft has connected to the link a few seconds after PZ came up.
 */
final class McLauncher {
    private McLauncher() {}

    /** A previous launch may still be loading (it takes 20-40 s): look for our Fabric dev client or its Gradle run. */
    private static boolean minecraftProcessExists() {
        long me = ProcessHandle.current().pid();
        return ProcessHandle.allProcesses().anyMatch(h -> {
            if (h.pid() == me) return false;
            String c = h.info().commandLine().orElse("").toLowerCase();
            return (c.contains("devlaunchinjector") || c.contains("knotclient") || c.contains("pzcraft-direct.args")
                    || (c.contains("gradle") && c.contains("runclient")))
                    && c.contains("fabric-mod");
        });
    }

    static void start() {
        Thread t = new Thread(McLauncher::run, "pzcraft-launcher");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            Thread.sleep(6000);
            if (LinkService.connected()) return; // already running (started by hand, or still up from before)
            Path cfg = Paths.get(zombie.ZomboidFileSystem.instance.getCacheDir(), "Lua", "pzcraft.properties");
            if (!Files.isRegularFile(cfg)) {
                Log.info("no " + cfg + "; start Minecraft yourself (scripts\run-minecraft.bat)");
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(cfg)) { p.load(in); }
            if ("false".equalsIgnoreCase(p.getProperty("autolaunch", "true"))) return;
            String cmd = p.getProperty("minecraft.launch", "").trim();
            if (cmd.isEmpty() || !Files.isRegularFile(Paths.get(cmd))) {
                Log.info("minecraft.launch is not set or missing (" + cmd + ")");
                return;
            }
            if (LinkService.connected()) return;
            if (minecraftProcessExists()) {
                Log.info("a Minecraft process is already starting; not launching another");
                return;
            }
            Log.info("starting Minecraft: " + cmd);
            new ProcessBuilder("cmd.exe", "/c", "start", "\"PzCraft Minecraft\"", "/min", cmd).start();
        } catch (InterruptedException e) {
            // shutting down
        } catch (IOException | RuntimeException e) {
            Log.error("could not start Minecraft", e);
        }
    }
}

