package pzcraft.mc;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import pzcraft.protocol.McSound;

/**
 * Minecraft's one-shot sounds played in PZ's process (SoundForwardMixin, PZ's SoundBridge): the .ogg of each sound is written
 * once to {@code ~/.pzcraft/sounds} (Minecraft's own sounds sit in its asset store or in mod jars, which PZ cannot read) and
 * PZ is told to play it. When PZ says it cannot (its FMOD refused the files), Minecraft plays its sounds itself again.
 */
public final class McSounds {
    private static final Path DIR = Path.of(System.getProperty("user.home"), ".pzcraft", "sounds");
    private static final Map<Identifier, String> files = new ConcurrentHashMap<>();
    private static volatile boolean failed;
    private static volatile int forwarded, missing;
    private static volatile String why = "";

    private McSounds() {}

    static void init() {}

    /** PZ will play the sounds: linked, Steve in the world, and PZ did not refuse them. */
    public static boolean active() {
        return !failed && Session.pzConnected && Session.released;
    }

    /** Render thread. False when the sound must be played by Minecraft after all (its file could not be written). */
    public static boolean forward(Identifier path, double x, double y, double z, float volume, float pitch, float range, boolean relative) {
        String file = file(path);
        if (file == null) return false;
        Session.send(McSound.MSG_SOUND, new McSound.Play(file, x, y, z, volume, pitch, range, relative ? McSound.FLAG_RELATIVE : 0).encode());
        forwarded++;
        return true;
    }

    private static String file(Identifier path) {
        String known = files.get(path);
        if (known != null) return known.isEmpty() ? null : known;
        String out = "";
        try {
            Path target = DIR.resolve(path.getNamespace()).resolve(path.getPath().replace(':', '_'));
            if (!Files.isRegularFile(target)) {
                var resource = Minecraft.getInstance().getResourceManager().getResource(path);
                if (resource.isPresent()) {
                    Files.createDirectories(target.getParent());
                    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                    try (InputStream in = resource.get().open()) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.isRegularFile(target)) out = target.toAbsolutePath().toString();
        } catch (Exception e) {
            PzCraftMain.LOG.debug("sound {} could not be cached for PZ: {}", path, e.toString());
        }
        if (out.isEmpty()) missing++;
        files.put(path, out);
        return out.isEmpty() ? null : out;
    }

    /** Link thread: PZ cannot play Minecraft's sounds. */
    public static void failed(String reason) {
        failed = true;
        why = reason;
        PzCraftMain.LOG.warn("PZ cannot play Minecraft's sounds ({}): Minecraft plays them itself", reason);
    }

    public static Map<String, Object> stats() {
        return Map.of("forwarded", forwarded, "missing", missing, "failed", failed, "why", why, "cached", files.size());
    }
}
