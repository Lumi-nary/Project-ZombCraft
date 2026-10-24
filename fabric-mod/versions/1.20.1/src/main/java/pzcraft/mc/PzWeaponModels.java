package pzcraft.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 1.20.1 port of the shared PzWeaponModels: PZ's own weapon meshes in Minecraft's hands. The converted private data in
 * {@code ~/.pzcraft/items/weapon-models} is the same as on 26.x; 1.20.1 has no special item models, so
 * {@code PzWeaponMesh120Mixin} hands every held/worn PZ weapon stack (not gui/ground/frames, which keep the PZ sprite) to
 * {@link #render} instead of the sprite model. The placement numbers and Tuning keys are the shared renderer's.
 */
public final class PzWeaponModels {
    static final Path DIR = PzItemCatalog.DIR.resolve("weapon-models");
    static final String NAMESPACE = PzBlocks.NAMESPACE;
    private static Map<String, String> items;
    private static JsonObject display;
    private static final Map<String, Optional<Model>> MODELS = new HashMap<>();

    private PzWeaponModels() {}

    /** Nothing to register on 1.20.1: the mixin asks {@link #render} directly. */
    public static void register() {}

    private static synchronized Map<String, String> items() {
        if (items != null) return items;
        Map<String, String> map = new HashMap<>();
        JsonObject shown = new JsonObject();
        try {
            Path index = DIR.resolve("index.json");
            if (Files.isRegularFile(index)) {
                for (var e : JsonParser.parseString(Files.readString(index)).getAsJsonObject().getAsJsonObject("items").entrySet())
                    map.put(e.getKey(), e.getValue().getAsString());
            }
            Path displayFile = DIR.resolve("display.json");
            if (Files.isRegularFile(displayFile)) shown = JsonParser.parseString(Files.readString(displayFile)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            PzCraftMain.LOG.warn("PZ weapon models unavailable: {}", e.toString());
        }
        display = shown;
        return items = map;
    }

    /** The model key (lower-case PZ weapon sprite name) for a PZ item type, or null when it keeps its sprite. */
    public static String keyOf(String fullType) { return fullType == null ? null : items().get(fullType); }

    // ---- the model ----

    private record Mesh(int tris, float[] pos, float[] uv, float[] nrm, float[] min, float[] max) {}
    private record Model(Mesh mesh, RenderType type) {}

    private static float[] floats(JsonObject o, String name) {
        JsonArray a = o.getAsJsonArray(name);
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    /** The mesh and its texture (registered straight from the private data; no resource pack involved). Render thread. */
    private static Model model(String key) {
        return MODELS.computeIfAbsent(key, k -> {
            try {
                Path png = DIR.resolve("textures").resolve(k + ".png");
                if (!Files.isRegularFile(png)) return Optional.empty();
                JsonObject o = JsonParser.parseString(Files.readString(DIR.resolve("models").resolve(k + ".json"))).getAsJsonObject();
                Mesh mesh = new Mesh(o.get("tris").getAsInt(), floats(o, "pos"), floats(o, "uv"), floats(o, "nrm"), floats(o, "min"), floats(o, "max"));
                NativeImage image;
                try (InputStream in = Files.newInputStream(png)) { image = NativeImage.read(in); }
                ResourceLocation texture = new ResourceLocation(NAMESPACE, "pzmodel/" + k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
                Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(image));
                return Optional.of(new Model(mesh, RenderType.entityCutout(texture)));
            } catch (IOException | RuntimeException e) {
                PzCraftMain.LOG.warn("PZ weapon model {} could not be loaded: {}", k, e.toString());
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static boolean shownAsSprite(ItemDisplayContext context) {
        return context == ItemDisplayContext.GUI || context == ItemDisplayContext.GROUND || context == ItemDisplayContext.FIXED;
    }

    /** The item model's display transform for this context (display.json, as 26.x's pzmodel_base carries it). */
    private static void displayTransform(ItemDisplayContext context, boolean leftHand, PoseStack pose) {
        if (display == null) return;
        String name = context.getSerializedName();
        if (!display.has(name) || !display.get(name).isJsonObject()) return;
        JsonObject t = display.getAsJsonObject(name);
        float[] r = vec(t, "rotation", 0), tr = vec(t, "translation", 0), s = vec(t, "scale", 1);
        float flip = leftHand ? -1 : 1;
        pose.translate(flip * tr[0] / 16f, tr[1] / 16f, tr[2] / 16f);
        pose.mulPose(new org.joml.Quaternionf().rotationXYZ((float) Math.toRadians(r[0]), (float) Math.toRadians(r[1] * flip), (float) Math.toRadians(r[2] * flip)));
        pose.scale(s[0], s[1], s[2]);
    }

    private static float[] vec(JsonObject o, String name, float fallback) {
        float[] v = {fallback, fallback, fallback};
        if (o.has(name) && o.get(name).isJsonArray()) for (int i = 0; i < 3 && i < o.getAsJsonArray(name).size(); i++) v[i] = o.getAsJsonArray(name).get(i).getAsFloat();
        return v;
    }

    private static void rotate(PoseStack pose, Axis axis, double degrees) { pose.mulPose(axis.rotationDegrees((float) degrees)); }

    /**
     * Draws a held PZ weapon as its mesh, as the shared renderer does after Minecraft's item transform. Returns false (the PZ
     * sprite is drawn) for other stacks, own Geo guns, gui/ground/frames, and weapons whose converted mesh is missing.
     */
    public static boolean render(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource source, int light, int overlay) {
        if (shownAsSprite(context) || GunModels.kind(stack) != null) return false;
        String type = PzItems.typeOf(stack);
        String key = keyOf(type);
        if (key == null) return false;
        Model model = model(key);
        if (model == null) return false;
        Mesh mesh = model.mesh;
        PzItemCatalog.Entry entry = PzItemCatalog.get(type);
        boolean gun = entry != null && entry.weapon() != null && entry.weapon().has("firearm");
        pose.pushPose();
        displayTransform(context, leftHand, pose);
        pose.translate(-0.5f, -0.5f, -0.5f); // what ItemRenderer does before any item model
        boolean firstPass = context.firstPerson();
        boolean third = !firstPass;
        // A firearm lies along the mesh's +Y (barrel forward) with +Z up: held barrel-forward, scaled by its length.
        String pre = gun ? (third ? "pzgun3." : "pzgun.") : (third ? "pzmesh3." : "pzmesh.");
        float length = Math.max(mesh.max[1] - mesh.min[1], 1e-3f);
        boolean pistol = length < .25f;
        float scale = (float) Tuning.get(pre + "scale", gun ? (pistol ? 2.2 : 0.93 / length) : 3.0);
        float flipX = (float) Tuning.get(gun ? "pzgun.flipX" : "pzmesh.flipX", gun ? -1 : 1), flipZ = (float) Tuning.get(gun ? "pzgun.flipZ" : "pzmesh.flipZ", -1);
        // PZ meshes already have their grip at the origin: undo the unit-cube centring for the world hand pass.
        if (third) pose.translate(0.5f, 0.5f, 0.5f);
        if (!gun && !third) {
            float[] motion = PzWeaponMotion.melee();
            if (motion != null) {
                pose.translate(motion[0], motion[1], motion[2]);
                rotate(pose, Axis.XP, motion[3]); rotate(pose, Axis.YP, motion[4]); rotate(pose, Axis.ZP, motion[5]);
            }
        }
        if (gun) {
            pose.translate((float) Tuning.get(pre + "x", third ? 0 : pistol ? 0.17 : 0.26), (float) Tuning.get(pre + "y", third ? 0 : pistol ? 0.82 : 0.62),
                    (float) Tuning.get(pre + "z", third ? 0 : pistol ? 0.78 : 0.5));
            float[] anim = third ? null : GunMeshAnim.pose(); // only the local first-person gun follows this action
            if (anim != null) {
                pose.translate(anim[0], anim[1], anim[2]);
                rotate(pose, Axis.XP, anim[3]); rotate(pose, Axis.YP, anim[4]); rotate(pose, Axis.ZP, anim[5]);
            }
            // how the whole gun is turned about the grip: yaw (towards the screen centre), then pitch (muzzle up)
            rotate(pose, Axis.YP, Tuning.get(pre + "yaw", third || pistol ? 0 : 4));
            rotate(pose, Axis.XP, Tuning.get(pre + "pitch", third || pistol ? 0 : 2));
            rotate(pose, Axis.ZP, Tuning.get(pre + "rz", 0));
            rotate(pose, Axis.XP, Tuning.get(pre + "rx", -90));
            rotate(pose, Axis.YP, Tuning.get(pre + "ry", 180));
        } else {
            pose.translate((float) Tuning.get(pre + "x", third ? 0 : 0.35), (float) Tuning.get(pre + "y", third ? 0 : 0.05), (float) Tuning.get(pre + "z", third ? 0 : -0.3));
            // a vertex is rolled about the blade's own axis (ry), tipped forward (rx), then leaned sideways (rz)
            rotate(pose, Axis.ZP, Tuning.get(pre + "rz", third ? 0 : 35));
            rotate(pose, Axis.XP, Tuning.get(pre + "rx", third ? 30 : -30));
            rotate(pose, Axis.YP, Tuning.get(pre + "ry", third ? 0 : 90));
        }
        pose.scale(scale * flipX, scale, scale * flipZ);
        if (gun && firstPass) {
            // Aiming down the sights: the sight point of the mesh is brought onto the view axis as the aim blends in.
            float aim = GunFeel.aim();
            if (aim > 0.001f) {
                var sight = new Vector3f(0, mesh.max[1] - length * (float) Tuning.get("pzgun.sightAlong", 0.1), mesh.max[2] + (float) Tuning.get("pzgun.sightUp", 0));
                pose.last().pose().transformPosition(sight);
                pose.last().pose().mulLocal(new Matrix4f().translation(-sight.x * aim + (float) Tuning.get("pzgun.adsX", 0) * aim,
                        -sight.y * aim + (float) Tuning.get("pzgun.adsY", 0) * aim, (float) Tuning.get("pzgun.adsZ", .15) * aim));
            }
        }
        boolean reverse = (flipX * flipZ < 0) != (Tuning.get("pzmesh.reverse", 0) > 0);
        // triangles go in as quads with a repeated corner
        int[] order = reverse ? new int[] {0, 2, 1, 1} : new int[] {0, 1, 2, 2};
        Matrix4f p = pose.last().pose();
        Matrix3f n = pose.last().normal();
        VertexConsumer vc = source.getBuffer(model.type);
        for (int t = 0; t < mesh.tris; t++) {
            for (int k : order) {
                int i = t * 3 + k;
                vc.vertex(p, mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2]).color(255, 255, 255, 255).uv(mesh.uv[i * 2], mesh.uv[i * 2 + 1])
                        .overlayCoords(overlay).uv2(light).normal(n, mesh.nrm[i * 3], mesh.nrm[i * 3 + 1], mesh.nrm[i * 3 + 2]).endVertex();
            }
        }
        // Arms after the mesh: vanilla's immediate buffer source would otherwise mix the gun's faces with Steve's skin.
        if (gun && !third) PzWeaponArms.submit(pose, source, light, Math.min(.28f, length * .38f));
        pose.popPose();
        return true;
    }
}
