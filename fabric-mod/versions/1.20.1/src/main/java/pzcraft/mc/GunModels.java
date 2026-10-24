package pzcraft.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Project-ZombCraft custom gun models bundled with this mod. PZ owns weapon behavior.
 * Firearms without a custom model use PZ's own locally converted mesh and generic motion.
 */
final class GunModels {
    /** What one model can show. {@code clips} are the logical clip names with their lengths in seconds. */
    record Kind(String id, String recipe, Set<String> hide, Map<String, Double> clips, boolean pistol, String shell, boolean bigFlash,
                boolean fireRacks, boolean loadendCloses, ResourceLocation model, JsonObject raw,
                java.util.List<String> roundBones, Set<String> roundsShowAll, Map<String, Set<String>> clipBones,
                java.util.List<String> emptyBones) {
        boolean has(String clip) { return clips.containsKey(clip); }
        double length(String clip) { return clips.getOrDefault(clip, 0.0); }
        /** Rounds go in one at a time (or both barrels at once) instead of a magazine. */
        boolean rounds() { return has("load"); }
        double number(String key, double fallback) { return raw != null && raw.has(key) ? raw.get(key).getAsDouble() : fallback; }

        /** When PZ's own sounds belong in a clip (the clip's sound marks) and how long its reload phase is; null without the clip. */
        pzcraft.protocol.GunAction.Timing timing(String clip, String phaseKey) {
            if (raw == null || !has(clip)) return null;
            var all = raw.has("cues") ? raw.getAsJsonObject("cues") : null;
            var marks = all != null && all.has(clip) ? all.getAsJsonArray(clip) : new com.google.gson.JsonArray();
            double[][] cues = new double[marks.size()][];
            for (int i = 0; i < cues.length; i++) {
                var mark = marks.get(i).getAsJsonArray();
                cues[i] = new double[] {mark.get(0).getAsDouble(), mark.get(1).getAsInt()};
            }
            return new pzcraft.protocol.GunAction.Timing(phaseKey == null ? 0 : number(phaseKey, 0), cues);
        }
    }

    /**
     * The gun effects and shell casings are our own (tools/effects/make_effects.py, in this jar's assets). A private set that
     * carries files of the same names leaves them out of the item pack, so every machine shows the ones players get.
     */
    static final Set<String> OWN_FILES = Set.of("textures/gui/crosshair.png", "textures/effect/flashes.png", "textures/effect/tracers2.png",
            "textures/effect/blood.png", "textures/effect/blood2.png", "textures/effect/dirt3x3.png", "textures/entity/shell_casing/ar_shell.png",
            "geckolib/models/entity/shell_casing/pistol_shell.geo.json", "geckolib/models/entity/shell_casing/ar_shell.geo.json");
    /**
     * Every kind that has ever had an item, in registration order. The list only grows: a world may hold stacks of any of them,
     * and an unknown item id would be dropped on load (which the inventory mirror would then take for a lost item).
     */
    static final String[] IDS = {"m9", "m1911a1", "deserteagle", "rhino", "m16a1", "mk14ebr", "m870", "m590", "c14", "l96a1",
            "citoricxs", "lever", "m870sawn", "citorisawn", "rhinolong", "rhinoshort", "mag9mm", "mag45", "mag44", "mag556", "m1a",
            "js14", "trapper"};
    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    private static final Map<String, String> TYPE_KIND = new LinkedHashMap<>();
    private static final Map<String, JsonObject> TYPE_EXTRA = new LinkedHashMap<>();
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    /** Every file of the kinds in use (path under assets/pzcraft) and where it is read from. */
    private static final Map<String, Path> FILES = new LinkedHashMap<>();
    private static Boolean available;
    private static String manifestHash = "";

    private GunModels() {}

    /** Reads the bundled custom gun set once. */
    static synchronized boolean available() {
        if (available != null) return available;
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("geckolib")) return available = false;
        var own = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(PzBlocks.NAMESPACE).flatMap(c -> c.findPath("pzcraft/guns"));
        if (own.isPresent()) load(own.get(), "own guns");
        PzCraftMain.LOG.info("Gun visuals: {} custom models for {} PZ item types", KINDS.size(), TYPE_KIND.size());
        return available = !KINDS.isEmpty();
    }

    /** One set (manifest.json and assets/pzcraft/... under {@code root}): taken whole, every listed file present with its SHA-256, or not at all. */
    private static void load(Path root, String what) {
        try {
            byte[] bytes = Files.readAllBytes(root.resolve("manifest.json"));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(bytes)).substring(0, 12);
            JsonObject manifest = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Path> files = new LinkedHashMap<>();
            for (var e : manifest.getAsJsonObject("files").entrySet()) {
                Path file = root.resolve("assets/pzcraft").resolve(e.getKey());
                if (!Files.isRegularFile(file) || !HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))).equals(e.getValue().getAsString())) {
                    PzCraftMain.LOG.warn("{}: {} is missing or changed; this set is not used", what, e.getKey());
                    return;
                }
                files.put(e.getKey(), file);
            }
            Set<String> known = Set.of(IDS);
            Map<String, Kind> kinds = new LinkedHashMap<>();
            for (var e : manifest.getAsJsonObject("kinds").entrySet()) {
                if (!known.contains(e.getKey())) { PzCraftMain.LOG.warn("{}: kind {} has no registered item, ignored", what, e.getKey()); continue; }
                if (KINDS.containsKey(e.getKey())) continue;
                JsonObject k = e.getValue().getAsJsonObject();
                boolean original = k.has("own") && k.get("own").getAsBoolean()
                        && k.has("source") && k.get("source").getAsString().equals("own");
                boolean editedM16 = e.getKey().equals("m16a1") && k.has("ownerEdited") && k.get("ownerEdited").getAsBoolean()
                        && k.has("source") && k.get("source").getAsString().equals("owner-edited-pz-m16");
                if (!original && !editedM16) {
                    PzCraftMain.LOG.warn("Ignoring unselected gun model {}", e.getKey());
                    continue;
                }
                Set<String> hide = new LinkedHashSet<>();
                k.getAsJsonArray("hide").forEach(h -> hide.add(h.getAsString()));
                Map<String, Double> clips = new LinkedHashMap<>();
                for (var c : k.getAsJsonObject("clips").entrySet()) clips.put(c.getKey(), c.getValue().getAsDouble());
                kinds.put(e.getKey(), new Kind(e.getKey(), k.get("recipe").getAsString(), Set.copyOf(hide), Map.copyOf(clips),
                        k.has("pistol") && k.get("pistol").getAsBoolean(), k.has("shell") ? k.get("shell").getAsString() : null,
                        k.has("flash") && k.get("flash").getAsString().equals("big"), k.has("fireRacks") && k.get("fireRacks").getAsBoolean(),
                        k.has("loadendCloses") && k.get("loadendCloses").getAsBoolean(), new net.minecraft.resources.ResourceLocation(PzBlocks.NAMESPACE + ":gun_" + e.getKey()), k,
                        strings(k, "roundBones"), Set.copyOf(strings(k, "roundsShowAll")), clipBones(k), strings(k, "emptyBones")));
            }
            KINDS.putAll(kinds);
            files.forEach(FILES::putIfAbsent);
            for (var e : manifest.getAsJsonObject("types").entrySet()) {
                JsonObject t = e.getValue().getAsJsonObject();
                if (TYPE_KIND.containsKey(e.getKey()) || !KINDS.containsKey(t.get("kind").getAsString())) continue;
                TYPE_KIND.put(e.getKey(), t.get("kind").getAsString());
                TYPE_EXTRA.put(e.getKey(), t);
            }
            manifestHash += hash;
            PzCraftMain.LOG.info("{}: {} kinds from {} ({} verified files)", what, kinds.size(), root, files.size());
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            PzCraftMain.LOG.warn("{} unavailable: {}", what, e.toString());
        }
    }

    /** Main entrypoint, registries open: one item per kind, always (a plain item where the visuals are missing). */
    static void register() {
        boolean on = available();
        for (String id : IDS) {
            String path = itemPath(id);
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, new net.minecraft.resources.ResourceLocation(PzBlocks.NAMESPACE, path));
            Item.Properties properties = new Item.Properties().stacksTo(1);
            Kind kind = on ? KINDS.get(id) : null;
            ITEMS.put(id, Registry.register(BuiltInRegistries.ITEM, key, kind != null ? new GunItem(properties, kind) : new Item(properties)));
        }
    }

    /** The item id of a kind: the pistol keeps the id it has had since the first spike. */
    private static String itemPath(String id) { return id.equals("m9") ? "pz_m9" : "pz_gun_" + id; }

    private static java.util.List<String> strings(JsonObject o, String key) {
        var out = new java.util.ArrayList<String>();
        if (o.has(key)) o.getAsJsonArray(key).forEach(x -> out.add(x.getAsString()));
        return java.util.List.copyOf(out);
    }

    /** Bones that are drawn only while one of the listed clips plays (the round in the hand while it is being put in). */
    private static Map<String, Set<String>> clipBones(JsonObject k) {
        var out = new LinkedHashMap<String, Set<String>>();
        if (k.has("clipBones")) for (var e : k.getAsJsonObject("clipBones").entrySet()) {
            var clips = new LinkedHashSet<String>();
            e.getValue().getAsJsonArray().forEach(x -> clips.add(x.getAsString()));
            out.put(e.getKey(), Set.copyOf(clips));
        }
        return Map.copyOf(out);
    }

    static Kind kindOf(String pzType) {
        String id = pzType == null ? null : TYPE_KIND.get(pzType);
        return id == null ? null : KINDS.get(id);
    }

    static Kind kind(ItemStack stack) { return stack != null && stack.getItem() instanceof GunItem gun ? gun.kind : null; }

    /** True for any of the gun items, with or without visuals (they all carry a PZ type like the plain PZ item). */
    static boolean isGunItem(Item item) { return ITEMS.containsValue(item); }

    /** The item a PZ firearm type is held as, or null for the plain PZ item (no kind for it, or no private visuals). */
    static Item itemFor(String pzType) {
        Kind kind = kindOf(pzType);
        return kind == null ? null : ITEMS.get(kind.id);
    }

    /** Bones a PZ type hides on top of its kind's (a variant of the same model). */
    static JsonObject extra(String pzType) { return TYPE_EXTRA.get(pzType); }

    static String typeKey(String pzType) { return pzType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_"); }

    /** The item model a stack of this PZ type uses: the gun in the hands, PZ's own sprite in slots and on the ground. */
    static ResourceLocation itemModel(String pzType) {
        Kind kind = kindOf(pzType);
        if (kind == null) return null;
        return new net.minecraft.resources.ResourceLocation(PzBlocks.NAMESPACE + ":guns/" + typeKey(pzType));
    }

    static String stamp() { return available() ? "1201-all-2-" + manifestHash : "own-guns-off"; }

    static void writePack(Path pack) throws IOException {
        if (!available()) return;
        Path assets = pack.resolve("assets/" + PzBlocks.NAMESPACE);
        for (var file : FILES.entrySet()) {
            if (OWN_FILES.contains(file.getKey())) continue;
            Path target = assets.resolve(file.getKey().replace("geckolib/models/", "geo/").replace("geckolib/animations/", "animations/"));
            Files.createDirectories(target.getParent());
            Files.copy(file.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
        }
        Path models = assets.resolve("models/item");
        Files.createDirectories(models);
        for (String id : IDS) {
            Files.writeString(models.resolve(itemPath(id) + ".json"), "{\"parent\":\"project-zombcraft:item/pz_item\"}");
        }
        for (var e : TYPE_KIND.entrySet()) {
            Kind kind = KINDS.get(e.getValue());
            // Geo4's builtin/entity model uses the authored display transforms unchanged.
            var file = FILES.get("models/item/gun_" + kind.id() + "_base.json");
            JsonObject model = file == null ? new JsonObject() : JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            model.addProperty("parent", "builtin/entity");
            Files.writeString(models.resolve(itemPath(kind.id()) + ".json"), model.toString());
        }
        PzCraftMain.LOG.info("Gun visuals written to the item pack: {} kinds, {} PZ types", KINDS.size(), TYPE_KIND.size());
    }
}
