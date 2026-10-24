package pzcraft.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
//#if MC_26_1
//$ import com.mojang.blaze3d.buffers.GpuBuffer;
//$ import com.mojang.blaze3d.textures.GpuTexture;
//#else
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
//#endif
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
    //#if MC_26_1
    //$ /** The first-person pass ran at least once (the gun model's arm bones may draw Steve's arms). */
    //$ private static boolean handsReady;
    //#else
    private static net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer hands;
    //#endif
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
            //#if MC_26_1
            //$ CameraRenderState camera = mc.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
            //#else
            CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            //#endif
            capture.clear();

            // Steve: in first person the camera sits inside his head, so his body only casts a shadow.
            capture.flags = EntityLink.FLAG_PLAYER | (thirdPerson ? 0 : EntityLink.FLAG_SHADOW_ONLY);
            submit(dispatcher, camera, player, pt, ox, oy, oz);
            capture.flags = 0;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == player || e.distanceToSqr(player) > RANGE * RANGE) continue;
                // PZ draws its zombies (their stand-ins are invisible): of those, and of other invisible entities, only the
                // flames, when they burn (PzFire keeps a stand-in on fire while PZ's zombie burns)
                if ((ActorProxies.isProxy(e) || e.isInvisible()) && !e.displayFireAnimation()) continue;
                submit(dispatcher, camera, e, pt, ox, oy, oz, ActorProxies.isProxy(e));
            }
            // Beds, chests, animated pistons and other block entities use their vanilla renderers too.
            var blockDispatcher = mc.getBlockEntityRenderDispatcher();
            int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
            for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) {
                var chunk = mc.level.getChunkSource().getChunk(cx+dx,cz+dz,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
                if (chunk == null) continue;
                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity.getBlockPos().distSqr(player.blockPosition()) > RANGE*RANGE) continue;
                    //#if MC_26_1
                    //$ var state = blockDispatcher.tryExtractRenderState(blockEntity,pt,null);
                    //#else
                    var state = blockDispatcher.tryExtractRenderState(blockEntity,pt,null,false);
                    //#endif
                    if (state == null) continue;
                    PoseStack pose = new PoseStack();
                    var pos = blockEntity.getBlockPos();
                    pose.translate(pos.getX()-ox,pos.getY()-oy,pos.getZ()-oz);
                    blockDispatcher.submit(state,pose,capture,camera);
                }
            }
            // Particles: drawn natively they are hidden behind PZ walls and Minecraft blocks like everything else
            // (ParticlesSubmitMixin keeps them out of the video layer). They come relative to the camera.
            //#if MC_26_1
            //$ var levelState = mc.gameRenderer.getGameRenderState().levelRenderState;
            //$ var cam = levelState.cameraRenderState.pos;
            //$ capture.shift((float) (cam.x - ox), (float) (cam.y - oy), (float) (cam.z - oz));
            //$ for (var group : levelState.particlesRenderState.particles) group.submit(capture, camera);
            //#else
            var levelState = mc.gameRenderer.gameRenderState().levelRenderState;
            var cam = levelState.cameraRenderState.pos;
            capture.shift((float) (cam.x - ox), (float) (cam.y - oy), (float) (cam.z - oz));
            levelState.particlesRenderState.submit(capture, camera);
            //#endif
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
            //#if MC_26_1
            //$ if(!thirdPerson && !mc.gameRenderer.getGameRenderState().optionsRenderState.hideGui && mc.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SPECTATOR) {
            //$     if(!camera.entityRenderState.isSleeping) {
            //$         handsReady=true;
            //#else
            if(!thirdPerson && !mc.gameRenderer.gameRenderState().guiRenderState.isHudHidden && mc.gameMode.getPlayerMode()!=net.minecraft.world.level.GameType.SPECTATOR) {
                var playerState=levelState.playerRenderState;
                if(playerState.firstPersonHandsAndItems!=null && !camera.entityRenderState.isSleeping) {
                    if(hands==null)hands=new net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer(mc);
            //#endif
                    PoseStack pose=new PoseStack();
                    if(bobHurt==null) {
                        Class<?> type=mc.gameRenderer.getClass();
                        bobHurt=type.getDeclaredMethod("bobHurt",CameraRenderState.class,PoseStack.class);bobHurt.setAccessible(true);
                        bobView=type.getDeclaredMethod("bobView",CameraRenderState.class,PoseStack.class);bobView.setAccessible(true);
                    }
                    bobHurt.invoke(mc.gameRenderer,camera,pose);
                    //#if MC_26_1
                    //$ if(mc.gameRenderer.getGameRenderState().optionsRenderState.bobView)bobView.invoke(mc.gameRenderer,camera,pose);
                    //#else
                    if(mc.gameRenderer.gameRenderState().optionsRenderState.bobView)bobView.invoke(mc.gameRenderer,camera,pose);
                    //#endif
                    capture.flags=EntityLink.FLAG_HAND|EntityLink.FLAG_NO_SHADOW;
                    if (PzGuns.held(mc.player.getMainHandItem())) GunFeel.sway(pose);
                    Object handKey=java.util.List.of("hand",mc.player.getMainHandItem().getItem(),mc.player.getOffhandItem().getItem());
                    int handBefore=capture.vertexCount();
                    capture.tracing=!reported.contains(handKey);
                    capture.takeTrace();
                    // a rigged model has both arms on its own bones (a magazine's left hand feeds the rounds the off-hand holds):
                    // no vanilla off-hand arm and item over them
                    //#if MC_26_1
                    //$ // (26.1.2 picks the hands to draw itself: a rigged gun's vanilla off-hand is not suppressed here)
                    //$ mc.gameRenderer.itemInHandRenderer.renderHandsWithItems(pt,pose,capture,mc.player,dispatcher.getPackedLightCoords(mc.player,pt));
                    //$ var heldGun = mc.player.getMainHandItem();
                    //$ if (PzGuns.held(heldGun) && Tuning.get("gunHands.camera", 0) > 0) gunHands(mc, capture, pose);
                    //#else
                    if (PzGuns.held(mc.player.getMainHandItem()) || GunModels.kind(mc.player.getMainHandItem()) != null) playerState.firstPersonHandsAndItems.handRenderSelection =
                            net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState.HandRenderSelection.RENDER_MAIN_HAND_ONLY;
                    currentPlayerState = playerState;
                    hands.submitHandsWithItems(camera.cameraEntityPartialTicks,pose,capture,playerState,playerState.firstPersonHandsAndItems);
                    // Animation libraries submit their first-person arms through the entity renderer, not the vanilla
                    // hand renderer. Capture that separate pass in camera space so it shares the native hand projection.
                    currentPlayerState = playerState;
                    // the M9 puts Steve's arms on its own bones; the guns drawn from PZ's meshes get the two arms in camera space
                    var heldGun = mc.player.getMainHandItem();
                    if (PzGuns.held(heldGun) && Tuning.get("gunHands.camera", 0) > 0) gunHands(mc, capture, pose, playerState);
                    //#endif
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
                        PzCraftMain.LOG.info("first-person hands holding {} / {}: {} triangles (calls {}) {}",
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()),
                                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(mc.player.getOffhandItem().getItem()),
                                (capture.vertexCount()-handBefore)/3, capture.takeTrace(), bounds(handBefore,capture.vertexCount()-handBefore));
                    }
                }
            }
            GunFeel.submitTracers(capture, new net.minecraft.world.phys.Vec3(cam.x, cam.y, cam.z), ox, oy, oz);
            GunImpacts.submit(capture, new net.minecraft.world.phys.Vec3(cam.x, cam.y, cam.z), ox, oy, oz);
            var batches = capture.batches();
            vertices.flip();
            link.writeFrame(ox, oy, oz, batches, vertices,poseState,camera.hudFov);
            frames++;
            triangles += capture.vertexCount() / 3;
            if (frames % 600 == 1) {
                PzCraftMain.LOG.info("native entities: {} triangles in {} batches, {} textures", capture.vertexCount() / 3, batches.size(), slots.size());
            }
        } catch (Throwable t) {
            if (++failures >= 3) failed = true;
            PzCraftMain.LOG.error("native entity export failed ({}/3)", failures, t);
        }
    }

    //#if MC_26_1
    //$ /** One of Steve's arms (his skin, the sleeve) at the pose stack as it is; see the 26.3 version. */
    //$ static void drawArm(PoseStack ps, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, net.minecraft.world.entity.HumanoidArm arm) {
    //$     if (!handsReady || capture == null) return;
    //$     playerHand(ps, collector, light, arm);
    //$ }
    //$
    //$ /** ItemInHandRenderer.renderPlayerArm of 26.1.2 without its swing transform. */
    //$ private static void playerHand(PoseStack ps, net.minecraft.client.renderer.SubmitNodeCollector collector, int light, net.minecraft.world.entity.HumanoidArm arm) {
    //$     var player = Minecraft.getInstance().player;
    //$     if (player == null) return;
    //$     var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player);
    //$     Identifier skin = player.getSkin().body().texturePath();
    //$     if (arm == net.minecraft.world.entity.HumanoidArm.RIGHT) renderer.renderRightHand(ps, collector, light, skin,
    //$             player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.RIGHT_SLEEVE));
    //$     else renderer.renderLeftHand(ps, collector, light, skin, player.isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart.LEFT_SLEEVE));
    //$ }
    //#else
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
            PzCraftMain.LOG.warn("could not draw the first-person arm: {}", e.toString());
        }
    }
    //#endif

    static net.minecraft.client.model.geom.ModelPart armModel(net.minecraft.world.entity.HumanoidArm arm) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        //#if MC_26_1
        //$ var model = mc.getEntityRenderDispatcher().getPlayerRenderer(mc.player).getModel();
        //#else
        if (currentPlayerState == null || currentPlayerState.avatarRenderState == null) return null;
        var model = mc.getEntityRenderDispatcher().getRenderer(currentPlayerState.avatarRenderState).getModel();
        //#endif
        return arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
    }

    /**
     * Hands on the pistol in first person. Vanilla draws no arm for a held item, and the gun model's own arm bones are
     * placeholders for a skin the item renderer never supplies, so the two arms are drawn here with the vanilla arm renderer
     * (Steve's skin and sleeve) in camera space; where they sit is {@code gunHands.*} in the tuning file.
     */
    //#if MC_26_1
    //$ private static void gunHands(Minecraft mc, EntityCapture capture, PoseStack camera) {
    //#else
    private static void gunHands(Minecraft mc, EntityCapture capture, PoseStack camera,
                                 net.minecraft.client.renderer.state.level.PlayerRenderState playerState) throws ReflectiveOperationException {
        if (hands == null) return;
        if (renderPlayerHand == null) {
            renderPlayerHand = net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class.getDeclaredMethod("renderPlayerHand",
                    PoseStack.class, net.minecraft.client.renderer.SubmitNodeCollector.class, int.class, net.minecraft.world.entity.HumanoidArm.class,
                    net.minecraft.client.renderer.state.level.PlayerRenderState.class);
            renderPlayerHand.setAccessible(true);
        }
    //#endif
        for (var arm : net.minecraft.world.entity.HumanoidArm.values()) {
            String side = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? "right" : "left";
            double sign = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
            PoseStack ps = new PoseStack();
            ps.mulPose(camera.last().pose());
            ps.translate(Tuning.get("gunHands." + side + ".x", sign * 0.30), Tuning.get("gunHands." + side + ".y", -0.55), Tuning.get("gunHands." + side + ".z", -0.55));
            //#if MC_26_1
            //$ ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Tuning.get("gunHands." + side + ".ry", -sign * 10)));
            //$ ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Tuning.get("gunHands." + side + ".rx", -75)));
            //$ ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) Tuning.get("gunHands." + side + ".rz", 0)));
            //#else
            ps.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get("gunHands." + side + ".ry", -sign * 10));
            ps.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get("gunHands." + side + ".rx", -75));
            ps.rotateDegrees(com.mojang.math.Axis.ZP, (float) Tuning.get("gunHands." + side + ".rz", 0));
            //#endif
            float scale = (float) Tuning.get("gunHands.scale", 1.0);
            ps.scale(scale, scale, scale);
            //#if MC_26_1
            //$ playerHand(ps, capture, 15728880, arm);
            //#else
            renderPlayerHand.invoke(hands, ps, capture, 15728880, arm, playerState);
            //#endif
        }
    }

    private static <E extends Entity> void submit(EntityRenderDispatcher dispatcher, CameraRenderState camera, E entity, float pt,
                                                  int ox, int oy, int oz) {
        submit(dispatcher, camera, entity, pt, ox, oy, oz, false);
    }

    /** {@code flamesOnly}: a PZ zombie's stand-in, of which only the flames are drawn (PZ draws the zombie), never its model or shadow. */
    private static <E extends Entity> void submit(EntityRenderDispatcher dispatcher, CameraRenderState camera, E entity, float pt,
                                                  int ox, int oy, int oz, boolean flamesOnly) {
        EntityRenderState state = dispatcher.extractEntity(entity, pt);
        if (entity == Minecraft.getInstance().player && PzCars.seat.inCar()
                && state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState rider) {
            rider.isPassenger = true;
            rider.bodyRot = PzCars.seat.yawDeg();
            rider.yRot = Mth.clamp(Mth.wrapDegrees(entity.getYRot() - rider.bodyRot), -85f, 85f);
            rider.walkAnimationSpeed = 0;
            // Minecraft bends a passenger's legs but leaves his torso at standing height.
            // Lower only the drawn body, keeping the simulation on PZ's storey for a safe dismount.
            rider.y -= 0.6;
        }
        if (flamesOnly) {
            state.isInvisible = true;
            if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) living.isInvisibleToPlayer = true;
            state.nameTag = null;
        }
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
                PzCraftMain.LOG.info("steve capture: {} triangles, calls {}, holding {}", made / 3, calls,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(((net.minecraft.world.entity.LivingEntity) entity).getMainHandItem().getItem()));
            }
            lastPlayerSignature = sig;
        }
        if (made > 0 ? reported.add(key) : quiet.add(key)) {
            PzCraftMain.LOG.info("native entity {}{}: {} triangles ({} as {}; calls {}) at {} {}", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type),
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

    /**
     * RGBA8 pixels in rows of {@code stride}, the top-left {@code ow*f x oh*f} box-filtered by {@code f} (a power of two) to
     * {@code ow x oh}; colour weighted by alpha so cut-out edges do not darken.
     */
    private static ByteBuffer shrink(ByteBuffer src, int stride, int ow, int oh, int f) {
        int n = f * f, base = src.position();
        ByteBuffer out = ByteBuffer.allocateDirect(ow * oh * 4);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                long r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < f; dy++) {
                    int row = base + ((y * f + dy) * stride + x * f) * 4;
                    for (int dx = 0; dx < f; dx++) {
                        int i = row + dx * 4, pa = src.get(i + 3) & 255;
                        r += (long) (src.get(i) & 255) * pa;
                        g += (long) (src.get(i + 1) & 255) * pa;
                        b += (long) (src.get(i + 2) & 255) * pa;
                        a += pa;
                    }
                }
                int o = (y * ow + x) * 4;
                if (a > 0) {
                    out.put(o, (byte) (r / a));
                    out.put(o + 1, (byte) (g / a));
                    out.put(o + 2, (byte) (b / a));
                }
                out.put(o + 3, (byte) (a / n));
            }
        }
        return out;
    }

    /**
     * The used part of RGBA8 pixels {@code w x h}: {width, height} of the smallest top-left box outside which every pixel is 0
     * (at least 1x1). An atlas is only filled as far as its sprites go; the rest need not cross (PZ fills it with 0).
     */
    private static int[] usedExtent(ByteBuffer src, int w, int h) {
        int base = src.position(), usedW = 0, usedH = 0;
        for (int y = 0; y < h; y++) {
            int row = base + y * w * 4, x = w;
            // From the right: the last non-zero pixel of this row, if it is right of the widest so far.
            while (x > usedW && x >= 2 && src.getLong(row + (x - 2) * 4) == 0) x -= 2;
            while (x > usedW && src.getInt(row + (x - 1) * 4) == 0) x--;
            if (x > usedW) {
                usedW = x;
                usedH = y + 1;
            } else if (usedW > 0 && rowUsed(src, row, usedW)) usedH = y + 1;
        }
        return new int[] {Math.max(1, usedW), Math.max(1, usedH)};
    }

    /** Whether any of the first {@code n} pixels of the row at {@code row} is not 0. */
    private static boolean rowUsed(ByteBuffer src, int row, int n) {
        int x = 0;
        for (; x + 2 <= n; x += 2) if (src.getLong(row + x * 4) != 0) return true;
        return x < n && src.getInt(row + x * 4) != 0;
    }

    /** Counts {@link #restartTextures}: a read-back that finishes after one belongs to slots that no longer exist. */
    private static int textureGeneration;
    private static long lastTextureRestart;

    /** Forget every published texture; each is published again when next drawn (this frame's batches wait a frame). */
    private static void restartTextures() {
        link.resetTextures();
        slots.clear();
        sourceOf.clear();
        nextSlot = 0;
        textureGeneration++;
        lastTextureRestart = System.nanoTime();
    }

    private static int newSlot(Identifier id) {
        // Out of slots (a resource reload, many skins): start over.
        if (nextSlot >= EntityLink.TEXTURE_SLOTS) restartTextures();
        int slot = nextSlot++;
        slots.put(id, slot);
        return slot;
    }

    /**
     * Textures bigger than this go to PZ at half size (or less); their UVs are relative, so a smaller copy maps the same. The item
     * atlas with PZ's own items is 8192x4096, 128 MiB; with a large additional item set it is 8192x8192, 256 MiB. Both go at full
     * size (halving drew Minecraft's 16-pixel items with 8), and only their used part crosses. Only a used part bigger still
     * (an atlas a resource pack fills further) is shrunk.
     */
    private static final long BIG_TEXTURE_BYTES = 256L * 1024 * 1024;

    private static void readBack(int slot, GpuTexture gpu, Identifier id) {
        if (pending.containsKey(gpu)) return;
        int w = gpu.getWidth(0), h = gpu.getHeight(0);
        //#if MC_26_1
        //$ if (gpu.getFormat().pixelSize() != 4) {
        //#else
        if (gpu.getFormat().blockSize() != 4) {
        //#endif
            PzCraftMain.LOG.warn("entity texture {} ({}x{}) cannot be published: format {}", id, w, h, gpu.getFormat());
            sourceOf.put(slot, gpu);
            return;
        }
        pending.put(gpu, Boolean.TRUE);
        int generation = textureGeneration;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "pzcraft entity texture", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpu, buffer, 0L, () -> {
            //#if MC_26_1
            //$ try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)) {
            //#else
            try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
            //#endif
                if (generation != textureGeneration) return;
                publish(slot, gpu, id, view.data(), w, h);
            } catch (Throwable t) {
                PzCraftMain.LOG.error("entity texture read-back failed for {}", id, t);
            } finally {
                buffer.close();
                pending.remove(gpu);
            }
        }, 0);
    }

    /** Publish read-back pixels {@code w x h}: their used part, at full size when it fits. */
    private static void publish(int slot, GpuTexture gpu, Identifier id, ByteBuffer data, int w, int h) {
        int[] used = usedExtent(data, w, h);
        int uw = used[0], uh = used[1];
        long need = (long) uw * uh * 4;
        // The space is only ever appended to, so a texture Minecraft rebuilt (the item atlas, once PZ's items are loaded)
        // takes room a second time. Start over when it would fit in an empty space, rather than shrink it; not more often
        // than every few seconds, so textures that cannot all fit at once settle on the shrinking below. Every texture,
        // this one too, is read back again when next drawn.
        if (need > link.textureBytesFree() && need <= Math.min(BIG_TEXTURE_BYTES, EntityLink.textureSpace())
                && System.nanoTime() - lastTextureRestart > 5_000_000_000L) {
            PzCraftMain.LOG.info("entity textures start over for {} ({}x{}, used {}x{}): {} bytes free", id, w, h, uw, uh, link.textureBytesFree());
            restartTextures();
            return;
        }
        long room = Math.min(BIG_TEXTURE_BYTES, link.textureBytesFree());
        int f = 1;
        while ((long) Math.ceilDiv(uw, f) * Math.ceilDiv(uh, f) * 4 > room && w / f > 1 && h / f > 1) f *= 2;
        int fw = w / f, fh = h / f, pw = Math.min(Math.ceilDiv(uw, f), fw), ph = Math.min(Math.ceilDiv(uh, f), fh);
        if ((long) pw * ph * 4 > link.textureBytesFree()) {
            PzCraftMain.LOG.warn("entity texture {} ({}x{}) cannot be published: needs {} bytes, {} bytes free",
                    id, pw, ph, (long) pw * ph * 4, link.textureBytesFree());
            sourceOf.put(slot, gpu);
            return;
        }
        if (f == 1) link.writeTexture(slot, pw, ph, w, fw, fh, data);
        else link.writeTexture(slot, pw, ph, pw, fw, fh, shrink(data, w, pw, ph, f));
        sourceOf.put(slot, gpu);
        PzCraftMain.LOG.info("entity texture {} -> slot {} ({}x{}{}{})", id, slot, fw, fh,
                pw == fw && ph == fh ? "" : ", sent " + pw + "x" + ph, f == 1 ? "" : ", from " + w + "x" + h);
    }
}
