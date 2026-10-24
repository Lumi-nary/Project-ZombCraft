package pzcraft.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * PZ's own weapon meshes in Minecraft's hands. {@code tools/pz-mcp/build-weapon-models.py} converts the models PZ uses
 * (media/models_X, media/textures) into private data in {@code ~/.pzcraft/items/weapon-models}; this loads them, writes the
 * item definitions and textures into the private item pack (the PZ sprite stays for gui/ground/frames, the mesh is what is
 * held) and draws the triangles through a special item renderer. Everything stays on this machine.
 */
public final class PzWeaponModels {
    static final Path DIR = PzItemCatalog.DIR.resolve("weapon-models");
    static final String NAMESPACE = PzBlocks.NAMESPACE;
    private static Map<String, String> items;

    private PzWeaponModels() {}

    private static synchronized Map<String, String> items() {
        if (items != null) return items;
        Map<String, String> map = new HashMap<>();
        try {
            Path index = DIR.resolve("index.json");
            if (Files.isRegularFile(index)) {
                for (var e : JsonParser.parseString(Files.readString(index)).getAsJsonObject().getAsJsonObject("items").entrySet())
                    map.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (IOException | RuntimeException e) {
            PzCraftMain.LOG.warn("PZ weapon models unavailable: {}", e.toString());
        }
        return items = map;
    }

    /** The model key (lower-case PZ weapon sprite name) for a PZ item type, or null when it keeps its sprite. */
    public static String keyOf(String fullType) { return fullType == null ? null : items().get(fullType); }

    private static String itemKey(String fullType) { return fullType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"); }

    /** The item model a stack of this PZ type should carry (the mesh when held, the sprite elsewhere), or null. */
    public static Identifier modelOf(String fullType) {
        return keyOf(fullType) == null ? null : Identifier.fromNamespaceAndPath(NAMESPACE, "pzw/" + itemKey(fullType));
    }

    /** Stacks made before the models existed carry the sprite model: point them at the new definition. */
    public static void retarget(ItemStack stack) {
        Identifier saved = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        Identifier renamed = PzBlocks.renamed(saved);
        if (renamed != saved) stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, renamed); // made before the rename
        String type = PzItems.typeOf(stack);
        if (type == null || GunModels.kind(stack) != null) return; // only a real custom model takes precedence
        Identifier want = modelOf(type);
        if (want != null && !want.equals(stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL)))
            stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, want);
    }

    /** Changes when the converter ran again or the hand transforms were edited. */
    public static String stamp() {
        try {
            Path index = DIR.resolve("index.json"), display = DIR.resolve("display.json");
            if (!Files.isRegularFile(index)) return "wm-off";
            return "wm2:" + Files.getLastModifiedTime(index).toMillis() + ":" + Files.size(index) + ":"
                    + (Files.isRegularFile(display) ? Files.getLastModifiedTime(display).toMillis() + ":" + Files.size(display) : "none");
        } catch (IOException e) {
            return "wm-off";
        }
    }

    /** Textures, the shared base model with the hand transforms, and one item definition per weapon type. */
    static void writePack(Path pack) throws IOException {
        Path index = DIR.resolve("index.json");
        if (!Files.isRegularFile(index)) return;
        Path assets = pack.resolve("assets").resolve(NAMESPACE);
        Path textures = assets.resolve("textures/item/pzmodel");
        Path definitions = assets.resolve("items/pzw");
        Files.createDirectories(textures);
        Files.createDirectories(definitions);
        Files.createDirectories(assets.resolve("models/item"));
        JsonObject display = new JsonObject();
        Path displayFile = DIR.resolve("display.json");
        if (Files.isRegularFile(displayFile)) {
            for (var e : JsonParser.parseString(Files.readString(displayFile)).getAsJsonObject().entrySet())
                if (e.getValue().isJsonObject()) display.add(e.getKey(), e.getValue());
        }
        JsonObject base = new JsonObject();
        base.addProperty("gui_light", "front");
        JsonObject particle = new JsonObject();
        particle.addProperty("particle", "minecraft:item/iron_sword");
        base.add("textures", particle);
        base.add("display", display);
        Files.writeString(assets.resolve("models/item/pzmodel_base.json"), base.toString(), StandardCharsets.UTF_8);
        int models = 0, written = 0;
        Map<String, Boolean> copied = new HashMap<>();
        JsonObject root = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        for (var e : root.getAsJsonObject("items").entrySet()) {
            String key = e.getValue().getAsString();
            if (copied.putIfAbsent(key, true) == null) {
                Path png = DIR.resolve("textures").resolve(key + ".png");
                if (!Files.isRegularFile(png)) { copied.put(key, false); continue; }
                Files.copy(png, textures.resolve(key + ".png"), StandardCopyOption.REPLACE_EXISTING);
                models++;
            } else if (!copied.get(key)) continue;
            PzItemCatalog.Entry entry = PzItemCatalog.get(e.getKey());
            boolean gun = entry != null && entry.weapon() != null && entry.weapon().has("firearm");
            String special = "{\"type\":\"minecraft:special\",\"base\":\"" + NAMESPACE + ":item/pzmodel_base\",\"model\":{\"type\":\""
                    + NAMESPACE + ":pzmesh\",\"mesh\":\"" + key + "\"" + (gun ? ",\"gun\":true" : "") + "}}";
            String icon = entry != null && entry.icon() != null ? PzItemCatalog.iconKey(entry.icon()) : null;
            Files.writeString(definitions.resolve(itemKey(e.getKey()) + ".json"), icon == null ? "{\"model\":" + special + "}"
                    : "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[{\"when\":"
                    + "[\"gui\",\"ground\",\"fixed\",\"on_shelf\"],\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + NAMESPACE + ":item/pz/" + icon
                    + "\"}}],\"fallback\":" + special + "}}", StandardCharsets.UTF_8);
            written++;
        }
        PzCraftMain.LOG.info("PZ weapon models: {} item definitions, {} textures", written, models);
    }

    /** Registers the {@code pzcraft:pzmesh} special item model type. Client initialisation, before resources load. */
    public static void register() {
        SpecialModelRenderers.ID_MAPPER.put(Identifier.fromNamespaceAndPath(NAMESPACE, "pzmesh"), Unbaked.MAP_CODEC);
    }

    // ---- the model ----

    private record Mesh(int tris, float[] pos, float[] uv, float[] nrm, float[] min, float[] max) {}

    private static float[] floats(JsonObject o, String name) {
        JsonArray a = o.getAsJsonArray(name);
        float[] out = new float[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsFloat();
        return out;
    }

    private static @Nullable Mesh load(String key) {
        try {
            JsonObject o = JsonParser.parseString(Files.readString(DIR.resolve("models").resolve(key + ".json"))).getAsJsonObject();
            return new Mesh(o.get("tris").getAsInt(), floats(o, "pos"), floats(o, "uv"), floats(o, "nrm"), floats(o, "min"), floats(o, "max"));
        } catch (IOException | RuntimeException e) {
            PzCraftMain.LOG.warn("PZ weapon model {} could not be loaded: {}", key, e.toString());
            return null;
        }
    }

    /** The item's rendering context travels with its extracted state, independently of the player's camera. */
    public static Object handContext(SpecialModelRenderer<?> renderer, ItemDisplayContext context) {
        return renderer instanceof Renderer ? context : null;
    }

    private record Renderer(Mesh mesh, boolean gun, RenderType type) implements SpecialModelRenderer<ItemDisplayContext> {
        @Override
        public void submit(@Nullable ItemDisplayContext argument, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outlineColor) {
            // the held weapon is placed by looking: first person and third person get their own numbers (the item renderer is
            // not told which hand pose it is in, but the camera is)
            boolean firstPass = collector instanceof EntityCapture capture && (capture.flags & pzcraft.protocol.EntityLink.FLAG_HAND) != 0;
            boolean third = !firstPass && (argument == null || !argument.firstPerson());
            // A firearm lies along the mesh's +Y (barrel forward) with +Z up: held barrel-forward, scaled by its length.
            String pre = gun ? (third ? "pzgun3." : "pzgun.") : (third ? "pzmesh3." : "pzmesh.");
            float length = Math.max(mesh.max[1] - mesh.min[1], 1e-3f);
            // placed by looking (tools/pz-mcp/gun-mesh-view.py, tshot.py): a pistol sits low and right of the view axis, a long gun
            // lies along it with the stock leaving the screen at the bottom right
            boolean pistol = length < .25f;
            float scale = (float) Tuning.get(pre + "scale", gun ? (pistol ? 2.2 : 0.93 / length) : 3.0);
            float flipX = (float) Tuning.get(gun ? "pzgun.flipX" : "pzmesh.flipX", gun ? -1 : 1), flipZ = (float) Tuning.get(gun ? "pzgun.flipZ" : "pzmesh.flipZ", -1);
            pose.pushPose();
            // Minecraft centres unit-cube item models by (-.5,-.5,-.5). PZ meshes already
            // have their grip at the origin, so undo that centring for the world hand pass.
            if (third) pose.translate(0.5f, 0.5f, 0.5f);
            if (!gun && !third) {
                float[] motion = PzWeaponMotion.melee();
                if (motion != null) {
                    pose.translate(motion[0], motion[1], motion[2]);
                    //#if MC_26_1
                    //$ pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(motion[3]));
                    //$ pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(motion[4]));
                    //$ pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(motion[5]));
                    //#else
                    pose.rotateDegrees(com.mojang.math.Axis.XP, motion[3]);
                    pose.rotateDegrees(com.mojang.math.Axis.YP, motion[4]);
                    pose.rotateDegrees(com.mojang.math.Axis.ZP, motion[5]);
                    //#endif
                }
            }
            if (gun) {
                pose.translate((float) Tuning.get(pre + "x", third ? 0 : pistol ? 0.17 : 0.26), (float) Tuning.get(pre + "y", third ? 0 : pistol ? 0.82 : 0.62),
                        (float) Tuning.get(pre + "z", third ? 0 : pistol ? 0.78 : 0.5));
                float[] anim = third ? null : GunMeshAnim.pose(); // only the local first-person gun follows this action
                if (anim != null) {
                    pose.translate(anim[0], anim[1], anim[2]);
                    //#if MC_26_1
                    //$ pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(anim[3]));
                    //#else
                    pose.rotateDegrees(com.mojang.math.Axis.XP, anim[3]);
                    //#endif
                    //#if MC_26_1
                    //$ pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(anim[4]));
                    //#else
                    pose.rotateDegrees(com.mojang.math.Axis.YP, anim[4]);
                    //#endif
                    //#if MC_26_1
                    //$ pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(anim[5]));
                    //#else
                    pose.rotateDegrees(com.mojang.math.Axis.ZP, anim[5]);
                    //#endif
                }
                // how the whole gun is turned about the grip: yaw (towards the screen centre), then pitch (muzzle up)
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Tuning.get(pre + "yaw", third || pistol ? 0 : 4)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get(pre + "yaw", third || pistol ? 0 : 4));
                //#endif
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Tuning.get(pre + "pitch", third || pistol ? 0 : 2)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get(pre + "pitch", third || pistol ? 0 : 2));
                //#endif
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) Tuning.get(pre + "rz", 0)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.ZP, (float) Tuning.get(pre + "rz", 0));
                //#endif
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Tuning.get(pre + "rx", -90)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get(pre + "rx", -90));
                //#endif
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Tuning.get(pre + "ry", 180)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get(pre + "ry", 180));
                //#endif
            } else {
                pose.translate((float) Tuning.get(pre + "x", third ? 0 : 0.35), (float) Tuning.get(pre + "y", third ? 0 : 0.05), (float) Tuning.get(pre + "z", third ? 0 : -0.3));
                // a vertex is rolled about the blade's own axis (ry), tipped forward (rx), then leaned sideways (rz)
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) Tuning.get(pre + "rz", third ? 0 : 35)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.ZP, (float) Tuning.get(pre + "rz", third ? 0 : 35));
                //#endif
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Tuning.get(pre + "rx", third ? 30 : -30)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get(pre + "rx", third ? 30 : -30));
                //#endif
                //#if MC_26_1
                //$ pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Tuning.get(pre + "ry", third ? 0 : 90)));
                //#else
                pose.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get(pre + "ry", third ? 0 : 90));
                //#endif
            }
            pose.scale(scale * flipX, scale, scale * flipZ);
            if (gun && firstPass) {
                // Aiming down the sights: the sight point of the mesh (a little behind the muzzle end, on top) is brought onto the
                // view axis as the aim blends in; the hand pass is in camera space, so this is a camera-space shift.
                float aim = GunFeel.aim();
                if (aim > 0.001f) {
                    var sight = new Vector3f(0, mesh.max[1] - length * (float) Tuning.get("pzgun.sightAlong", 0.1), mesh.max[2] + (float) Tuning.get("pzgun.sightUp", 0));
                    pose.last().pose().transformPosition(sight);
                    pose.last().pose().mulLocal(new org.joml.Matrix4f().translation(-sight.x * aim + (float) Tuning.get("pzgun.adsX", 0) * aim,
                            -sight.y * aim + (float) Tuning.get("pzgun.adsY", 0) * aim, (float) Tuning.get("pzgun.adsZ", .15) * aim));
                }
            }
            if (gun && !third) PzWeaponArms.submit(pose, collector, light, Math.min(.28f, length * .38f));
            boolean reverse = (flipX * flipZ < 0) != (Tuning.get("pzmesh.reverse", 0) > 0);
            collector.submitCustomGeometry(pose, type, (p, vc) -> {
                // triangles go in as quads with a repeated corner
                int[] order = reverse ? new int[] {0, 2, 1, 1} : new int[] {0, 1, 2, 2};
                for (int t = 0; t < mesh.tris; t++) {
                    for (int k : order) {
                        int i = t * 3 + k;
                        vc.addVertex(p, mesh.pos[i * 3], mesh.pos[i * 3 + 1], mesh.pos[i * 3 + 2]).setColor(-1).setUv(mesh.uv[i * 2], mesh.uv[i * 2 + 1])
                                .setOverlay(overlay).setLight(light).setNormal(p, mesh.nrm[i * 3], mesh.nrm[i * 3 + 1], mesh.nrm[i * 3 + 2]);
                    }
                }
            });
            pose.popPose();
        }

        @Override
        public void getExtents(Consumer<Vector3fc> output) {
            for (int i = 0; i < 8; i++)
                output.accept(new Vector3f((i & 1) == 0 ? mesh.min[0] : mesh.max[0], (i & 2) == 0 ? mesh.min[1] : mesh.max[1], (i & 4) == 0 ? mesh.min[2] : mesh.max[2]).mul(3.0f));
        }

        @Override
        public @Nullable ItemDisplayContext extractArgument(ItemStack stack) { return null; }
    }

    private record Unbaked(String mesh, boolean gun) implements SpecialModelRenderer.Unbaked<ItemDisplayContext> {
        static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(Codec.STRING.fieldOf("mesh").forGetter(Unbaked::mesh),
                Codec.BOOL.optionalFieldOf("gun", false).forGetter(Unbaked::gun)).apply(i, Unbaked::new));

        @Override
        public @Nullable SpecialModelRenderer<ItemDisplayContext> bake(SpecialModelRenderer.BakingContext context) {
            Mesh loaded = load(mesh);
            if (loaded == null) return null;
            return new Renderer(loaded, gun, RenderTypes.entityCutout(Identifier.fromNamespaceAndPath(NAMESPACE, "textures/item/pzmodel/" + this.mesh + ".png")));
        }

        @Override
        public MapCodec<? extends SpecialModelRenderer.Unbaked<ItemDisplayContext>> type() { return MAP_CODEC; }
    }
}
