package pzcraft.pz;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The player's Minecraft instances that have the PzCraft Fabric mod, in the launchers players use, and how to start one.
 * Prism Launcher, MultiMC and PolyMC start an instance from the command line ({@code --launch}); CurseForge, the Modrinth
 * App and Mojang's own launcher do not, so for those PZ only says where the instance is and waits for the player to start
 * it. Minecraft is never started directly (that would go round the player's own account and launcher).
 */
final class McInstances {
    enum Launcher {
        PRISM("Prism Launcher"), MULTIMC("MultiMC"), POLYMC("PolyMC"), CURSEFORGE("CurseForge"), MODRINTH("Modrinth App"),
        OFFICIAL("Minecraft Launcher");
        final String label;
        Launcher(String label) { this.label = label; }
    }

    /** An instance with our mod. {@code exe} is the launcher that can start it, null when it has to be started by hand. */
    record Instance(Launcher launcher, String id, String name, Path dir, Path exe) {
        boolean launchable() { return exe != null; }
        String label() { return name + " (" + launcher.label + ")"; }
    }

    private static final Pattern OUR_JAR = Pattern.compile("(?i)(pzcraft-fabric|project-zombcraft-fabric)-.*\\.jar");

    private McInstances() {}

    /** Every instance found, the ones PZ can start first. Reads folders only; a slow disk costs a little time. */
    static List<Instance> find() {
        List<Instance> out = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        Path appData = env("APPDATA", home.resolve("AppData/Roaming"));
        Path localAppData = env("LOCALAPPDATA", home.resolve("AppData/Local"));
        // Prism: its data folder (installed) or the folder of a portable copy; the exe from the usual install places
        Path prismExe = firstFile(localAppData.resolve("Programs/PrismLauncher/prismlauncher.exe"),
                env("ProgramFiles", Path.of("C:/Program Files")).resolve("PrismLauncher/prismlauncher.exe"),
                home.resolve("scoop/apps/prismlauncher/current/prismlauncher.exe"));
        List<Path> prismData = new ArrayList<>(List.of(appData.resolve("PrismLauncher"), home.resolve(".local/share/PrismLauncher")));
        if (prismExe != null && Files.isDirectory(prismExe.getParent().resolve("instances"))) prismData.add(0, prismExe.getParent());
        for (Path data : prismData) mmcStyle(out, Launcher.PRISM, data, "prismlauncher.cfg", prismExe);
        // MultiMC is portable only: look where people unzip it
        for (Path dir : List.of(home.resolve("MultiMC"), home.resolve("Desktop/MultiMC"), home.resolve("Documents/MultiMC"),
                home.resolve("Downloads/MultiMC"), Path.of("C:/MultiMC"), Path.of("C:/Games/MultiMC"), Path.of("D:/MultiMC"),
                Path.of("D:/Games/MultiMC"))) {
            mmcStyle(out, Launcher.MULTIMC, dir, "multimc.cfg", firstFile(dir.resolve("MultiMC.exe"), dir.resolve("MultiMC")));
        }
        Path polyExe = firstFile(localAppData.resolve("Programs/PolyMC/polymc.exe"));
        mmcStyle(out, Launcher.POLYMC, appData.resolve("PolyMC"), "polymc.cfg", polyExe);
        // started by the player only
        plain(out, Launcher.CURSEFORGE, home.resolve("curseforge/minecraft/Instances"));
        plain(out, Launcher.MODRINTH, appData.resolve("ModrinthApp/profiles"));
        plain(out, Launcher.MODRINTH, appData.resolve("com.modrinth.theseus/profiles"));
        Path official = appData.resolve(".minecraft");
        if (hasOurMod(official.resolve("mods"))) out.add(new Instance(Launcher.OFFICIAL, ".minecraft", "Fabric profile", official, null));
        // the same folder found twice (a portable Prism that is also the data folder) counts once
        Map<Path, Instance> unique = new LinkedHashMap<>();
        for (Instance i : out) unique.putIfAbsent(i.dir().toAbsolutePath().normalize(), i);
        List<Instance> list = new ArrayList<>(unique.values());
        list.sort((a, b) -> Boolean.compare(b.launchable(), a.launchable()));
        return list;
    }

    /** Prism, MultiMC and PolyMC: instances/<id>/instance.cfg (name=...), the game in minecraft/ or .minecraft/. */
    private static void mmcStyle(List<Instance> out, Launcher launcher, Path data, String cfgName, Path exe) {
        Path instances = data.resolve("instances");
        Properties cfg = read(data.resolve(cfgName));
        String custom = cfg.getProperty("InstanceDir", "").trim();
        if (!custom.isEmpty()) instances = data.resolve(custom);       // relative to the data folder, or absolute
        if (!Files.isDirectory(instances)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(instances)) {
            for (Path dir : dirs) {
                if (!Files.isRegularFile(dir.resolve("instance.cfg"))) continue;
                Path mods = Files.isDirectory(dir.resolve("minecraft")) ? dir.resolve("minecraft/mods") : dir.resolve(".minecraft/mods");
                if (!hasOurMod(mods)) continue;
                String name = read(dir.resolve("instance.cfg")).getProperty("name", dir.getFileName().toString());
                out.add(new Instance(launcher, dir.getFileName().toString(), name, dir, exe));
            }
        } catch (IOException e) {
            Log.info("could not list " + instances + ": " + e);
        }
    }

    /** CurseForge and the Modrinth App: one folder per instance, its mods right inside. */
    private static void plain(List<Instance> out, Launcher launcher, Path root) {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) if (hasOurMod(dir.resolve("mods"))) out.add(new Instance(launcher, dir.getFileName().toString(),
                    dir.getFileName().toString(), dir, null));
        } catch (IOException e) {
            Log.info("could not list " + root + ": " + e);
        }
    }

    static boolean hasOurMod(Path mods) {
        if (!Files.isDirectory(mods)) return false;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(mods)) {
            for (Path f : files) if (OUR_JAR.matcher(f.getFileName().toString()).matches()) return true;
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** Starts the instance through its launcher (Prism and PolyMC: --launch, MultiMC: -l). */
    static void launch(Instance i) throws IOException {
        if (!i.launchable()) throw new IOException(i.launcher().label + " cannot be started from PZ");
        String flag = i.launcher() == Launcher.MULTIMC ? "-l" : "--launch";
        new ProcessBuilder(i.exe().toString(), flag, i.id())
                .directory(i.exe().getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Log.info("starting Minecraft: " + i.label() + " via " + i.exe());
    }

    private static Path env(String name, Path fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Path.of(v);
    }

    private static Path firstFile(Path... candidates) {
        for (Path p : candidates) if (Files.isRegularFile(p)) return p;
        return null;
    }

    private static Properties read(Path file) {
        Properties p = new Properties();
        if (!Files.isRegularFile(file)) return p;
        try (InputStream in = Files.newInputStream(file)) {
            // the launchers' cfg files are INI-like "key=value" lines with Windows paths: keep backslashes as they are
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\\", "\\\\");
            p.load(new java.io.StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            Log.info("could not read " + file + ": " + e);
        }
        return p;
    }

    static String describe(List<Instance> found) {
        StringBuilder b = new StringBuilder();
        for (Instance i : found) b.append(i.label()).append(i.launchable() ? "" : " [start by hand]").append("; ");
        return b.length() == 0 ? "none" : b.toString();
    }
}
