package pzcraft.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The item catalogue of Project Zomboid as exported by PZ (name, category, weight, icon and the food, weapon and clothing
 * numbers), read from {@code ~/.pzcraft/items/items.json}. It lives outside the project: icons come from the user's own
 * PZ install and are never committed.
 */
public final class PzItemCatalog {
    public static final Path DIR = Path.of(System.getProperty("user.home"), ".pzcraft", "items");

    /** One PZ item type. {@code weapon}, {@code food} and {@code clothing} are null for other kinds of item. */
    public record Entry(String id, String name, String category, String type, float weight, String icon, boolean hidden,
                        List<String> tags, JsonObject weapon, JsonObject food, JsonObject clothing, String bodyLocation, String replaceOnUse) {
        public boolean isType(String t) { return type.equalsIgnoreCase(t); }
    }

    private static volatile Map<String, Entry> entries = Map.of();
    private static long loadedStamp = Long.MIN_VALUE;
    private static long checkedAt;

    private PzItemCatalog() {}

    /** The icon's resource key, e.g. {@code Item_Axe} becomes {@code item_axe}. */
    public static String iconKey(String texture) { return texture.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"); }

    public static Entry get(String fullType) {
        refresh();
        return entries.get(fullType);
    }

    public static Collection<String> ids() {
        refresh();
        return entries.keySet();
    }

    public static int size() {
        refresh();
        return entries.size();
    }

    /** Re-reads the file when PZ rewrote it (checked at most once a second). */
    public static synchronized void refresh() {
        long now = System.nanoTime();
        if (now - checkedAt < 1_000_000_000L && loadedStamp != Long.MIN_VALUE) return;
        checkedAt = now;
        Path file = DIR.resolve("items.json");
        try {
            if (!Files.isRegularFile(file)) return;
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
            if (stamp == loadedStamp) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Entry> loaded = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("items")) {
                JsonObject o = element.getAsJsonObject();
                String id = o.get("id").getAsString();
                List<String> tags = new ArrayList<>();
                if (o.has("tags")) for (JsonElement t : o.getAsJsonArray("tags")) tags.add(t.getAsString());
                loaded.put(id, new Entry(id, str(o, "name", id), str(o, "category", ""), str(o, "type", "NORMAL"),
                        o.has("weight") ? o.get("weight").getAsFloat() : 0f, o.has("icon") && !o.get("icon").isJsonNull() ? o.get("icon").getAsString() : null,
                        o.has("hidden") && o.get("hidden").getAsBoolean(), List.copyOf(tags),
                        o.has("weapon") ? o.getAsJsonObject("weapon") : null, o.has("food") ? o.getAsJsonObject("food") : null,
                        o.has("clothing") ? o.getAsJsonObject("clothing") : null,
                        o.has("bodyLocation") && !o.get("bodyLocation").isJsonNull() ? o.get("bodyLocation").getAsString() : null,
                        o.has("replaceOnUse") && !o.get("replaceOnUse").isJsonNull() ? o.get("replaceOnUse").getAsString() : null));
            }
            entries = Map.copyOf(loaded);
            loadedStamp = stamp;
            PzCraftClient.LOG.info("PZ item catalogue: {} items", loaded.size());
        } catch (IOException | RuntimeException e) {
            PzCraftClient.LOG.warn("could not read the PZ item catalogue: {}", e.toString());
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}

