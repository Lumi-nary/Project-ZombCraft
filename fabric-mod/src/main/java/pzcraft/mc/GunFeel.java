package pzcraft.mc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.InputState;

/**
 * How the pistol feels, all client side and visual: aiming down sights (hold the use key), camera recoil, muzzle flash,
 * bullet tracers, the crosshair and whether the gun is being run with. PZ owns what a shot does; this only shows it. The
 * numbers can be changed while the game runs in {@code ~/.pzcraft/tuning.json} ({@code recoil.*}, {@code tracer.*},
 * {@code crosshair.*}, {@code ads.*}).
 */
final class GunFeel {
    private static float aim, kickPitch, kickYaw, spread;
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
    /** Degrees added to the camera (negative pitch looks up), decaying after each shot. */
    static float kickPitch() { return kickPitch; }
    static float kickYaw() { return kickYaw; }

    private static float swayPhase, idlePhase, swaySpeed;

    /**
     * The gun moves with the player: a step bob scaled by his real horizontal speed (stronger sprinting), a slow breathing
     * drift when he stands still, and almost nothing while aiming. Applied to the camera-space pose the hands are drawn in.
     */
    static void sway(com.mojang.blaze3d.vertex.PoseStack pose) {
        float aimDamp = 1f - 0.8f * aim;
        float step = (float) Math.min(1.5, swaySpeed / 4.3);       // 1 at walking speed
        float bobX = (float) (Math.sin(swayPhase) * Tuning.get("sway.x", 0.018) * step);
        float bobY = (float) (-Math.abs(Math.sin(swayPhase)) * Tuning.get("sway.y", 0.022) * step);
        float breathY = (float) (Math.sin(idlePhase) * Tuning.get("sway.breath", 0.003));
        float breathX = (float) (Math.sin(idlePhase * 0.6) * Tuning.get("sway.breath", 0.003) * 0.7);
        pose.translate((bobX + breathX) * aimDamp, (bobY + breathY) * aimDamp, 0f);
        pose.rotateDegrees(com.mojang.math.Axis.ZP, (float) (Math.sin(swayPhase) * Tuning.get("sway.roll", 1.4) * step * aimDamp));
        pose.rotateDegrees(com.mojang.math.Axis.XP, (float) (Math.abs(Math.cos(swayPhase)) * Tuning.get("sway.pitch", 0.8) * step * aimDamp));
    }

    static java.util.Map<String, Object> stats() {
        return java.util.Map.of("aim", aim, "sprinting", sprinting, "gunHeld", gunHeld, "flashing", flashing(), "kickPitch", kickPitch, "swaySpeed", swaySpeed, "animation", M9Item.animationStats(), "shells", GunShells.stats());
    }

    /** Once per rendered frame (FrameHook). */
    static void frame(Minecraft mc) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        GunShells.frame();
        var p = mc.player;
        gunHeld = p != null && Session.pzConnected && PzGuns.held(p.getMainHandItem()) && mc.gui.screen() == null && !p.isDeadOrDying();
        var input = gunHeld ? Session.readInput() : null;
        boolean wantAim = gunHeld && input != null && (input.buttons & InputState.USE) != 0 && !p.isSprinting();
        aim = Math.max(0, Math.min(1, aim + (wantAim ? 1 : -1) * dt / (float) Tuning.get("ads.seconds", .2)));
        if (!gunHeld && aim < 0.002f) aim = 0f;
        float decay = (float) Math.exp(-dt * Tuning.get("recoil.decay", 9));
        kickPitch *= decay;
        kickYaw *= decay;
        spread *= (float) Math.exp(-dt * 6);
        sprinting = gunHeld && p.isSprinting() && p.onGround();
        double blocksPerSecond = p == null ? 0 : p.getDeltaMovement().horizontalDistance() * 20;
        swaySpeed += (float) ((p != null && p.onGround() ? blocksPerSecond : 0) - swaySpeed) * Math.min(1f, dt * 10f);
        swayPhase += dt * Math.max(0f, swaySpeed) * (float) Tuning.get("sway.rate", 2.3);
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

    /** A shot PZ confirmed: flash, kick, tracer. {@code from} is near the muzzle, {@code to} where the ray ended. */
    static void fired(Vec3 from, Vec3 to) {
        long now = System.nanoTime();
        flashBorn = now;
        flashUntil = now + (long) (Tuning.get("flash.ms", 50) * 1e6);
        flashFrame = java.util.concurrent.ThreadLocalRandom.current().nextInt(9);
        GunShells.fired();
        float damp = 1f - (float) Tuning.get("recoil.adsDamp", 0.45) * aim;
        kickPitch -= (float) Tuning.get("recoil.pitch", 2.6) * damp;
        kickYaw += (float) ((Math.random() - 0.5) * Tuning.get("recoil.yaw", 1.0)) * damp;
        spread = Math.min(2f, spread + 1f);
        if (from != null && to != null) synchronized (TRACERS) { TRACERS.add(new Tracer(from, to, now)); }
    }

    /** Glowing streaks from the muzzle to where each shot ended, relative to the export origin; camera is the eye position. */
    static void submitTracers(EntityCapture capture, Vec3 camera, int ox, int oy, int oz) {
        long now = System.nanoTime();
        float life = (float) Tuning.get("tracer.life", .2);
        float width = (float)Tuning.get("tracer.width", .2);
        synchronized (TRACERS) {
            for (Tracer t : TRACERS) {
                float age = (now - t.born) / 1e9f;
                if (age > life) continue;
                Vec3 d = t.to.subtract(t.from);
                double distance=d.length();
                if(distance<.001)continue;
                double tail=Math.min(distance,age*Tuning.get("tracer.speed",300));
                double head=Math.min(distance,tail+Math.max(.1,Math.min(10,distance*.5)));
                if(tail>=head)continue;
                Vec3 origin=new Vec3(ox,oy,oz);
                Vec3 a=t.from.add(d.scale(tail/distance)).subtract(origin),b=t.from.add(d.scale(head/distance)).subtract(origin);
                capture.texturedTracer(a,b,camera.subtract(origin),width,(float)Tuning.get("tracer.faceWidth",.1),age/life*6*(float)Math.PI);
            }
        }
    }

    /** Native rounds (including the chamber), independent of the reference gun's gameplay data. */
    static void drawAmmo(GuiGraphicsExtractor g) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || !Session.pzConnected || !PzGuns.held(mc.player.getMainHandItem()) || mc.gui.screen()!=null)return;
        var state=PzItems.stateOf(mc.player.getMainHandItem());
        if(!state.has("g"))return;
        var gun=state.getAsJsonObject("g");
        int rounds=gun.has("ammo")?gun.get("ammo").getAsInt():0;
        if(gun.has("chamber")&&gun.get("chamber").getAsBoolean())rounds++;
        int capacity=gun.has("capacity")?gun.get("capacity").getAsInt():0;
        String text=rounds+"/"+capacity+"  SINGLE";
        int x=g.guiWidth()/2+102,y=g.guiHeight()-16;
        if(x+mc.font.width(text)>g.guiWidth()-4){x=g.guiWidth()/2+91-mc.font.width(text);y=g.guiHeight()-34;}
        g.text(mc.font,text,x,y,0xFFFFFFFF);
    }

    /** True when it drew the crosshair itself (a gun in first person); false leaves vanilla's. */
    static boolean drawCrosshair(GuiGraphicsExtractor g) {
        var mc = Minecraft.getInstance();
        if (!gunHeld) return false;
        // A gun suppresses the vanilla crosshair in every view; ADS uses its physical sights.
        if (!mc.options.getCameraType().isFirstPerson() || aim > .001f) return true;
        int cx=g.guiWidth()/2, cy=g.guiHeight()/2;
        float gap=3.2f*(crosshairExpansion-1), half=30f*7/39/2;
        var texture=net.minecraft.resources.Identifier.parse("pzcraft:textures/gui/crosshair.png");
        float inner=16f/39;
        g.blit(texture, Math.round(cx-half), Math.round(cy-half), Math.round(cx+half), Math.round(cy+half), inner, 1-inner, inner, 1-inner);
        g.blit(texture, Math.round(cx-15-gap), cy-15, Math.round(cx-half+.5f-gap), cy+15, 0, inner, 0, 1);
        g.blit(texture, Math.round(cx+half-.5f+gap), cy-15, Math.round(cx+15-.5f+gap), cy+15, 1-inner, 1, 0, 1);
        g.blit(texture, cx-15, Math.round(cy+half-.5f+gap), cx+15, Math.round(cy+15-1+gap), 0, 1, 1-inner, 1);
        return true;
    }
}

