package pzcraft.pz;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;
import zombie.core.Translator;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/**
 * Writes PZ's item catalogue for Minecraft: {@code items.json} (name, category, weight and the food / weapon / clothing
 * numbers) and one square transparent PNG per icon, cropped out of PZ's texture atlas pages on the GPU. Everything goes to
 * {@code ~/.pzcraft/items}, outside the project: the icons come from the user's own PZ install and must never be committed.
 */
final class ItemExporter {
    static final Path DIR = Path.of(System.getProperty("user.home"), ".pzcraft", "items");
    private static volatile boolean running;
    private static volatile String status = "never run";
    private static volatile int itemCount, iconCount, failed;

    private ItemExporter() {}

    private static boolean autoChecked;

    /** Game thread, every frame: once the player exists, export the catalogue if it is missing or from another game version. */
    static void tickAuto(Object player) {
        if (autoChecked || player == null || running) return;
        autoChecked = true;
        try {
            Path manifest = DIR.resolve("items.json");
            String head = "{\"version\":1,\"game\":\"" + zombie.core.Core.getInstance().getVersion() + "\"";
            if (Files.isRegularFile(manifest) && Files.readString(manifest, StandardCharsets.UTF_8).startsWith(head)
                    && Files.isDirectory(DIR.resolve("icons"))) return;
        } catch (IOException ignored) { }
        Log.info("exporting PZ items for Minecraft: " + export());
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("status", status);
        m.put("items", itemCount);
        m.put("icons", iconCount);
        m.put("failedIcons", failed);
        m.put("directory", DIR.toString());
        return m;
    }

    /** Game thread (developer automation / dev command). Blocks for the GPU read-back; a few seconds for the whole catalogue. */
    static String export() {
        if (running) return "item export already running";
        running = true;
        long start = System.nanoTime();
        try {
            Files.createDirectories(DIR.resolve("icons"));
            List<Item> items = new ArrayList<>(ScriptManager.instance.getAllItems());
            Map<String, Texture> textures = new TreeMap<>();
            StringBuilder json = new StringBuilder(1 << 20);
            json.append("{\"version\":1,\"game\":\"").append(zombie.core.Core.getInstance().getVersion()).append("\",\"items\":[");
            boolean first = true;
            for (Item item : items) {
                if (item == null) continue;
                Texture tex = item.getNormalTexture();
                String icon = tex == null ? null : tex.getName();
                if (tex != null && icon != null) textures.putIfAbsent(icon, tex);
                if (!first) json.append(',');
                first = false;
                describe(json, item, icon);
            }
            json.append("]}");
            itemCount = items.size();
            Files.writeString(DIR.resolve("items.json"), json, StandardCharsets.UTF_8);
            exportIcons(textures);
            status = "done in " + Math.round((System.nanoTime() - start) / 1e6) + " ms";
            return status + ": " + itemCount + " items, " + iconCount + " icons (" + failed + " failed)";
        } catch (IOException | RuntimeException e) {
            status = "failed: " + e;
            Log.error("item export failed", e);
            return status;
        } finally {
            running = false;
        }
    }

    // ---- manifest ----

    private static void describe(StringBuilder j, Item item, String icon) {
        String full = item.getFullName();
        j.append("{\"id\":").append(str(full));
        j.append(",\"name\":").append(str(safeName(full, item)));
        j.append(",\"category\":").append(str(item.getDisplayCategory()));
        String type = typeName(item);
        j.append(",\"type\":").append(str(type));
        j.append(",\"weight\":").append(num(item.getActualWeight()));
        j.append(",\"icon\":").append(icon == null ? "null" : str(icon));
        j.append(",\"hidden\":").append(item.isHidden());
        List<String> tags = new ArrayList<>();
        if (item.getTags() != null) item.getTags().forEach(tag -> tags.add(String.valueOf(tag)));
        j.append(",\"tags\":[");
        for (int i = 0; i < tags.size(); i++) j.append(i > 0 ? "," : "").append(str(tags.get(i)));
        j.append(']');
        if (type.equals("weapon")) {
            j.append(",\"weapon\":{\"ranged\":").append(item.isRanged());
            j.append(",\"minDamage\":").append(num(item.getMinDamage()));
            j.append(",\"maxDamage\":").append(num(item.getMaxDamage()));
            j.append(",\"minRange\":").append(num(item.minRange));
            j.append(",\"maxRange\":").append(num(item.getMaxRange()));
            j.append(",\"swingTime\":").append(num(item.getSwingTime()));
            j.append(",\"twoHanded\":").append(item.twoHandWeapon);
            j.append(",\"crit\":").append(num(item.criticalChance));
            j.append(",\"knockdown\":").append(num(item.knockdownMod));
            j.append(",\"pushBack\":").append(num(item.pushBackMod));
            j.append(",\"doorDamage\":").append(item.doorDamage);
            j.append(",\"hitChance\":").append(item.hitChance);
            j.append(",\"conditionMax\":").append(item.getConditionMax());
            j.append(",\"maxAmmo\":").append(item.maxAmmo);
            j.append(",\"soundRadius\":").append(item.getSoundRadius());
            j.append(",\"soundVolume\":").append(item.getSoundVolume());
            j.append(",\"ammoType\":").append(item.getAmmoType() == null ? "null" : str(String.valueOf(item.getAmmoType())));
            j.append(",\"treeDamage\":").append(intField(item, "treeDamage"));
            j.append(",\"clipSize\":").append(intField(item, "clipSize"));
            // what kind of weapon it is and how many targets one swing may hit: Minecraft picks the swing from these
            j.append(",\"categories\":[");
            boolean firstCategory = true;
            if (item.getWeaponCategories() != null) {
                List<String> categories = new ArrayList<>();
                for (Object category : item.getWeaponCategories()) categories.add(typeName(String.valueOf(category)));
                java.util.Collections.sort(categories);
                for (String category : categories) { j.append(firstCategory ? "" : ",").append(str(category)); firstCategory = false; }
            }
            j.append(']');
            j.append(",\"swingAnim\":").append(item.getSwingAnim() == null ? "null" : str(item.getSwingAnim()));
            j.append(",\"maxHitCount\":").append(item.getMaxHitCount());
            j.append('}');
        }
        if (type.equals("food")) {
            j.append(",\"food\":{\"hunger\":").append(num(item.getHungerChange()));
            j.append(",\"thirst\":").append(num(item.getThirstChange()));
            j.append(",\"unhappy\":").append(num(item.getUnhappyChange()));
            j.append(",\"boredom\":").append(num(item.getBoredomChange()));
            j.append(",\"stress\":").append(num(item.getStressChange()));
            j.append(",\"endurance\":").append(num(item.getEnduranceChange()));
            j.append(",\"daysFresh\":").append(item.getDaysFresh());
            j.append(",\"daysRotten\":").append(item.getDaysTotallyRotten());
            j.append('}');
        }
        if (type.equals("clothing")) {
            j.append(",\"clothing\":{\"bite\":").append(num(floatField(item, "biteDefense")));
            j.append(",\"scratch\":").append(num(floatField(item, "scratchDefense")));
            j.append(",\"bullet\":").append(num(floatField(item, "bulletDefense")));
            j.append(",\"insulation\":").append(num(floatField(item, "insulation")));
            j.append(",\"combatSpeed\":").append(num(item.combatSpeedModifier));
            j.append('}');
        }
        if (item.getBodyLocation() != null) j.append(",\"bodyLocation\":").append(str(String.valueOf(item.getBodyLocation())));
        if (item.getReplaceOnUse() != null && !item.getReplaceOnUse().isBlank()) j.append(",\"replaceOnUse\":").append(str(item.getReplaceOnUse()));
        j.append('}');
    }

    /** {@code base:weapon} becomes {@code weapon}: the item type without its module. */
    private static String typeName(Item item) { return typeName(String.valueOf(item.getItemType())); }

    private static String typeName(String name) {
        String t = name.toLowerCase(java.util.Locale.ROOT);
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    private static String safeName(String full, Item item) {
        try {
            String translated = Translator.getItemNameFromFullType(full);
            if (translated != null && !translated.isBlank() && !translated.equals(full)) return translated;
        } catch (RuntimeException ignored) { }
        return item.getDisplayName();
    }

    private static int intField(Item item, String name) {
        try {
            Field f = Item.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(item);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    private static float floatField(Item item, String name) {
        try {
            Field f = Item.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(item);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    private static String num(float v) {
        return Float.isFinite(v) ? Float.toString(v) : "0";
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ---- icons ----

    /** Reads each atlas page once from the GPU and crops its icons into square, transparent PNGs. */
    private static void exportIcons(Map<String, Texture> textures) {
        Map<Integer, List<Texture>> byPage = new LinkedHashMap<>();
        for (Texture t : textures.values()) byPage.computeIfAbsent(t.getID(), k -> new ArrayList<>()).add(t);
        iconCount = 0;
        failed = 0;
        RenderThread.invokeOnRenderContext(() -> {
            for (Map.Entry<Integer, List<Texture>> page : byPage.entrySet()) {
                int pw = 0, ph = 0;
                ByteBuffer buffer = null;
                try {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    GL11.glEnable(GL11.GL_TEXTURE_2D);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, page.getKey());
                    pw = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                    ph = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                    if (pw <= 0 || ph <= 0) { failed += page.getValue().size(); continue; }
                    GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                    buffer = MemoryUtil.memAlloc(pw * ph * 4);
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
                    for (Texture t : page.getValue()) {
                        if (cropIcon(buffer, pw, ph, t)) iconCount++; else failed++;
                    }
                } catch (RuntimeException e) {
                    failed += page.getValue().size();
                    Log.error("icon page " + page.getKey() + " failed", e);
                } finally {
                    if (buffer != null) MemoryUtil.memFree(buffer);
                }
            }
            zombie.core.SpriteRenderer.ringBuffer.restoreBoundTextures = true; // PZ rebinds its own textures
        });
    }

    private static boolean cropIcon(ByteBuffer page, int pw, int ph, Texture t) {
        int x = t.getX(), y = t.getY(), w = t.getWidth(), h = t.getHeight();
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > pw || y + h > ph) return false;
        int side = Math.max(w, h);
        ByteBuffer out = MemoryUtil.memCalloc(side * side * 4);
        try {
            int ox = (side - w) / 2, oy = side - h; // centred horizontally, standing on the bottom edge
            for (int row = 0; row < h; row++) {
                int src = ((y + row) * pw + x) * 4;
                int dst = ((oy + row) * side + ox) * 4;
                for (int i = 0; i < w * 4; i++) out.put(dst + i, page.get(src + i));
            }
            Path file = DIR.resolve("icons").resolve(safe(t.getName()) + ".png");
            return STBImageWrite.stbi_write_png(file.toString(), side, side, 4, out, side * 4);
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    private static String safe(String name) { return name.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}

