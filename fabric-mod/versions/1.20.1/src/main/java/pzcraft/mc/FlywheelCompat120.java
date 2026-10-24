package pzcraft.mc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Flywheel (bundled with Create 6) draws block entities and entities it "visualizes" (Create's shafts, cogwheels,
 * belts, contraptions; vanilla chests, bells, minecarts) on the GPU straight into Minecraft's picture, skipping their
 * block entity / entity renderers. With PZ drawing the world natively those parts then reached PZ only as flat video,
 * on top of the native copy of their block model: Create's cogwheels showed twice, one of them standing still.
 *
 * While Minecraft's world is drawn natively by PZ, Flywheel is told visualization is unsupported
 * (FlywheelVisuals120Mixin). Create then falls back to its own block entity renderers, which EntitySceneExporter
 * captures every frame as native PZ geometry, rotating, lit and depth-tested like any other block entity. When the
 * state changes Flywheel's managers are rebuilt, as Flywheel does itself when its backend changes.
 */
public final class FlywheelCompat120 {
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("flywheel");
    private static boolean applied;

    private FlywheelCompat120() {}

    /** Asked by Flywheel's supportsVisualization: true while PZ draws Minecraft's world. */
    public static boolean suppressed() { return Session.nativeWorld; }

    /** Render thread, every frame. */
    public static void frame(Minecraft mc) {
        if (!PRESENT) return;
        boolean want = Session.nativeWorld;
        if (want == applied) return;
        applied = want;
        mc.tell(() -> {
            try {
                Class.forName("dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl")
                        .getMethod("resetAll").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                PzCraftMain.LOG.warn("Flywheel reset failed; Create parts may need a chunk reload (F3+A)", e);
            }
            if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
            PzCraftMain.LOG.info("Flywheel visualization {} (PZ draws the world: {})", want ? "off" : "on", want);
        });
    }
}
