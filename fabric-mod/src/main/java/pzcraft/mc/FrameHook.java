package pzcraft.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import pzcraft.protocol.InputState;
import pzcraft.protocol.PlayerState;
import pzcraft.protocol.SharedLink;

/** Per rendered frame: take PZ's look direction first, publish Steve's interpolated pose back to PZ last. */
public final class FrameHook {
    private static final PlayerState state = new PlayerState();
    private static float lastAspect;

    private FrameHook() {}

    /** Start of a frame: take PZ's look direction and size, so this very frame is rendered through it. */
    public static void beforeFrame(Minecraft mc) {
        WindowMode.initialize(mc);
        GuiBridge.beforeFrame(mc);
        boolean wasNative=Session.nativeWorld;
        Session.nativeWorld = Session.pzConnected && Session.released && Session.link != null && Session.link.nativeWorldActive();
        if(wasNative && !Session.nativeWorld && mc.level!=null)
            mc.levelRenderer.invalidateCompiledGeometry(mc.level,mc.options,mc.gameRenderer.mainCamera(),mc.getBlockColors());
        LocalPlayer p = mc.player;
        InputState in = Session.readInput();
        if (p == null || mc.level == null) return;
        Session.playerUuid = p.getUUID();
        if (Session.pzConnected && in != null) {
            // PZ owns the camera, so rotation is input, not output: no round-trip lag on mouse look.
            p.setYRot(in.yawDeg);
            p.setXRot(Mth.clamp(in.pitchDeg, -90.0F, 90.0F));
            p.yRotO = p.getYRot();
            p.xRotO = p.getXRot();
            p.setYHeadRot(in.yawDeg);
        }

        float asp = Session.aspect;
        if (Session.pzConnected && asp > 0.5f && asp < 4f && Math.abs(asp - lastAspect) > 0.01f) {
            // Render at PZ's aspect ratio so the overlay is not stretched and the world layer lines up horizontally.
            lastAspect = asp;
            mc.getWindow().setWindowed(1280, Math.max(240, Math.round(1280f / asp)));
        }
        FrameCapture.begin(p.getYRot(), p.getXRot(), Session.renderFov());
    }

    /** The level has been drawn; the hand and HUD are next. */
    public static void afterWorld(Minecraft mc) {
        var camera = mc.gameRenderer.mainCamera();
        FrameCapture.camera(camera.yRot(), camera.xRot());
        FrameCapture.captureWorld(mc);
    }

    /** End of a frame: publish Steve's interpolated pose to PZ and hand the finished picture over. */
    public static void afterFrame(Minecraft mc) {
        LocalPlayer p = mc.player;
        SharedLink l = Session.link;
        if (p == null || l == null || mc.level == null) return;

        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        state.tick = mc.level.getGameTime();
        state.x = Mth.lerp(pt, p.xo, p.getX());
        state.y = Mth.lerp(pt, p.yo, p.getY());
        state.z = Mth.lerp(pt, p.zo, p.getZ());
        state.yawDeg = p.getYRot();
        state.pitchDeg = p.getXRot();
        state.flags = (p.onGround() ? PlayerState.FLAG_ON_GROUND : 0)
                | (p.isCrouching() ? PlayerState.FLAG_SNEAKING : 0)
                | (p.isSprinting() ? PlayerState.FLAG_SPRINTING : 0)
                | (mc.gui.screen() != null ? PlayerState.FLAG_MENU_OPEN : 0);
        // PZ's camera sits at Steve's eye: the height Minecraft's own camera used for this frame (eased between
        // standing, sneaking and swimming), or his pose's eye height when Minecraft's camera is elsewhere.
        var camera = mc.gameRenderer.mainCamera();
        state.eyeHeight = camera.entity() == p && !camera.isDetached()
                ? (float) (camera.position().y - state.y) : p.getEyeHeight();
        state.cameraMode = mc.options.getCameraType().ordinal();
        state.cameraX = camera.position().x; state.cameraY = camera.position().y; state.cameraZ = camera.position().z;
        GunFeel.frame(mc);
        // recoil moves what the player sees (and the world with it), not Steve's aim
        state.cameraYawDeg = camera.yRot() + GunFeel.kickYaw(); state.cameraPitchDeg = camera.xRot() + GunFeel.kickPitch();
        state.aim = GunFeel.aim();
        state.cameraValid = camera.entity() == p;
        if (Session.nativeWorld) {
            EntitySceneExporter.frame(mc, !mc.options.getCameraType().isFirstPerson(),state);
        }
        l.writePlayerState(state);
        FrameCapture.captureHud(mc);
    }
}

