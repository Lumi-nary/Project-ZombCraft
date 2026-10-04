package pzcraft.mc;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.client.GeoRenderProvider;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.LoopType;
import com.geckolib.model.DefaultedItemGeoModel;
import com.geckolib.renderer.GeoItemRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.util.GeckoLibUtil;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Single-gun visual spike. Debug animation commands do not fire shots, consume ammunition or play imported sounds. */
final class M9Item extends Item implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static long heldId = Long.MIN_VALUE;
    private static boolean wasSprinting;
    /** The held pistol has no round in the chamber (slide back clips): from the mirror's gun state, kept by {@link #tick}. */
    private static volatile boolean emptyHeld;

    M9Item(Properties properties) {
        super(properties);
        GeoItem.registerSyncedAnimatable(this);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            var command = Commands.literal("pzgunanim");
            for (String clip : new String[] {"draw", "drawempty", "fire", "reload", "reloadempty", "run", "runningstart", "running", "runningend", "inspect"}) {
                command.then(Commands.literal(clip).executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    ItemStack stack = player.getMainHandItem();
                    if (stack.getItem() != this) return 0;
                    triggerAnim(player, GeoItem.getId(stack), "m9", clip);
                    PzCraftClient.LOG.info("M9 visual spike: {} (PZ item #{})", clip, PzItems.stateOf(stack).get("i"));
                    return 1;
                }));
            }
            dispatcher.register(command);
        });
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<M9Item>("m9", 0,
                test -> {
                    test.controller().setAnimationSpeed(1.0);
                    return test.setAndContinue(RawAnimation.begin().thenLoop(emptyHeld ? "animation.model.idleempty" : "animation.model.idle"));
                })
                // Source clips declare loop=true; thenPlay inherits that. Explicitly end action clips.
                .triggerableAnim("draw", RawAnimation.begin().then("animation.model.draw", LoopType.PLAY_ONCE))
                .triggerableAnim("drawempty", RawAnimation.begin().then("animation.model.drawempty", LoopType.PLAY_ONCE))
                .triggerableAnim("fire", RawAnimation.begin().then("animation.model.fire", LoopType.PLAY_ONCE))
                .triggerableAnim("reload", RawAnimation.begin().then("animation.model.reload", LoopType.PLAY_ONCE))
                .triggerableAnim("reloadempty", RawAnimation.begin().then("animation.model.reloadempty", LoopType.PLAY_ONCE))
                .triggerableAnim("run", RawAnimation.begin().then("animation.model.runningstart", LoopType.PLAY_ONCE).thenLoop("animation.model.running"))
                .triggerableAnim("runningstart", RawAnimation.begin().then("animation.model.runningstart", LoopType.PLAY_ONCE))
                .triggerableAnim("running", RawAnimation.begin().thenLoop("animation.model.running"))
                .triggerableAnim("runningend", RawAnimation.begin().then("animation.model.runningend", LoopType.PLAY_ONCE))
                .triggerableAnim("inspect", RawAnimation.begin().then("animation.model.inspect", LoopType.PLAY_ONCE))
                .setSoundKeyframeHandler(event -> {})); // Native PZ owns sounds; no imported sound instructions.
    }

    @Override public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private GeoItemRenderer<M9Item> renderer;
            @Override public GeoItemRenderer<M9Item> getGeoItemRenderer() {
                if (renderer == null) renderer = new GeoItemRenderer<M9Item>(new DefaultedItemGeoModel<>(Identifier.parse("pzcraft:m9"))) {
                    @Override public void adjustRenderPose(RenderPassInfo<GeoRenderState> pass) {
                        super.adjustRenderPose(pass);
                        var context = pass.getGeckolibData(com.geckolib.constant.DataTickets.ITEM_RENDER_PERSPECTIVE);
                        float aim = GunFeel.aim();
                        if (aim <= 0.001f || (context != net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                && context != net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND)) return;
                        // Aiming moves the sight frame to the camera's centre ray.
                        var ps = pass.poseStack();
                        pass.model().getBone("scope").ifPresent(scope -> {
                            // Align the sight's own coordinate frame with the camera ray. PZ's
                            // camera-space hand pass needs no world model-view transform here.
                            var scopePose = new com.mojang.blaze3d.vertex.PoseStack();
                            com.geckolib.util.RenderUtil.transformToBone(scopePose, scope);
                            var target = new org.joml.Matrix4f().translation((float) Tuning.get("ads.x", 0),
                                    (float) Tuning.get("ads.y", 0), (float) Tuning.get("ads.z", -0.7))
                                    .mul(new org.joml.Matrix4f(scopePose.last().pose()).invert());
                            var correction = new org.joml.Matrix4f(ps.last().pose()).invert().mul(target);
                            ps.mulPose(new org.joml.Matrix4f().lerp(correction, aim));
                        });
                    }
                    @Override public void adjustModelBonesForRender(RenderPassInfo<GeoRenderState> pass, BoneSnapshots bones) {
                        // PZ supplies gun state later; this spike shows the unmodified pistol, without attachments,
                        // a duplicate magazine or a permanently visible muzzle flash. The model's two arm bones are
                        // placeholders for a skin the item renderer never supplies: their cubes are never drawn; in first
                        // person Steve's own arm models are drawn at the bones instead (they follow the animation).
                        for (String bone : new String[] {"rightarm", "leftarm", "magazine2", "scope",
                                "_cb_scope", "_cb_suppressor", "_cb_stock", "sightmount"})
                            bones.ifPresent(bone, snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        // The source model reserves this bone for a separate textured muzzle effect.
                        bones.ifPresent("muzzleflash", snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        if (GunFeel.flashing()) pass.model().getBone("muzzleflash").ifPresent(flashBone -> pass.addPerBoneRender(flashBone, (p, b, collector) -> {
                            if (collector instanceof EntityCapture capture) {
                                p.poseStack().translate((float) Tuning.get("flash.x", 0), (float) Tuning.get("flash.y", 0), (float) Tuning.get("flash.z", 0));
                                capture.glowSprite(p.poseStack().last(), Identifier.parse("pzcraft:textures/effect/flashes.png"),
                                        (float) Tuning.get("flash.halfSize", 1.25), GunFeel.flashFrame(), 9, GunFeel.flashAlpha());
                            }
                        }));
                        var context = pass.getGeckolibData(com.geckolib.constant.DataTickets.ITEM_RENDER_PERSPECTIVE);
                        if (context != net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                && context != net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND) return;
                        pass.model().getBone("_shelleject_").ifPresent(ejectBone -> pass.addPerBoneRender(ejectBone, (p, b, collector) -> {
                            if (collector instanceof EntityCapture capture) GunShells.submit(p, capture);
                        }));
                        for (var arm : net.minecraft.world.entity.HumanoidArm.values()) {
                            String name = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? "rightarm" : "leftarm";
                            pass.model().getBone(name).ifPresent(geoBone -> pass.addPerBoneRender(geoBone, (p, b, collector) -> {
                                var ps = p.poseStack();
                                String side = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? "right" : "left";
                                double sign = arm == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
                                ps.translate((float) Tuning.get("armBone." + side + ".x", 0), (float) Tuning.get("armBone." + side + ".y", 0), (float) Tuning.get("armBone." + side + ".z", 0));
                                ps.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get("armBone." + side + ".rx", 0));
                                ps.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get("armBone." + side + ".ry", 0));
                                ps.rotateDegrees(com.mojang.math.Axis.ZP, (float) Tuning.get("armBone." + side + ".rz", 0));
                                float scale = (float) Tuning.get("armBone.scale", 2.0);
                                ps.scale(scale * (float) Tuning.get("armBone.flipX", -1), scale * (float) Tuning.get("armBone.flipY", -1), scale * (float) Tuning.get("armBone.flipZ", 1));
                                // vanilla's arm model sits (+-5, 2) pixels from its own shoulder pivot: put the shoulder on the bone's pivot
                                double compensate = Tuning.get("armBone.compensate", -1);
                                ps.translate((float) (-sign * 5 / 16.0 * compensate), (float) (-2 / 16.0 * compensate), 0f);
                                EntitySceneExporter.drawArm(ps, collector, p.packedLight(), arm);
                            }));
                        }
                    }
                };
                return renderer;
            }
        });
    }

    @SuppressWarnings("unchecked")
    static void identify(ItemStack stack, com.google.gson.JsonObject state) {
        if (state != null && state.has("i")) {
            var type = (DataComponentType<Long>) com.geckolib.GeckoLibConstants.STACK_ANIMATABLE_ID_COMPONENT.get();
            stack.set(type, state.get("i").getAsLong());
        }
    }

    static void tick(Minecraft mc) {
        ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof M9Item item)) { heldId = Long.MIN_VALUE; return; }
        long id = GeoItem.getId(stack);
        var gun = PzItems.stateOf(stack);
        emptyHeld = gun.has("g") && gun.getAsJsonObject("g").has("chamber") && !gun.getAsJsonObject("g").get("chamber").getAsBoolean();
        if (id != heldId) {
            heldId = id;
            wasSprinting = false;
            item.triggerAnim(mc.player, id, "m9", emptyHeld ? "drawempty" : "draw");
        }
        // sprinting carries the gun low and angled (Point Blank's clips): started and ended by triggers, which the controller honours
        boolean sprint = GunFeel.sprinting();
        if (sprint != wasSprinting) {
            wasSprinting = sprint;
            item.triggerAnim(mc.player, id, "m9", sprint ? "run" : "runningend");
        }
    }

    static java.util.Map<String, Object> animationStats() {
        var mc = Minecraft.getInstance();
        var stack = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof M9Item item)) return java.util.Map.of();
        var controller = item.cache.getManagerForId(GeoItem.getId(stack)).getAnimationControllers().get("m9");
        if (controller == null) return java.util.Map.of();
        var point = controller.getCurrentAnimationPoint();
        return java.util.Map.of("speed", controller.getAnimationSpeed(), "time", controller.getCurrentAnimationTime(),
                "clip", point == null ? "" : point.animation().name());
    }

    static void clip(Minecraft mc, String clip) { clip(mc, clip, 1.0); }

    static void clip(Minecraft mc, String clip, double pace) {
        ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (stack.getItem() instanceof M9Item item) {
            long id = GeoItem.getId(stack);
            var controller = item.cache.getManagerForId(id).getAnimationControllers().get("m9");
            // Native PZ scales reload sounds and duration by pace; scale this entire clip by the same clock.
            if (controller != null) controller.setAnimationSpeed(Double.isFinite(pace) && pace > 0 ? 1.0 / pace : 1.0);
            item.triggerAnim(mc.player, id, "m9", clip);
        }
    }
}

