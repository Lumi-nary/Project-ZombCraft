package pzcraft.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Numbers that are easier to find by looking than by thinking (where a hand sits on a gun): read from
 * {@code ~/.pzcraft/tuning.json} (flat keys) and re-read twice a second, so a value can be changed while the game runs.
 * A missing file or key means the built-in default; the defaults are what ships.
 */
final class Tuning {
    private static final Path FILE = Path.of(System.getProperty("user.home"), ".pzcraft", "tuning.json");
    private static JsonObject data = new JsonObject();
    private static long checkedAt, stamp = -1;

    private Tuning() {}

    static double get(String key, double fallback) {
        refresh();
        var e = data.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : fallback;
    }

    private static synchronized void refresh() {
        long now = System.nanoTime();
        if (now - checkedAt < 500_000_000L) return;
        checkedAt = now;
        try {
            if (!Files.isRegularFile(FILE)) { data = new JsonObject(); stamp = -1; return; }
            long s = Files.getLastModifiedTime(FILE).toMillis() ^ Files.size(FILE);
            if (s == stamp) return;
            stamp = s;
            data = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
        } catch (IOException | RuntimeException e) { /* keep the last good values */ }
    }
}

