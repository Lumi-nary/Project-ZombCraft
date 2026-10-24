package pzcraft.mc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;

/**
 * Builds a resource pack from the icons PZ exported, so every PZ item type gets an item model (the icon as a flat item).
 * Written to {@code ~/.pzcraft/items/packs/pzcraft-items} and rebuilt only when PZ re-exported its catalogue.
 */
public final class PzItemPack {
    /** The stamp of the pack Minecraft was last given; a different current stamp means PZ re-exported since. */
    private static volatile String providedStamp;

    private PzItemPack() {}

    /** The stamp the pack would have now, or null while PZ has exported nothing. */
    public static String currentStamp() {
        try {
            Path items = PzItemCatalog.DIR.resolve("items.json"), icons = PzItemCatalog.DIR.resolve("icons");
            if (!Files.isRegularFile(items) || !Files.isDirectory(icons)) return null;
            long count;
            try (Stream<Path> s = Files.list(icons)) { count = s.filter(f -> f.toString().endsWith(".png")).count(); }
            return Files.getLastModifiedTime(items).toMillis() + ":" + Files.size(items) + ":" + count + ":" + GunModels.stamp() + ":" + PzWeaponModels.stamp() + ":" + packFormat();
        } catch (IOException e) {
            return null;
        }
    }

    /** The resource pack format: 26.1.2 and 26.3 share the pack folder, and each rebuilds it in its own format. */
    private static String packFormat() {
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        return "f" + format.major() + "." + format.minor();
    }

    /** True when PZ exported items after Minecraft last loaded the pack (the packs must be rescanned and reloaded). */
    public static boolean stale() {
        String now = currentStamp();
        return now != null && !now.equals(providedStamp);
    }

    /**
     * Stacks saved before the rename name their model "pzcraft:...": every item definition is also written under the old
     * namespace, pointing at the same new models, so they still draw (in a chest, before PzWeaponModels moves them on).
     */
    private static void aliasOldDefinitions(Path pack) throws IOException {
        Path from = pack.resolve("assets").resolve(PzBlocks.NAMESPACE).resolve("items");
        Path to = pack.resolve("assets").resolve(PzBlocks.OLD_NAMESPACE).resolve("items");
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Path target = to.resolve(from.relativize(f).toString());
                Files.createDirectories(target.getParent());
                Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** The pack folder, rebuilt if needed; null while PZ has not exported anything yet. */
    public static Path refresh() throws IOException {
        Path items = PzItemCatalog.DIR.resolve("items.json"), icons = PzItemCatalog.DIR.resolve("icons");
        if (!Files.isRegularFile(items) || !Files.isDirectory(icons)) return null;
        Path pack = PzItemCatalog.DIR.resolve("packs").resolve("pzcraft-items");
        long count;
        try (Stream<Path> s = Files.list(icons)) { count = s.filter(f -> f.toString().endsWith(".png")).count(); }
        String stamp = Files.getLastModifiedTime(items).toMillis() + ":" + Files.size(items) + ":" + count + ":" + GunModels.stamp() + ":" + PzWeaponModels.stamp() + ":" + packFormat();
        Path stampFile = pack.resolve(".stamp");
        if (Files.isRegularFile(stampFile) && Files.readString(stampFile).equals(stamp) && Files.isRegularFile(pack.resolve("pack.mcmeta"))) {
            providedStamp = stamp;
            return pack;
        }

        PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        String version = "[" + format.major() + "," + format.minor() + "]";
        Path assets = pack.resolve("assets").resolve(PzBlocks.NAMESPACE);
        // This is a generated pack. Rebuild both namespaces from current sources so
        // retired models and old item definitions cannot survive a source change.
        clearGeneratedNamespace(assets);
        clearGeneratedNamespace(pack.resolve("assets").resolve(PzBlocks.OLD_NAMESPACE));
        Path textures = assets.resolve("textures").resolve("item").resolve("pz");
        Path models = assets.resolve("models").resolve("item").resolve("pz");
        Path definitions = assets.resolve("items").resolve("pz");
        Files.createDirectories(textures);
        Files.createDirectories(models);
        Files.createDirectories(definitions);
        Files.writeString(pack.resolve("pack.mcmeta"),
                "{\"pack\":{\"description\":\"Project-ZombCraft: Project Zomboid items\",\"min_format\":" + version + ",\"max_format\":" + version + "}}", StandardCharsets.UTF_8);
        int written = 0;
        try (Stream<Path> s = Files.list(icons)) {
            for (Path png : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".png"))::iterator) {
                String name = png.getFileName().toString();
                String key = PzItemCatalog.iconKey(name.substring(0, name.length() - 4));
                Files.copy(png, textures.resolve(key + ".png"), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(models.resolve(key + ".json"),
                        "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"" + PzBlocks.NAMESPACE + ":item/pz/" + key + "\"}}", StandardCharsets.UTF_8);
                Files.writeString(definitions.resolve(key + ".json"),
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + PzBlocks.NAMESPACE + ":item/pz/" + key + "\"}}", StandardCharsets.UTF_8);
                written++;
            }
        }
        GunModels.writePack(pack);
        PzWeaponModels.writePack(pack);
        aliasOldDefinitions(pack);
        Files.writeString(stampFile, stamp);
        PzCraftMain.LOG.info("PZ item resource pack rebuilt: {} icons ({})", written, pack);
        providedStamp = stamp;
        return pack;
    }

    private static void clearGeneratedNamespace(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
