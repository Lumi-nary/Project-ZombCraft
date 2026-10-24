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
import com.google.gson.JsonObject;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A PZ firearm drawn with one of the private gun models ({@link GunModels}): the model, Steve's own arms on its arm bones, the
 * muzzle flash and casings on their bones, the sights brought to the screen centre when aiming, and the clip that fits what PZ
 * just answered (a shot, a magazine change, rounds going in one by one, the action being worked, an inspection). Purely visual:
 * nothing here fires, loads or plays an imported sound.
 */
final class GunItem extends Item implements GeoItem {
    private static final String CONTROLLER = "gun", PREFIX = "animation.model.";
    /** Ticks a change of clip blends over by default (see {@link #blendTicks}). */
    private static final int BLEND_TICKS = 5;
    final GunModels.Kind kind;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // ---- the gun in the main hand (one at a time) ----
    private static long heldId = Long.MIN_VALUE;
    private static boolean wasSprinting;
    /** Ticks the sprint flag has disagreed with the pose so far: the pose changes only after {@link #SPRINT_HOLD_TICKS} of it. */
    private static int sprintHeld;
    private static final int SPRINT_HOLD_TICKS = 5;
    private static int finishedTicks;
    static volatile int idleResumed;
    /** The slide stands back (slide-back clips): nothing is left to fire because the last round was fired, or the gun was drawn
     *  like that. With rounds in the magazine the slide is shut and R racks it by hand; a magazine taken out of a gun whose
     *  chamber was already empty leaves it shut. And no magazine in the gun. From the mirror's gun state, kept by {@link #tick}. */
    private static volatile boolean emptyHeld, noMagazine;
    /** A round was in the chamber when the gun's state was last looked at. */
    private static boolean hadChamber;
    /** Rounds in the held gun (the chambered one included) or magazine, for the models that show them. */
    private static volatile int heldRounds;
    /** The count PZ gave with its last answer about rounds going in or out: the mirrored stack follows a few ticks later. -1: none. */
    private static int saidRounds = -1;
    private static long saidAt;
    /**
     * Rounds going in (or out) one at a time: PZ said when it started and how long one takes; ends when PZ says so. {@code clip}
     * is one round's clip and {@code mark} when in it the round is in (or out); 0 for a clip that ends at that moment.
     */
    private record Loading(long start, double per, double first, boolean all, String clip, double mark) {
        /** How long one round's clip is given: its own length, or less when PZ is faster than that. */
        double seconds(GunModels.Kind kind) { return Math.min(kind.length(clip), per * (mark > 0 ? .95 : .8)); }
        /** How long before PZ moves the round its clip has to start. */
        double lead(GunModels.Kind kind) { return mark > 0 ? mark * seconds(kind) / kind.length(clip) : seconds(kind); }
    }
    private static Loading loading;
    private static int loadsStarted;
    /** The finished clip's last frame is kept (the action is open while rounds go in). */
    private static boolean holdPose;
    /** The action is about to be worked by PZ: its "racked" answer needs no clip of its own. */
    private static boolean expectRacked;
    private static double rackAfterLoad;
    /** A clip to start when the running one ends. */
    private static String queuedClip;
    private static double queuedSpeed = 1;
    private static volatile String lastClip = "";
    /** The clip whose own bones are drawn (a round, a magazine): the clip on screen. A new clip's bones take effect only once its
     *  blend is done, so they do not jump ahead of the hand carrying them (see {@link #blendTicks}). */
    private static volatile String shownClip = "";
    /** Ticks left before {@link #shownClip} follows {@link #lastClip}. */
    private static int blendLeft;
    private static volatile int clipsPlayed;

    GunItem(Properties properties, GunModels.Kind kind) {
        super(properties);
        this.kind = kind;
        GeoItem.registerSyncedAnimatable(this);
        if (kind.id().equals("m9")) CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            // /pzgunanim <clip>: plays a clip of whatever gun is held (visual only)
            var command = Commands.literal("pzgunanim");
            for (String clip : new String[] {"draw", "drawempty", "fire", "reload", "reloadempty", "run", "runningstart", "running", "runningend",
                    "inspect", "inspectempty", "loadstart", "load", "loadend", "rack", "unload", "idle", "idleempty",
                    "idlenomag", "drawnomag", "reloadnomag", "reloademptynomag", "inspectnomag", "runnomag", "runningnomag", "racknomag",
                    "runempty", "runningstartempty", "runningempty", "runningendempty"}) {
                command.then(Commands.literal(clip).executes(ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.execute(() -> play(mc, clip, 1.0));
                    return 1;
                }));
            }
            dispatcher.register(command);
        });
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    private String idleClip() { return PREFIX + (emptyHeld && kind.has("idleempty") ? "idleempty" : fitting(kind, "idle")); }

    /** The held gun has no magazine in it (PZ's state of it, as of the last tick). */
    static boolean withoutMagazine() { return noMagazine; }

    /**
     * The clip for the gun as it is: a gun with no magazine in it has clips of its own ({@code idlenomag}, {@code drawnomag},
     * {@code reloadnomag}, {@code inspectnomag}, {@code runningnomag}, {@code racknomag}) where its kind has them. The
     * slide-back clips come first (an empty gun without a magazine is drawn with those, its magazine bones hidden).
     */
    private static String fitting(GunModels.Kind kind, String clip) { return noMagazine && kind.has(clip + "nomag") ? clip + "nomag" : clip; }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        var controller = new AnimationController<GunItem>(CONTROLLER, BLEND_TICKS, test -> {
            test.controller().setAnimationSpeed(1.0);
            return test.setAndContinue(RawAnimation.begin().thenLoop(idleClip()));
        });
        // Source clips declare loop=true; thenPlay inherits that. Explicitly end action clips.
        for (String clip : kind.clips().keySet()) {
            if (clip.equals("running") || clip.equals("runningnomag") || clip.equals("runningempty")) controller.triggerableAnim(clip, RawAnimation.begin().thenLoop(PREFIX + clip));
            else controller.triggerableAnim(clip, RawAnimation.begin().then(PREFIX + clip, LoopType.PLAY_ONCE));
        }
        // an empty gun is run with its slide back (runempty: the clips with the empty gun's pose in them)
        for (String run : new String[] {"run", "runnomag", "runempty"}) {
            String loop = runLoop(run), start = run.equals("runempty") && kind.has("runningstartempty") ? "runningstartempty" : "runningstart";
            if (kind.has(loop)) controller.triggerableAnim(run, kind.has(start)
                    ? RawAnimation.begin().then(PREFIX + start, LoopType.PLAY_ONCE).thenLoop(PREFIX + loop)
                    : RawAnimation.begin().thenLoop(PREFIX + loop));
        }
        controllers.add(controller.setSoundKeyframeHandler(event -> {})); // Native PZ owns sounds; no imported sound instructions.
    }

    /** The clip a run trigger loops. */
    private static String runLoop(String run) { return run.equals("run") ? "running" : run.equals("runnomag") ? "runningnomag" : "runningempty"; }

    @Override public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private GeoItemRenderer<GunItem> renderer;
            @Override public GeoItemRenderer<GunItem> getGeoItemRenderer() {
                if (renderer == null) renderer = new GeoItemRenderer<GunItem>(new DefaultedItemGeoModel<>(kind.model())) {
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
                        // The alignment above lands the sights on a fixed target and so removes the hip sway; the motion of
                        // walking while aimed is added afterwards, in camera space.
                        var adsSway = GunFeel.adsSway();
                        if (adsSway != null) ps.last().pose().mulLocal(adsSway);
                    }
                    @Override public void adjustModelBonesForRender(RenderPassInfo<GeoRenderState> pass, BoneSnapshots bones) {
                        // The bare gun: hide connector bones unless the matching attachment is equipped
                        // stay hidden. The two arm bones are placeholders for a skin the item renderer never supplies: their
                        // cubes are never drawn; in first person Steve's own arm models are drawn at the bones instead (they
                        // follow the animation). The muzzle-flash bone is reserved for the separate textured effect.
                        for (String bone : kind.hide()) bones.ifPresent(bone, snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        // a bone that belongs to one clip (the round in the hand while it goes in)
                        for (var only : kind.clipBones().entrySet())
                            if (!only.getValue().contains(shownClip)) bones.ifPresent(only.getKey(), snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        // the rounds the gun really holds (a revolver's cylinder); all of them while the empties are thrown out
                        if (!kind.roundBones().isEmpty() && !kind.roundsShowAll().contains(shownClip))
                            for (int i = Math.max(0, heldRounds); i < kind.roundBones().size(); i++)
                                bones.ifPresent(kind.roundBones().get(i), snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        // what is seen only when nothing is in it (an empty magazine's follower standing in its mouth)
                        if (heldRounds > 0) for (String bone : kind.emptyBones())
                            bones.ifPresent(bone, snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        // PZ can have a gun with no magazine in it (ejected, or dropped out empty); a reload clip brings one in and
                        // the unload clip is the hand taking it out
                        if (noMagazine && !shownClip.startsWith("reload") && !shownClip.equals("unload"))
                            for (String bone : new String[] {"magazine", "magazine2"}) bones.ifPresent(bone, snapshot -> snapshot.skipRender(true).skipChildrenRender(true));
                        var context = pass.getGeckolibData(com.geckolib.constant.DataTickets.ITEM_RENDER_PERSPECTIVE);
                        boolean firstPerson = context == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                                || context == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
                        // Held still the gun breathes: the idle clips move `main`. The sights are aligned with the model at rest
                        // (adjustRenderPose), so aimed that motion would carry them off the centre of the view, where the shot
                        // goes: it fades out with the aim. Only the idle clips: a shot's kick stays as it is.
                        float aim = GunFeel.aim();
                        if (firstPerson && aim > 0.001f && (lastClip.isEmpty() || lastClip.startsWith("idle"))) bones.ifPresent("main", snapshot -> {
                            float still = 1f - aim;
                            snapshot.setTranslation(snapshot.getTranslateX() * still, snapshot.getTranslateY() * still, snapshot.getTranslateZ() * still);
                            snapshot.setRotation(snapshot.getRotX() * still, snapshot.getRotY() * still, snapshot.getRotZ() * still);
                        });
                        if (firstPerson || GunFeel.flashing()) pass.model().getBone("muzzleflash").ifPresent(flashBone -> pass.addPerBoneRender(flashBone, (p, b, collector) -> {
                            if (!(collector instanceof EntityCapture capture)) return;
                            // where the muzzle is drawn this frame (camera space of the hand pass): bullet streaks start there
                            if (firstPerson) GunFeel.muzzleSeen(p.poseStack().last().pose());
                            if (!GunFeel.flashing()) return;
                            p.poseStack().translate((float) Tuning.get("flash.x", 0), (float) Tuning.get("flash.y", 0), (float) Tuning.get("flash.z", 0));
                            capture.glowSprite(p.poseStack().last(), Identifier.parse(PzBlocks.NAMESPACE + ":textures/effect/flashes.png"),
                                    (float) (Tuning.get("flash.halfSize", 1.25) * (kind.bigFlash() ? Tuning.get("flash.bigScale", 1.6) : 1)),
                                    GunFeel.flashFrame(), 9, GunFeel.flashAlpha());
                        }));
                        if (!firstPerson) return;
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
                                //#if MC_26_1
                                //$ ps.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) Tuning.get("armBone." + side + ".rx", 0)));
                                //#else
                                ps.rotateDegrees(com.mojang.math.Axis.XP, (float) Tuning.get("armBone." + side + ".rx", 0));
                                //#endif
                                //#if MC_26_1
                                //$ ps.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) Tuning.get("armBone." + side + ".ry", 0)));
                                //#else
                                ps.rotateDegrees(com.mojang.math.Axis.YP, (float) Tuning.get("armBone." + side + ".ry", 0));
                                //#endif
                                //#if MC_26_1
                                //$ ps.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) Tuning.get("armBone." + side + ".rz", 0)));
                                //#else
                                ps.rotateDegrees(com.mojang.math.Axis.ZP, (float) Tuning.get("armBone." + side + ".rz", 0));
                                //#endif
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
    static void identify(ItemStack stack, JsonObject state) {
        if (state != null && state.has("i")) {
            var type = (DataComponentType<Long>) com.geckolib.GeckoLibConstants.STACK_ANIMATABLE_ID_COMPONENT.get();
            stack.set(type, state.get("i").getAsLong());
        }
    }

    private static GunItem held(Minecraft mc) {
        ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        return stack.getItem() instanceof GunItem item ? item : null;
    }

    private AnimationController<?> controller(long id) { return cache.getManagerForId(id).getAnimationControllers().get(CONTROLLER); }

    private static double clampSpeed(double speed) { return Double.isFinite(speed) && speed > 0 ? Math.max(.5, Math.min(2.5, speed)) : 1.0; }

    /**
     * Ticks a change to this clip blends over, from the pose on screen (GeckoLib crossfades; a change with no blend snaps). A shot
     * or a hand going to the magazine answers at once, so its blend is short; the hand going back to rest, the sprint and the
     * inspection are slower, so nothing snaps at their ends.
     */
    static int blendTicks(String clip) {
        if (clip.equals("fire")) return 2;
        if (clip.startsWith("rack")) return 3;
        if (clip.startsWith("draw") || clip.startsWith("load") || clip.equals("unloadround")) return 4;
        if (clip.startsWith("reload") || clip.equals("unload") || clip.startsWith("idle")) return 6;
        if (clip.startsWith("run") || clip.startsWith("inspect")) return 8;
        return BLEND_TICKS;
    }

    /** Starts a clip of the held gun (from the beginning, also when it is the one playing) at a speed factor. */
    static boolean play(Minecraft mc, String clip, double speed) {
        GunItem item = held(mc);
        if (item == null) return false;
        if (!item.kind.has(clip.startsWith("run") && !clip.startsWith("running") ? runLoop(clip) : clip)) return false;
        long id = GeoItem.getId(mc.player.getMainHandItem());
        var controller = item.controller(id);
        // an inspection under way is not started again: every press of I would restart it from the beginning (it looked like jitter)
        var current = controller == null ? null : controller.getCurrentAnimationPoint();
        if (clip.startsWith("inspect") && lastClip.startsWith("inspect") && current != null && !current.hasFinished()) return true;
        if (controller != null) {
            controller.setAnimationSpeed(clampSpeed(speed));
            controller.setTransitionTicks(blendTicks(clip));      // read when the clip starts: blends from the pose on screen
        }
        OnClient.trigger(item, mc, id, clip);
        lastClip = clip;
        blendLeft = blendTicks(clip);
        clipsPlayed++;
        finishedTicks = 0;
        return true;
    }

    private static void toIdle(GunItem item, long id) {
        var controller = item.controller(id);
        if (controller == null) return;
        controller.stopTriggeredAnimation();
        controller.setAnimationSpeed(1.0);
        controller.setTransitionTicks(blendTicks("idle"));
        controller.setAnimation(RawAnimation.begin().thenLoop(item.idleClip()));
        lastClip = "";
        blendLeft = blendTicks("idle");
        idleResumed++;
    }

    private static void endSequence() { loading = null; loadsStarted = 0; holdPose = false; queuedClip = null; rackAfterLoad = 0; }

    /** One answer from PZ about the held gun. */
    static void result(Minecraft mc, JsonObject r) {
        GunItem item = held(mc);
        if (item == null) return;
        GunModels.Kind kind = item.kind;
        String status = r.get("status").getAsString();
        double seconds = r.has("seconds") ? r.get("seconds").getAsDouble() : 0;
        if (status.equals("fired")) {
            endSequence();
            expectRacked = r.has("rackSeconds");
            play(mc, "fire", 1.0);
        } else if (status.equals("reload started")) {
            endSequence();
            boolean empty = r.has("chamber") && !r.get("chamber").getAsBoolean() && kind.has("reloadempty");
            // a gun with no magazine in it: nothing comes out first (PZ was sent that clip's timing with the request)
            String clip = fitting(kind, empty ? "reloadempty" : "reload");
            boolean bare = clip.endsWith("nomag");
            // the clip plays at its own pace, as in the gun viewer: PZ's timer decides when the gun is reloaded, not how fast the hand moves
            play(mc, clip, 1.0);
        } else if (status.equals("loading") || status.equals("unloading")) {
            endSequence();
            if (!kind.has("load")) return;
            double per = r.has("per") ? r.get("per").getAsDouble() : 1.0;
            double first = r.has("first") ? r.get("first").getAsDouble() : per;      // when PZ puts the first round in
            // a model made for it takes a round out with a clip of its own, and its clips can go on after the round has moved
            String clip = status.equals("unloading") && kind.has("unloadround") ? "unloadround" : "load";
            loading = new Loading(System.nanoTime(), per, first, r.has("all") && r.get("all").getAsBoolean(), clip,
                    kind.number(clip.equals("load") ? "loadMark" : "unloadMark", 0));
            holdPose = true;
            if (kind.has("loadstart")) {
                // opening the action has until the first round's clip starts; never slower than the clip itself (it then holds)
                double room = Math.max(.15, first - loadSeconds(kind, per));
                play(mc, "loadstart", Math.max(1.0, kind.length("loadstart") / room));
            }
        } else if (status.equals("racking")) {
            expectRacked = true;
            if (loading != null) rackAfterLoad = seconds > 0 ? seconds : .85;      // "loaded" follows: the stroke comes after the closing clip
            else if (kind.has("rack")) play(mc, fitting(kind, "rack"), 1.0);      // at its own pace; PZ's stroke may be quicker, the clip is not sped up
        } else if (status.equals("racked") || status.equals("unjammed")) {
            if (expectRacked) expectRacked = false;
            else play(mc, kind.has("rack") ? fitting(kind, "rack") : "reload", 1.0);
        } else if (status.equals("eject started")) {
            // the clip plays at its own pace; PZ takes the magazine out on its own timer
            endSequence();
            play(mc, "unload", 1.0);
        } else if (status.equals("magazine ejected")) {
            // the answer to the clip started above; alone (PZ was sent no timing) it still shows the hand taking the magazine
            if (!lastClip.equals("unload")) { endSequence(); play(mc, "unload", 1.0); }
        } else if (status.equals("inspecting")) {
            endSequence();
            String clip = r.has("clip") ? r.get("clip").getAsString() : "inspect";
            play(mc, kind.has(clip) && !clip.equals("inspect") ? clip : fitting(kind, "inspect"), 1.0);
        }
        if (r.has("ammo") && (status.startsWith("loaded") || status.startsWith("unloaded"))) {
            saidRounds = r.get("ammo").getAsInt() + (r.has("chamber") && r.get("chamber").getAsBoolean() ? 1 : 0);
            saidAt = System.nanoTime();
        }
        if (loading != null && (status.startsWith("loaded") || status.startsWith("unloaded") || status.startsWith("stopped")
                || status.startsWith("cancelled") || status.equals("no rounds") || status.equals("invalid ammo"))) {
            double rack = rackAfterLoad;
            boolean returns = loading.mark() > 0 && lastClip.equals(loading.clip());     // the running clip takes the hand back to rest
            endSequence();
            boolean stroke = rack > 0 && kind.has("rack") && !kind.loadendCloses();
            if (kind.has("loadend")) {
                play(mc, "loadend", 1.0);
                if (stroke) { queuedClip = "rack"; queuedSpeed = 1.0; }
            } else if (stroke) play(mc, "rack", 1.0);
            else if (!returns) toIdle(item, GeoItem.getId(mc.player.getMainHandItem()));
        } else if (status.equals("reload cancelled") || status.equals("eject cancelled")) {
            toIdle(item, GeoItem.getId(mc.player.getMainHandItem()));
        }
    }

    /** How long one round's clip is given: its own length, or less when PZ loads faster than that. */
    private static double loadSeconds(GunModels.Kind kind, double per) { return Math.min(kind.length("load"), per * .8); }

    static void tick(Minecraft mc) {
        ItemStack stack = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof GunItem item)) { heldId = Long.MIN_VALUE; endSequence(); expectRacked = false; shownClip = ""; blendLeft = 0; return; }
        if (blendLeft > 0 && --blendLeft == 0) shownClip = lastClip;      // the blend is done: the clip's own bones follow it
        GunModels.Kind kind = item.kind;
        long id = GeoItem.getId(stack);
        var state = PzItems.stateOf(stack);
        var g = state.has("g") ? state.getAsJsonObject("g") : null;
        boolean chamber = g != null && g.has("chamber") && g.get("chamber").getAsBoolean();
        boolean nothingLeft = g != null && g.has("chamber") && !chamber && (!g.has("ammo") || g.get("ammo").getAsInt() <= 0);
        if (!nothingLeft) emptyHeld = false;
        else if (id != heldId || hadChamber) emptyHeld = true;      // drawn empty, or the chamber has just been emptied by a shot
        hadChamber = chamber;
        noMagazine = g != null && g.has("clip") && !g.get("clip").getAsBoolean() && kind.recipe().equals("magazine");
        int rounds = g == null ? 0 : (g.has("ammo") ? g.get("ammo").getAsInt() : 0) + (g.has("chamber") && g.get("chamber").getAsBoolean() ? 1 : 0);
        // PZ's own answer is ahead of the mirrored stack: until the stack agrees (a second at most) the answer is what is shown
        if (saidRounds >= 0 && (id != heldId || rounds == saidRounds || System.nanoTime() - saidAt > 1_000_000_000L)) saidRounds = -1;
        heldRounds = saidRounds >= 0 ? saidRounds : rounds;
        if (id != heldId) {
            heldId = id;
            wasSprinting = false;
            sprintHeld = 0;
            endSequence();
            expectRacked = false;
            shownClip = "";
            blendLeft = 0;
            play(mc, emptyHeld && kind.has("drawempty") ? "drawempty" : fitting(kind, "draw"), 1.0);
        }
        var controller = item.controller(id);
        // rounds going in: the n-th round's clip is timed so that PZ puts the round in (every `per` seconds) when the clip does
        if (loading != null) {
            // PZ moves the n-th round at first, first + per, first + 2 per ...: that is the clip's mark, or its end
            double t = (System.nanoTime() - loading.start) / 1e9, lead = loading.lead(kind);
            int due = t + lead >= loading.first ? 1 + (int) Math.floor((t + lead - loading.first) / loading.per) : 0;
            if (due > loadsStarted && (!loading.all || loadsStarted == 0)) {
                loadsStarted = due;
                play(mc, loading.clip(), kind.length(loading.clip()) / loading.seconds(kind));
            }
            if (t > 120) endSequence();
        }
        // A finished one-shot clip (fire, draw, reload...) otherwise stays frozen on its last frame and the idle pose is never
        // resumed: the slide would stay shut after the last round. Give control back to the idle handler, which chooses
        // idle or idleempty (slide back) from the chamber state. While rounds go in the frozen frame is wanted (action open).
        // Two ticks in a row, so a clip triggered in this very tick (before the renderer has swapped it in) is not cut off.
        if (controller != null && controller.getCurrentAnimationPoint() != null && controller.getCurrentAnimationPoint().hasFinished()) {
            if (++finishedTicks >= 2) {
                finishedTicks = 0;
                if (queuedClip != null) { String next = queuedClip; queuedClip = null; play(mc, next, queuedSpeed); }
                else if (!holdPose) toIdle(item, id);
            }
        } else finishedTicks = 0;
        // Aiming or sprinting ends an inspection: the sights/run pose take over, and PZ stops the rest of the inspect sounds.
        var playing = controller == null ? null : controller.getCurrentAnimationPoint();
        if (playing != null && !playing.hasFinished() && playing.animation().name().contains("inspect")
                && (GunFeel.aim() > 0.05f || GunFeel.sprinting())) {
            toIdle(item, id);
            PzGuns.cancelInspect();
        }
        // tuning aid: debug.clipTime >= 0 holds the playing clip at that many seconds, for looking at one frame of it
        double freeze = Tuning.get("debug.clipTime", -1);
        if (freeze >= 0 && controller != null && controller.getCurrentAnimationPoint() != null) {
            controller.setAnimationSpeed(1e-4);      // not 0: GeckoLib only re-poses the bones when time has moved
            controller.setAnimationTime(freeze);
            finishedTicks = 0;
        }
        // sprinting carries the gun low and angled during running clips: started and ended by triggers, which the controller honours
        // the sprint flag can flicker (a landing, a step up): the pose changes only once it has held for a few ticks
        boolean sprint = GunFeel.sprinting() && loading == null;
        if (sprint == wasSprinting) sprintHeld = 0;
        else if (++sprintHeld >= SPRINT_HOLD_TICKS) {
            sprintHeld = 0;
            wasSprinting = sprint;
            // the slide-back clips come first, as they do standing still (an empty gun without a magazine: its magazine bones are hidden)
            boolean open = emptyHeld && kind.has("runningempty");
            if (kind.has("running")) play(mc, sprint ? (open ? "runempty" : noMagazine && kind.has("runningnomag") ? "runnomag" : "run")
                    : open && kind.has("runningendempty") ? "runningendempty" : kind.has("runningend") ? "runningend" : "idle", 1.0);
        }
    }

    /**
     * The calls that hand a client type on as one of its supertypes ({@code mc.player} as an Entity): the verifier loads such a
     * type to check it, and a dedicated server has none, so they live here, loaded only when they first run (on a client).
     */
    private static final class OnClient {
        static void trigger(GunItem item, Minecraft mc, long id, String clip) { item.triggerAnim(mc.player, id, CONTROLLER, clip); }
    }

    static java.util.Map<String, Object> animationStats() {
        var mc = Minecraft.getInstance();
        var stack = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (!(stack.getItem() instanceof GunItem item)) return java.util.Map.of();
        var controller = item.controller(GeoItem.getId(stack));
        if (controller == null) return java.util.Map.of();
        var point = controller.getCurrentAnimationPoint();
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("kind", item.kind.id()); m.put("speed", controller.getAnimationSpeed()); m.put("time", controller.getCurrentAnimationTime());
        m.put("clip", point == null ? "" : point.animation().name()); m.put("triggered", controller.isPlayingTriggeredAnimation());
        m.put("finished", point != null && point.hasFinished()); m.put("loop", point == null ? "" : String.valueOf(point.loopType()));
        m.put("playState", String.valueOf(controller.getPlayState())); m.put("idleResumed", idleResumed);
        m.put("transitioning", controller.isTransitioning()); m.put("blendTicks", controller.getTransitionTicks());
        m.put("lastClip", lastClip); m.put("shownClip", shownClip); m.put("clipsPlayed", clipsPlayed); m.put("loading", loading != null); m.put("loadsStarted", loadsStarted);
        m.put("holdPose", holdPose); m.put("queued", queuedClip == null ? "" : queuedClip); m.put("noMagazine", noMagazine); m.put("empty", emptyHeld);
        return m;
    }
}
