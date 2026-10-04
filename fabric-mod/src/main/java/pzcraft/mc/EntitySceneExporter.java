package pzcraft.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;
import pzcraft.protocol.EntityLink;

/**
 * Every Minecraft frame, runs Steve, the entities and block entities near him and the particles in view through
 * Minecraft's own renderers into an {@link EntityCapture} and publishes the triangles for PZ, which draws them in
 * Viewpoint's scene (lit, shadowed, occluded by PZ walls and Minecraft blocks alike). Textures the geometry samples are
 * read back from the GPU once and published alongside.
 */
public final class EntitySceneExporter {
    private static final double RANGE = 48.0;
    private static EntityLink link;
    private static ByteBuffer vertices;
    private static EntityCapture capture;
    private static boolean failed;
    private static int failures;
    private static long frames, triangles;

    /** Texture identity -> slot, and which GPU texture object each slot was read from (a reload replaces them). */
    private static final Map<Identifier, Integer> slots = new HashMap<>();
    private static final Map<Integer, GpuTexture> sourceOf = new HashMap<>();
    private static final Map<GpuTexture, Boolean> pending = new IdentityHashMap<>();
    private static int nextSlot;
    private static net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer hands;
    private static java.lang.reflect.Method bobView,bobHurt;

    private EntitySceneExporter() {}
    public static boolean isNativeCollector(net.minecraft.client.renderer.SubmitNodeCollector collector){return collector==capture;}

    /** Render thread, end of each Minecraft frame, only while PZ draws the world natively. */
    static void frame(Minecraft mc, boolean thirdPerson,pzcraft.protocol.PlayerState poseState) {
        if (failed) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        try {
            if (link == null) {
                link = EntityLink.open(true);
                vertices = MemoryUtil.memAlloc(EntityLink.MAX_VERTICES * EntityLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                capture = new EntityCapture(vertices, id -> slotFor(mc, id));
            }
            float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            int ox = Mth.floor(player.getX()), oy = Mth.floor(player.getY()), oz = Mth.floor(player.getZ());
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            capture.clear();

            // Steve: in first person the camera sits inside his head, so his body only casts a shadow.
            capture.flags = EntityLink.FLAG_PLAYER | (thirdPerson ? 0 : EntityLink.FLAG_SHADOW_ONLY);
            submit(dispatcher, camera, player, pt, ox, oy, oz);
            capture.flags = 0;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == player || ActorProxies.isProxy(e) || e.isInvisible() || e.distanceToSqr(player) > RANGE * RANGE) continue;
                submit(dispatcher, camera, e, pt, ox, oy, oz);
            }
            // Beds, chests, animated pistons and other block entities use their vanilla renderers too.
            var blockDispatcher = mc.getBlockEntityRenderDispatcher();
            int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
            for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) {
                var chunk = mc.level.getChunkSource().getChunk(cx+dx,cz+dz,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
                if (chunk == null) continue;
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity.getBlockPos().distSqr(player.blockPosition()) > RANGE*RANGE) continue;
                    var state = blockDispatcher.tryExtractRenderState(blockEntity,pt,null,false);
                    if (state == null) continue;
                    PoseStack pose = new PoseStack();
                    var pos = blockEntity.getBlockPos();
                    pose.translate(pos.getX()-ox,pos.getY()-oy,pos.getZ()-oz);
                    blockDispatcher.submit(state,pose,capture,camera);
                }
            }
            // Particles: drawn natively they are hidden behind PZ walls and Minecraft blocks like everything else
            // (ParticlesSubmitMixin keeps them out of the video layer). They come relative to the camera.
            var levelState = mc.gameRenderer.gameRenderState().levelRenderState;
            var cam = levelState.cameraRenderState.pos;
            capture.shift((float) (cam.x - ox), (float) (cam.y - oy), (float) (cam.z - oz));
            levelState.particlesRenderState.submit(capture, camera);
            capture.shift(0, 0, 0);
            for(var breaking : levelState.blockBreakingRenderStates) {
                var state=breaking.blockState();
                if(state.getRenderShape()!=net.minecraft.world.level.block.RenderShape.MODEL)continue;
                var pos=breaking.blockPos();
                PoseStack pose=new PoseStack();pose.translate(pos.getX()-ox,pos.getY()-oy,pos.getZ()-oz);
                pose.translate(state.getOffset(pos));
                var parts=new java.util.ArrayList<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>();
                var model=mc.getModelManager().getBlockStateModelSet().get(state);
                var random=net.minecraft.util.RandomSource.create(state.getSeed(pos));model.collectParts(random,parts);
                capture.submitBreakingBlockModel(pose,parts,breaking.progress(),model.hasMaterialFlag(1));
            }
            if(!thirdPerson && !mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden && mc.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SPECTATOR) {
                var playerState=levelState.playerRenderState;
                if(playerState.firstPersonHandsAndItems!=null && !camera.entityRenderState.isSleeping) {
                    if(hands==null)hands=new net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer(mc);
                    PoseStack pose=new PoseStack();
                    if(bobHurt==null) {
                        Class<?> type=mc.gameRenderer.getClass();
                        bobHurt=type.getDeclaredMethod("bobHurt",CameraRenderState.class,PoseStack.class);bobHurt.setAccessible(true);
                        bobView=type.getDeclaredMethod("bobView",CameraRenderState.class,PoseStack.class);bobView.setAccessible(true);
                    }
                    bobHurt.invoke(mc.gameRenderer,camera,pose);
                    if(mc.gameRenderer.gameRenderState().optionsRenderState.bobView)bobView.invoke(mc.gameRenderer,camera,pose);
                    capture.flags=EntityLink.FLAG_HAND|EntityLink.FLAG_NO_SHADOW;
                    if (PzGuns.held(mc.player.getMainHandItem())) GunFeel.sway(pose);
                    Object handKey=java.util.List.of("hand",mc.player.getMainHandItem().getItem(),mc.player.getOffhandItem().getItem());
                    int handBefore=capture.vertexCount();
                    capture.tracing=!reported.contains(handKey);
                    capture.takeTrace();
                    hands.submitHandsWithItems(camera.cameraEntityPartialTicks,pose,capture,playerState,playerState.firstPersonHandsAndItems);
                    // Animation libraries submit their first-person arms through the entity renderer, not the vanilla
                    // hand renderer. Capture that separate pass in camera space so it shares the native hand projection.
                    currentPlayerState = playerState;
                    if (Tuning.get("gunHands.camera", 0) > 0 && PzGuns.held(mc.player.getMainHandItem())) gunHands(mc, capture, pose, playerState);
                    EntityRenderState animatedState = dispatcher.extractEntity(player, pt);
                    if (BetterCombatCompat.beginFirstPerson(animatedState)) {
                        try {
                            PoseStack animatedPose = new PoseStack();
                            // These bones are posed around the full body's shoulder pivot. Bring the arm pass in front of the eye
                            // at hand scale (found by looking: 0.15 / -0.8 / 0.9 keeps both arms and the weapon close and in frame;
                            // the older 0.35 / -1.25 / 0.7 left them small and far away).
                            animatedPose.translate(Tuning.get("bcArms.tx", 0), Tuning.get("bcArms.ty", 0.15), Tuning.get("bcArms.tz", -0.8));
                            float bcScale = (float) Tuning.get("bcArms.scale", 0.9);
                            animatedPose.scale(bcScale, bcScale, bcScale);
                            animatedPose.mulPose(camera.viewRotationMatrix);
                            dispatcher.submit(animatedState, camera, animatedState.x - cam.x, animatedState.y - cam.y,
                                    animatedState.z - cam.z, animatedPose, capture);
                        } finally {
                            BetterCombatCompat.endFirstPerson(animatedState);
                        }
                    }
                    capture.tracing=false;
                    capture.flags=0;
                    if(reported.add(handKey)) {
                        PzCraftClient.LOG.info("first-person hands holding {} / {}: {} triangles (calls {}) {}",
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()),
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getOffhandItem().getItem()),
                                (capture.vertexCount()-handBefore)/3, capture.takeTrace(), bounds(handBefore,capture.vertexCount()-handBefore));
                    }
                }
            }
            GunFeel.submitTracers(capture, new net.minecraft.world.phys.Vec3(cam.x, cam.y, cam.z), ox, oy, oz);
            var batches = capture.batches();
            vertices.flip();
            link.writeFrame(ox, oy, oz, batches, vertices,poseState,camera.hudFov);
            frames++;
            triangles += capture.vertexCount() / 3;
            if (frames % 600 == 1) {
                PzCraftClient.LOG.info("native entities: {} triangles in {} batches, {} textures", capture.vertexCount() / 3, batches.size(), slots.size());
            }
        } catch (Throwable t) {
            if (++failures >= 3) failed = true;
            PzCraftClient.LOG.error("native entity export failed ({}/3)", failures, t);
        }
    }

    private static java.lang.reflect.Method renderPlayerHand;
    private static net.minecraft.client.renderer.state.level.PlayerRenderState currentPlayerState;

    /**
     * One of Steve's arms (his skin, the sleeve), drawn with the pose stack as it is: the gun model's arm bones call this at
     * their own animated pivot, so the hands follow the draw, fire and reload clips. First-person capture only.
     */
    static void drawArm(PoseStack ps, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, net.minecraft.world.entity.HumanoidArm arm) {
        if (hands == null || currentPlayerState == null || capture == null) return;
        try {
            if (renderPlayerHand == null) {
                renderPlayerHand = net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class.getDeclaredMethod("renderPlayerHand",
                        PoseStack.class, net.minecraft.client.renderer.SubmitNodeCollector.class, int.class, net.minecraft.world.entity.HumanoidArm.class,
                        net.minecraft.client.renderer.state.level.PlayerRenderState.class);
                renderPlayerHand.setAccessible(true);
            }
            renderPlayerHand.invoke(hands, ps, collector, light, arm, currentPlayerState);
        } catch (ReflectiveOperationException e) {
            PzCraftClient.LOG.warn("could not draw the first-person arm: {}", e.toString());
        }
    }

    /**
     * Hands on the pistol in first person. Vanilla draws no arm for a held item, and the gun model's own arm bones are
     * placeholders for a skin the item renderer never supplies, so the two arms are drawn here with the vanilla arm renderer
     * (Steve's skin and sleeve) in camera space; where they sit is {@code gunHands.*} in the tuning file.
     */
    private static void gunHands(Minecraft mc, EntityCapture capture, PoseStack camera,
                                 net.minecraft.client.renderer.state.level.PlayerRenderState playerState) throws ReflectiveOperationException {
        if (hands == null) return;
        if (renderPlayerHand == null) {
            renderPlayerHand = net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class.getDeclaredMethod("renderPlayerHand",
                    PoseStack.class, net.minecraft.client.renderer.SubmitNodeCollector.class, int.class, net.minecraft.world.entity.HumanoidArm.class,
                    net.minecraft.client.renderer.state.level.PlayerRenderState.class);
            renderPlayerHand.setAccessible(true);
        }
        for (var arm : net.minecraft.world.entity.HumanoidArm.values()) {
            String side = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? "right" : "left";
            double sign = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
            PoseStack ps = new PoseStack();
            ps.mulPose(camera.last().pose());
            ps.translate(Tuning.get("gunHands." + side + ".x", sign * 0.30), Tuning.get("gunHands." + side + ".y", -0.55), Tuning.get("gunHands." + side + ".z", -0.55));
            ps.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get("gunHands." + side + ".ry", -sign * 10));
            ps.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get("gunHands." + side + ".rx", -75));
            ps.rotateDegrees(com.mojang.math.Axis.ZP, (float) Tuning.get("gunHands." + side + ".rz", 0));
            float scale = (float) Tuning.get("gunHands.scale", 1.0);
            ps.scale(scale, scale, scale);
            renderPlayerHand.invoke(hands, ps, capture, 15728880, arm, playerState);
        }
    }

    private static <E extends Entity> void submit(EntityRenderDispatcher dispatcher, CameraRenderState camera, E entity, float pt,
                                                  int ox, int oy, int oz) {
        EntityRenderState state = dispatcher.extractEntity(entity, pt);
        PoseStack pose = new PoseStack();
        int before = capture.vertexCount();
        // one line per entity type; the player once per item in its hands (what its renderer submits changes with the item)
        Object key = entity instanceof net.minecraft.world.entity.LivingEntity living
                ? java.util.List.of(entity.getType(), living.getMainHandItem().getItem(), living.getOffhandItem().getItem()) : entity.getType();
        boolean mine = entity == Minecraft.getInstance().player;
        capture.tracing = mine || !reported.contains(key);
        capture.takeTrace();
        dispatcher.submit(state, camera, state.x - ox, state.y - oy, state.z - oz, pose, capture);
        capture.tracing = false;
        var type = entity.getType();
        int made = capture.vertexCount() - before;
        String calls = capture.takeTrace();
        if (mine) { // what Steve's renderer submits changes while he swings: log every change (a few hundred lines at most)
            String sig = made / 3 + ":" + calls;
            if (!sig.equals(lastPlayerSignature) && playerChanges++ < 400) {
                PzCraftClient.LOG.info("steve capture: {} triangles, calls {}, holding {}", made / 3, calls,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(((net.minecraft.world.entity.LivingEntity) entity).getMainHandItem().getItem()));
            }
            lastPlayerSignature = sig;
        }
        if (made > 0 ? reported.add(key) : quiet.add(key)) {
            PzCraftClient.LOG.info("native entity {}{}: {} triangles ({} as {}; calls {}) at {} {}", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type),
                    entity instanceof net.minecraft.world.entity.LivingEntity living ? " holding " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(living.getMainHandItem().getItem()) + " / "
                            + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(living.getOffhandItem().getItem()) : "",
                    made / 3, state.getClass().getSimpleName(), dispatcher.getRenderer(state).getClass().getSimpleName(),
                    calls, String.format("(%.2f, %.2f, %.2f)", state.x - ox, state.y - oy, state.z - oz), bounds(before, made));
        }
    }

    private static String lastPlayerSignature = "";
    private static int playerChanges;

    /** Diagnostics: the box around vertices [first, first + count) of this frame, relative to the export origin. */
    private static String bounds(int first, int count) {
        if (count <= 0) return "";
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int v = first; v < first + count; v++) {
            for (int a = 0; a < 3; a++) {
                float f = vertices.getFloat((v * EntityLink.VERTEX_FLOATS + a) * 4);
                lo[a] = Math.min(lo[a], f);
                hi[a] = Math.max(hi[a], f);
            }
        }
        return String.format("box (%.2f, %.2f, %.2f)..(%.2f, %.2f, %.2f)", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    private static final java.util.Set<Object> quiet = new java.util.HashSet<>();

    /** Entity types already described in the log (one line each, to see which renderers produce geometry). */
    private static final java.util.Set<Object> reported = new java.util.HashSet<>();

    /** The slot a texture is published in; the first request starts its GPU read-back. */
    private static int slotFor(Minecraft mc, Identifier id) {
        Integer slot = slots.get(id);
        if (id.equals(EntityCapture.WHITE)) {
            // Not a Minecraft texture at all (asking the texture manager would try to load it): one white pixel.
            if (slot != null) return slot;
            slot = newSlot(id);
            ByteBuffer white = ByteBuffer.allocateDirect(4);
            white.putInt(0, -1);
            link.writeTexture(slot, EntityLink.KIND_PIXELS, 1, 1, white);
            return slot;
        }
        AbstractTexture texture = mc.getTextureManager().getTexture(id);
        GpuTexture gpu = texture == null ? null : texture.getTexture();
        if (slot != null && (gpu == null || sourceOf.get(slot) == gpu)) return slot;
        if (slot == null) slot = newSlot(id);
        if (id.equals(TextureAtlas.LOCATION_BLOCKS)) {
            link.writeTexture(slot, EntityLink.KIND_BLOCK_ATLAS, 0, 0, null);
            sourceOf.put(slot, gpu);
        } else if (gpu != null) {
            readBack(slot, gpu, id);
        }
        return slot;
    }

    private static int newSlot(Identifier id) {
        if (nextSlot >= EntityLink.TEXTURE_SLOTS) {
            // Out of slots (a resource reload, many skins): start over; this frame's batches simply wait a frame.
            link.resetTextures();
            slots.clear();
            sourceOf.clear();
            nextSlot = 0;
        }
        int slot = nextSlot++;
        slots.put(id, slot);
        return slot;
    }

    private static void readBack(int slot, GpuTexture gpu, Identifier id) {
        if (pending.containsKey(gpu)) return;
        int w = gpu.getWidth(0), h = gpu.getHeight(0);
        if (gpu.getFormat().blockSize() != 4 || (long) w * h * 4 > link.textureBytesFree()) {
            PzCraftClient.LOG.warn("entity texture {} ({}x{}) cannot be published: format {}, needs {} bytes, {} bytes free",
                    id, w, h, gpu.getFormat(), (long) w * h * 4, link.textureBytesFree());
            sourceOf.put(slot, gpu);
            return;
        }
        pending.put(gpu, Boolean.TRUE);
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "pzcraft entity texture", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpu, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
                link.writeTexture(slot, EntityLink.KIND_PIXELS, w, h, view.data());
                sourceOf.put(slot, gpu);
                PzCraftClient.LOG.info("entity texture {} -> slot {} ({}x{})", id, slot, w, h);
            } catch (Throwable t) {
                PzCraftClient.LOG.error("entity texture read-back failed for {}", id, t);
            } finally {
                buffer.close();
                pending.remove(gpu);
            }
        }, 0);
    }
}

