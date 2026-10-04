package pzcraft.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Scoped owner-supplied visuals stay outside the workspace and jar. Only these pinned M9 assets enter the private pack. */
final class M9Assets {
    private static final Path ROOT = Path.of(System.getProperty("user.home"), ".pzcraft", "private",
            "pointblank-26.1.2-2.2.0", "m9-reference", "assets", "pointblank");
    private static final Map<String, String> FILES = Map.of(
            "geckolib/models/item/m9.geo.json", "966e4a38ef2392d9b990fa98f8c20b8895ced69babe8185b83668064a954dc2b",
            "geckolib/animations/item/m9.animation.json", "4baf37320304f1cebdda04896482f99323b58509a01990067069bdee4ade93a6",
            "textures/item/m9.png", "2c699a852c190e68e5bf07503db34ce5ec85ed98d2788ee72fd09c67d93b4be4",
            "textures/item/m9.icon.png", "a121311d48ca44bf6f08a07b04826433bf1650789b7b9d7fab5a6d862289e6bf",
            "models/item/m9_base.json", "8515e645fb8de47e6116021dcb1967f65d06a2dbe63b9c9bcf9b9c27044f4df1",
            "textures/gui/crosshair.png", "20c46b33f81967349460bea90d4f0289e222d8dc98fcb1eefb359151de8e3ddf",
            "textures/effect/flashes.png", "dbc6e5a9cb7c42acddade0b1419f82affcd85575e0d3ebd2c736f5567b2f564a",
            "textures/effect/tracers2.png", "4fe14b08a40b9ddd8b0a81cc47a5172826538e9e4acf40f284f76ac8757bd72c",
            "textures/entity/shell_casing/ar_shell.png", "3028cca951b7b77e035f32acd0eb63161dffe4c4f11b4a8fd37ef3500315f70d",
            "geckolib/models/entity/shell_casing/pistol_shell.geo.json", "1f9124e56b6f49bd2705d04437a11f9ca725eb4fb0de884f9912a6d0560d0f7b");
    private static Boolean available;

    private M9Assets() {}

    static synchronized boolean available() {
        if (available != null) return available;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var entry : FILES.entrySet()) {
                Path file = ROOT.resolve(entry.getKey());
                if (!Files.isRegularFile(file) || !HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))).equals(entry.getValue()))
                    return available = false;
            }
            return available = true;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            PzCraftClient.LOG.warn("Private M9 visual assets unavailable: {}", e.toString());
            return available = false;
        }
    }

    static String stamp() { return available() ? "m9-render-3" : "m9-off"; }

    static void writePack(Path pack) throws IOException {
        if (!available()) return;
        Path assets = pack.resolve("assets/pzcraft");
        for (String name : FILES.keySet()) {
            Path target = assets.resolve(name);
            Files.createDirectories(target.getParent());
            if (name.endsWith("m9_base.json")) {
                Files.writeString(target, Files.readString(ROOT.resolve(name)).replace("pointblank:", "pzcraft:"));
            } else Files.copy(ROOT.resolve(name), target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(assets.resolve("items"));
        // The 3D model is for the hands; the hotbar, inventory, ground and frames show PZ's own sprite for the pistol.
        String special = "{\"type\":\"minecraft:special\",\"base\":\"pzcraft:item/m9_base\",\"model\":{\"type\":\"geckolib:geckolib\"}}";
        var pistol = PzItemCatalog.get("Base.Pistol");
        String icon = pistol != null && pistol.icon() != null ? PzItemCatalog.iconKey(pistol.icon()) : null;
        Files.writeString(assets.resolve("items/m9.json"), icon == null ? "{\"model\":" + special + "}"
                : "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[{\"when\":"
                + "[\"gui\",\"ground\",\"fixed\",\"on_shelf\"],\"model\":{\"type\":\"minecraft:model\",\"model\":\"pzcraft:item/pz/" + icon
                + "\"}}],\"fallback\":" + special + "}}");
        PzCraftClient.LOG.info("Private M9 visuals loaded from ten verified owner-supplied files");
    }
}

