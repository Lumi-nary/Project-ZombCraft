package pzcraft.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;

/** Local PZ hand tracks adapted to an unrigged weapon's grip. No game animation assets are bundled. */
final class PzWeaponMotion {
    private static JsonObject data;
    private static long modified = Long.MIN_VALUE;

    private PzWeaponMotion() {}

    static String profile(String fullType) {
        var entry = PzItemCatalog.get(fullType);
        var weapon = entry == null ? null : entry.weapon();
        if (weapon == null) return "boltaction";
        if (weapon.has("reloadType") && !weapon.get("reloadType").isJsonNull())
            return weapon.get("reloadType").getAsString().toLowerCase(java.util.Locale.ROOT);
        return weapon.has("twoHanded") && weapon.get("twoHanded").getAsBoolean() ? "boltaction" : "handgun";
    }

    /** PZ motion is the default melee presentation; an explicitly installed combat animator keeps its own motion. */
    static float[] melee() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (!Session.pzConnected || mc.player == null
                || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("bettercombat")) return null;
        String fullType = PzItems.typeOf(mc.player.getMainHandItem());
        if (fullType == null) return null;
        var entry = PzItemCatalog.get(fullType);
        var weapon = entry == null ? null : entry.weapon();
        if (weapon == null || weapon.has("firearm") || !weapon.has("swingAnim") || weapon.get("swingAnim").isJsonNull()) return null;
        String swing = weapon.get("swingAnim").getAsString();
        if (swing.equals("Throw") || swing.equals("Shove") || swing.equals("Stone")) return null;
        String group = switch (swing) {
            case "Heavy" -> "heavy";
            case "Spear" -> "spear";
            case "Stab" -> "knife";
            default -> weapon.has("twoHanded") && weapon.get("twoHanded").getAsBoolean() ? "2handed" : "1handed";
        };
        //#if MC_26_1
        //$ float progress = mc.player.getAttackAnim(1.0f);
        //#else
        float progress = mc.player.getSwingAnimation(1.0f);
        //#endif
        return progress > 0 ? sample(group, "attack", progress) : null;
    }

    private static synchronized JsonObject data() {
        try {
            var file = PzWeaponModels.DIR.resolve("motion.json");
            long current = Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
            if (modified == current) return data;
            modified = current;
            data = current < 0 ? null : JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (data != null && data.get("version").getAsInt() != 1) data = null;
        } catch (java.io.IOException | RuntimeException error) {
            data = null;
            PzCraftMain.LOG.warn("PZ weapon motion unavailable: {}", error.toString());
        }
        return data;
    }

    /** Normalized progress follows PZ's actual action duration, independent of the source clip's rate. */
    static float[] sample(String profile, String action, double progress) {
        return sample(profile, action, progress, false);
    }

    static float[] hands(String profile, String action, double progress) {
        return sample(profile, action, progress, true);
    }

    private static float[] sample(String profile, String action, double progress, boolean hands) {
        var root = data();
        if (root == null) return null;
        try {
            var profiles = root.getAsJsonObject("profiles");
            var actions = profiles.getAsJsonObject(profiles.has(profile) ? profile : "boltaction");
            if (!actions.has(action)) return null;
            var clip = root.getAsJsonObject("clips").getAsJsonObject(actions.get(action).getAsString());
            if (hands && !clip.has("hands")) return null;
            var frames = clip.getAsJsonArray(hands ? "hands" : "frames");
            if (frames.size() < 2) return null;
            double u = Math.max(0, Math.min(1, progress));
            int index = Math.min(frames.size() - 2, (int) (u * (frames.size() - 1)));
            var a = frames.get(index).getAsJsonArray();
            var b = frames.get(index + 1).getAsJsonArray();
            double span = b.get(0).getAsDouble() - a.get(0).getAsDouble();
            double blend = Math.max(0, Math.min(1, (u - a.get(0).getAsDouble()) / Math.max(1e-8, span)));
            // Return smoothly to the held pose at both boundaries, including a looping load stroke.
            double envelope = Math.min(1, u * 10) * Math.min(1, (1 - u) * 10);
            float[] out = new float[6];
            for (int i = 0; i < 6; i++) {
                double value = a.get(i + 1).getAsDouble() * (1 - blend) + b.get(i + 1).getAsDouble() * blend;
                if (!Double.isFinite(value)) return null;
                double limit = hands ? .4 : i < 3 ? .3 : 65;
                //#if MC_1_20
                //$ out[i] = (float) (Math.max(-limit, Math.min(limit, value)) * envelope); // Java 17: no Math.clamp
                //#else
                out[i] = (float) (Math.clamp(value, -limit, limit) * envelope);
                //#endif
            }
            return out;
        } catch (RuntimeException error) {
            return null; // preserve the original procedural motion for incomplete local caches
        }
    }
}
