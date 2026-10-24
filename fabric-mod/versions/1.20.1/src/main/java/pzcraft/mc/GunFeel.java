package pzcraft.mc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.InputState;

/**
 * How the pistol feels, all client side and visual: aiming down sights (hold the use key), camera recoil, muzzle flash,
 * bullet tracers, the crosshair and whether the gun is being run with. PZ owns what a shot does; this only shows it. The
 * numbers can be changed while the game runs in {@code ~/.pzcraft/tuning.json} ({@code recoil.*}, {@code tracer.*},
 * {@code crosshair.*}, {@code ads.*}).
 */
public final class GunFeel {
    private static float aim, spread;
    private static long lastFrame = System.nanoTime(), flashUntil, flashBorn;
    private static int flashFrame;
    private static float crosshairExpansion = 1, previousYaw, previousPitch;
    private static boolean sprinting, gunHeld;
    private record Tracer(Vec3 from, Vec3 to, long born) {}
    private static final List<Tracer> TRACERS = new ArrayList<>();

    private GunFeel() {}

    /** 0 hip fire, 1 fully aimed down the sights. */
    static float aim() { return aim; }
    static boolean flashing() { return System.nanoTime() < flashUntil; }
    static int flashFrame() { return flashFrame; }
    static float flashAlpha() { return (float) (0.6 * Math.pow(Math.max(0, 1.0 - (System.nanoTime()-flashBorn)/(double)Math.max(1,flashUntil-flashBorn)), 2)); }
    static boolean sprinting() { return sprinting || Tuning.get("debug.sprint", 0) > 0; }
    private static float swayPhase, idlePhase, swaySpeed;

    /**
     * The gun moves with the player: a step bob scaled by his real horizontal speed (stronger sprinting) and a slow breathing
     * drift when he stands still. Applied to the camera-space pose the hands are drawn in. Aiming fades this one out by itself
     * (the sight alignment blends the pose towards a fixed target); the motion while aimed is {@link #adsSway}.
     */
    static void sway(com.mojang.blaze3d.vertex.PoseStack pose) { applySway(pose, 1f, 1f, 1f, 1f); }

    /**
     * Walking while aimed: a camera-space matrix to apply after the sight alignment. The reference's walking_aiming clip keeps
     * about 0.4 of the hip bob in position, the full roll and about half the pitch (animation.model.walking_aiming against
     * animation.model.walking); breathing nearly stops. {@code null} when not aimed.
     */
    static org.joml.Matrix4f adsSway() {
        if (aim <= 0.001f) return null;
        var s = new com.mojang.blaze3d.vertex.PoseStack();
        applySway(s, aim * (float) Tuning.get("sway.adsPos", 0.4), aim * (float) Tuning.get("sway.adsRoll", 1.0),
                aim * (float) Tuning.get("sway.adsPitch", 0.5), aim * 0.2f);
        return new org.joml.Matrix4f(s.last().pose());
    }

    /**
     * The speed the bob is driven by. Aimed, Steve walks at about half speed, but the reference's walking clip is time-based and
     * keeps its beat; so the aimed speed is read as a full walking speed (blended in with the aim).
     */
    private static float swayRate() { return swaySpeed * (1f + aim * (float) Tuning.get("sway.adsSpeedBoost", 1.0)); }

    private static void applySway(com.mojang.blaze3d.vertex.PoseStack pose, float posScale, float rollScale, float pitchScale, float breathScale) {
        float step = (float) Math.min(1.5, swayRate() / 4.3);      // 1 at walking speed
        float bobX = (float) (Math.sin(swayPhase) * Tuning.get("sway.x", 0.018) * step);
        float bobY = (float) (-Math.abs(Math.sin(swayPhase)) * Tuning.get("sway.y", 0.022) * step);
        float breathY = (float) (Math.sin(idlePhase) * Tuning.get("sway.breath", 0.003));
        float breathX = (float) (Math.sin(idlePhase * 0.6) * Tuning.get("sway.breath", 0.003) * 0.7);
        pose.translate(bobX * posScale + breathX * breathScale, bobY * posScale + breathY * breathScale, 0f);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) (Math.sin(swayPhase) * Tuning.get("sway.roll", 1.4) * step * rollScale)));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) (Math.abs(Math.cos(swayPhase)) * Tuning.get("sway.pitch", 0.8) * step * pitchScale)));
    }

    static java.util.Map<String, Object> stats() {
        var stats = new java.util.LinkedHashMap<String, Object>();
        stats.put("aim", aim); stats.put("sprinting", sprinting); stats.put("gunHeld", gunHeld); stats.put("flashing", flashing());
        stats.put("recoilPitch", GunRecoil.offsetPitch()); stats.put("recoilYaw", GunRecoil.offsetYaw());
        stats.put("recoilActive", GunRecoil.active()); stats.put("recoilPeakPitch", GunRecoil.peakPitch());
        stats.put("recoilPeakYaw", GunRecoil.peakYaw()); stats.put("recoilShots", GunRecoil.shots());
        stats.put("offhandCleared", PzGuns.offhandCleared);
        stats.put("swaySpeed", swaySpeed); stats.put("animation", GunItem.animationStats());
         stats.put("lastShot", lastShot);
        return stats;
    }

    /** Once per rendered frame (FrameHook). */
    static void frame(Minecraft mc) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        
        
        var p = mc.player;
        gunHeld = p != null && Session.pzConnected && PzGuns.held(p.getMainHandItem()) && mc.screen == null && !p.isDeadOrDying();
        var input = gunHeld ? Session.readInput() : null;
        boolean wantAim = gunHeld && input != null && (input.buttons & InputState.USE) != 0 && !p.isSprinting();
        aim = Math.max(0, Math.min(1, aim + (wantAim ? 1 : -1) * dt / (float) Tuning.get("ads.seconds", .2)));
        if (!gunHeld && aim < 0.002f) aim = 0f;
        spread *= (float) Math.exp(-dt * 6);
        sprinting = gunHeld && p.isSprinting() && p.onGround();
        double blocksPerSecond = p == null ? 0 : p.getDeltaMovement().horizontalDistance() * 20;
        swaySpeed += (float) ((p != null && p.onGround() ? blocksPerSecond : 0) - swaySpeed) * Math.min(1f, dt * 10f);
        swayPhase += dt * Math.max(0f, swayRate()) * (float) Tuning.get("sway.rate", 2.3);
        idlePhase += dt * (float) Tuning.get("sway.breathRate", 1.7);
        if (p != null) {
            float expansion = 1 + (float)p.getDeltaMovement().length() * 8;
            if (p.isSprinting()) expansion *= 3;
            if (!p.onGround()) expansion *= 2.4f;
            if (p.isVisuallyCrawling()) expansion *= .8f;
            if (p.isCrouching()) expansion *= .7f;
            expansion += (Math.abs(net.minecraft.util.Mth.wrapDegrees(p.getYRot()-previousYaw)) + Math.abs(p.getXRot()-previousPitch)) * .1f;
            if (spread > .1f) expansion *= 4;
            previousYaw=p.getYRot(); previousPitch=p.getXRot();
            crosshairExpansion += (Math.max(1,Math.min(7,expansion))-crosshairExpansion)*Math.min(1,dt/.1f);
        }
        synchronized (TRACERS) { TRACERS.removeIf(t -> now - t.born > 200_000_000L); }
    }

    /**
     * A shot PZ confirmed: flash, kick, tracer and what it did where it ended (blood on a target PZ hurt, smoke and chips on a
     * surface). {@code shot.from()} is near the muzzle, {@code shot.to()} where the ray ended.
     */
    static void fired(PzGuns.Shot shot, com.google.gson.JsonObject result) {
        Vec3 seen = muzzleWorld(Minecraft.getInstance());
        Vec3 from = shot == null ? null : seen != null ? seen : shot.from(), to = shot == null ? null : shot.to();
        boolean hit = result != null && result.has("hit") && result.get("hit").getAsBoolean();
        float damage = result != null && result.has("damage") ? result.get("damage").getAsFloat() : 0f;
        boolean killed = result != null && result.has("killed") && result.get("killed").getAsBoolean();
        if (shot != null) {
            GunEffects120.impact(shot, result);
            lastShot = lastShotRecord(shot, hit, damage, killed, result);
        }
        long now = System.nanoTime();
        flashBorn = now;
        flashUntil = now + (long) (Tuning.get("flash.ms", 50) * 1e6);
        flashFrame = java.util.concurrent.ThreadLocalRandom.current().nextInt(9);
        
        GunRecoil.fired(aim > .5f, PzGuns.recoilScale(Minecraft.getInstance().player == null ? null : Minecraft.getInstance().player.getMainHandItem()));
        spread = Math.min(2f, spread + 1f);
        if (from != null && to != null) synchronized (TRACERS) {
            // a shotgun shot shows a few of its pellets as streaks, not nine
            for (Vec3 end : shot.tracerEnds()) TRACERS.add(new Tracer(from, end, now));
        }
    }

    private static volatile org.joml.Vector3f muzzleCamera;
    private static volatile long muzzleSeenAt;

    /** The muzzle bone of the first-person gun as it was just drawn (camera space of the hand pass). */
    static void muzzleSeen(org.joml.Matrix4fc pose) {
        muzzleCamera = pose.transformPosition(new org.joml.Vector3f());
        muzzleSeenAt = System.nanoTime();
    }

    /**
     * Where that muzzle is in the world now, so a streak leaves the gun that is on screen. The hands are drawn with their own
     * field of view (70), the world with the player's: the sideways offsets are scaled by the ratio so the two line up on screen.
     * Null in third person or when no rigged gun was drawn in the last quarter second.
     */
    static Vec3 muzzleWorld(Minecraft mc) {
        var m = muzzleCamera;
        if (m == null || System.nanoTime() - muzzleSeenAt > 250_000_000L || mc.player == null || !mc.options.getCameraType().isFirstPerson()) return null;
        float partial = mc.getFrameTime();
        Vec3 eye = mc.player.getEyePosition(partial), forward = mc.player.getViewVector(partial);
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        // the world is shown with Viewpoint's field of view (narrower while aiming), the hands with Minecraft's fixed 70
        double world = Session.fovDeg > 10 && Session.fovDeg < 170 ? Session.fovDeg : mc.options.fov().get();
        double k = Tuning.get("tracer.fovScale", Math.tan(Math.toRadians(world) / 2) / Math.tan(Math.toRadians(Tuning.get("tracer.handFov", 70)) / 2));
        return eye.add(right.scale(m.x * k)).add(up.scale(m.y * k)).add(forward.scale(-m.z));
    }

    private static volatile java.util.Map<String, Object> lastShot = java.util.Map.of();
    private static int shotsConfirmed;
    private static java.util.Map<String, Object> lastShotRecord(PzGuns.Shot s, boolean hit, float damage, boolean killed, com.google.gson.JsonObject result) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("n", ++shotsConfirmed);
        m.put("eye", vec(s.eye())); m.put("dir", vec(s.dir())); m.put("muzzle", vec(s.from())); m.put("end", vec(s.to()));
        Vec3 seen = muzzleWorld(Minecraft.getInstance());
        if (seen != null) m.put("muzzleSeen", vec(seen));
        m.put("distance", s.eye().distanceTo(s.to()));
        m.put("normal", s.normal() == null ? null : vec(s.normal()));
        m.put("block", s.block() == null ? null : s.block().toShortString());
        if (s.target() != null) {
            m.put("targetMin", new double[] {s.target().minX, s.target().minY, s.target().minZ});
            m.put("targetMax", new double[] {s.target().maxX, s.target().maxY, s.target().maxZ});
        }
        m.put("hit", hit); m.put("damage", damage); m.put("killed", killed);
        m.put("pellets", s.tracerEnds().size()); m.put("impacts", s.impacts().size());
        if (result != null && result.has("hits")) m.put("hits", result.getAsJsonArray("hits").size());
        return m;
    }
    private static double[] vec(Vec3 v) { return new double[] {v.x, v.y, v.z}; }

    /** Glowing streaks from the muzzle to where each shot ended, relative to the export origin; camera is the eye position. */
    /** Native rounds (including the chamber), independent of the reference gun's gameplay data. */
    static void submitTracers(EntityCapture capture,Vec3 camera,int ox,int oy,int oz) {
        int previous=capture.flags;
        capture.flags=pzcraft.protocol.EntityLink.FLAG_NO_SHADOW|pzcraft.protocol.EntityLink.FLAG_TRANSLUCENT|pzcraft.protocol.EntityLink.FLAG_ADDITIVE;
        try {
            var buffer=capture.getBuffer(net.minecraft.client.renderer.RenderType.entityTranslucentEmissive(new net.minecraft.resources.ResourceLocation(PzBlocks.NAMESPACE,"textures/effect/tracers2.png")));
            long now=System.nanoTime();Vec3 origin=new Vec3(ox,oy,oz);
            synchronized(TRACERS) { for(Tracer t:TRACERS) {
                double age=(now-t.born)/1e9,distance=t.to.distanceTo(t.from);if(distance<.001||age>.2)continue;
                Vec3 direction=t.to.subtract(t.from).scale(1/distance);
                double tail=Math.min(distance,age*300),head=Math.min(distance,tail+Math.min(10,distance*.5));if(head<=tail)continue;
                Vec3 a=t.from.add(direction.scale(tail)),b=t.from.add(direction.scale(head));
                Vec3 side=direction.cross(camera.subtract(a.add(b).scale(.5))).normalize().scale(Tuning.get("tracer.width",.025)/2);
                Vec3[] corners={a.add(side),b.add(side),b.subtract(side),a.subtract(side)};
                float[][] uv={{0,1},{0,0},{.75f,0},{.75f,1}};
                for(int i=0;i<4;i++) {Vec3 v=corners[i].subtract(origin);buffer.vertex(v.x,v.y,v.z).color(255,255,255,255).uv(uv[i][0],uv[i][1]).overlayCoords(0).uv2(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT).normal(0,1,0).endVertex();}
            }}
        } finally {capture.flags=previous;}
    }

    /** Native rounds (including the chamber), independent of the reference gun's gameplay data. */
    public static void drawAmmo(GuiGraphics g) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || !Session.pzConnected || !PzGuns.held(mc.player.getMainHandItem()) || mc.screen!=null)return;
        var state=PzItems.stateOf(mc.player.getMainHandItem());
        if(!state.has("g"))return;
        var gun=state.getAsJsonObject("g");
        int rounds=gun.has("ammo")?gun.get("ammo").getAsInt():0;
        if(gun.has("chamber")&&gun.get("chamber").getAsBoolean())rounds++;
        int capacity=gun.has("capacity")?gun.get("capacity").getAsInt():0;
        String text=rounds+"/"+capacity+"  "+(gun.has("mode")?gun.get("mode").getAsString().toUpperCase():"SINGLE");
        int x=g.guiWidth()/2+102,y=g.guiHeight()-16;
        if(x+mc.font.width(text)>g.guiWidth()-4){x=g.guiWidth()/2+91-mc.font.width(text);y=g.guiHeight()-34;}
        g.drawString(mc.font,text,x,y,0xFFFFFFFF);
    }

    /** True when it drew the crosshair itself (a gun in first person); false leaves vanilla's. */
    public static boolean drawCrosshair(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (!gunHeld) return false;
        // A gun suppresses the vanilla crosshair in every view; ADS uses its physical sights.
        if (!mc.options.getCameraType().isFirstPerson() || aim > .001f) return true;
        int cx=g.guiWidth()/2, cy=g.guiHeight()/2, gap=Math.round(3+crosshairExpansion*2);
        g.fill(cx-gap-4,cy,cx-gap,cy+1,0xFFFFFFFF); g.fill(cx+gap,cy,cx+gap+4,cy+1,0xFFFFFFFF);
        g.fill(cx,cy-gap-4,cx+1,cy-gap,0xFFFFFFFF); g.fill(cx,cy+gap,cx+1,cy+gap+4,0xFFFFFFFF);
        return true;
    }
}
