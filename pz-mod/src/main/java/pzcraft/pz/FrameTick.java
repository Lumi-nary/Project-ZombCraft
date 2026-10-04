package pzcraft.pz;

import pzcraft.protocol.PlayerState;

/** Once-per-frame work, run right after PZ polls the mouse. Never lets an exception escape into the game loop. */
public final class FrameTick {
    private static final int MAX_FAILURES = 10;
    private static int failures;
    private static boolean disabled;
    private static long lastReport;
    private static final PlayerState reportState = new PlayerState();

    private FrameTick() {}

    public static void run() {
        if (disabled) return;
        try {
            PuppetDriver.maintainPlacement();
            SteveControl.update(PuppetDriver.localPlayer());
            InputForwarder.forward();
            WorldExporter.tick(PuppetDriver.localPlayer());
            TreeBridge.tick();
            ExplosionBridge.tick();
            GroundBridge.tick();
            ItemExporter.tickAuto(PuppetDriver.localPlayer());
            ContainerBridge.tick(PuppetDriver.localPlayer());
            MirrorBridge.tick(PuppetDriver.localPlayer());
            HudBridge.tick(PuppetDriver.localPlayer());
            PuppetDriver.finishRespawn();
            ClockBridge.tick(PuppetDriver.localPlayer());
            InteractionBridge.tick(PuppetDriver.localPlayer());
            LightBridge.tick();
            WeatherBridge.tick();
            PassengerBridge.tick();
            ActorTerrain.tick();
            Gameplay.tick(PuppetDriver.localPlayer());
            report();
        } catch (Throwable t) {
            failed("frame tick", t);
        }
    }

    /** True when this character is the local player and Steve is driving, so Viewpoint should not draw the PZ body. */
    public static boolean hidesOwnModel(zombie.characters.IsoGameCharacter c) {
        try {
            return c != null && c == zombie.characters.IsoPlayer.getInstance()
                    && ViewpointBridge.viewEnabled() && LinkService.minecraftReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void puppet(zombie.characters.IsoPlayer player) {
        if (disabled) return;
        try {
            PuppetDriver.apply(player);
        } catch (Throwable t) {
            failed("puppet", t);
        }
    }

    private static void failed(String where, Throwable t) {
        failures++;
        Log.error(where + " failed (" + failures + "/" + MAX_FAILURES + ")", t);
        if (failures >= MAX_FAILURES) {
            disabled = true;
            Log.info("too many failures: PzCraft is switching itself off for this session so PZ keeps running");
        }
    }

    private static void report() {
        long now = System.currentTimeMillis();
        if (now - lastReport < 5000) return;
        lastReport = now;
        float[] fr = ViewpointBridge.latestFrame();
        zombie.characters.IsoPlayer pl = PuppetDriver.localPlayer();
        if (fr != null && pl != null) {
            Log.info(String.format("vpFrame cam=(%.3f,%.3f,%.3f) eye=(%.3f,%.3f,%.3f) yaw=%.3f pitch=%.3f | player=(%.3f,%.3f,%.3f)",
                    fr[0], fr[1], fr[2], fr[3], fr[4], fr[5], fr[6], fr[7], pl.getX(), pl.getY(), pl.getZ()));
        }
        String vp = ViewpointBridge.available() ? ("viewpoint ok, 3D " + (ViewpointBridge.viewEnabled() ? "on" : "off")) : "viewpoint NOT found";
        if (!LinkService.connected()) {
            Log.info("waiting for Minecraft (" + vp + ")");
        } else if (!LinkService.pullInto(reportState)) {
            Log.info("link up, waiting for a Minecraft pose (" + vp + ")");
        } else {
            Log.info(String.format("link up rtt=%.1f ms ready=%b %s (%s) input[%s] zombies=%d", LinkService.rttMs(), LinkService.minecraftReady(), reportState, vp, InputForwarder.statusText(), Gameplay.lastActorCount));
        }
    }
}


