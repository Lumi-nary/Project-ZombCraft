package pzcraft.mc;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/**
 * In a PZ car (W5) PZ draws its dashboard across the bottom of the screen, where Minecraft's hotbar and bars are: they are
 * lifted above it (PZ sends how much of the screen's height the dashboard covers with the seat, {@link PzCars#seat}). The
 * crosshair, titles and the rest stay where they are.
 */
final class HudLift {
    private static final Identifier[] BOTTOM = {VanillaHudElements.HOTBAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.SPECTATOR_TOOLTIP,
            VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.CHAT};

    private HudLift() {}

    /** Client entrypoint. */
    static void init() {
        for (Identifier id : BOTTOM) {
            HudElementRegistry.replaceElement(id, element -> (graphics, delta) -> {
                var seat = PzCars.seat;
                float lift = Session.pzConnected && seat.inCar() ? seat.dashboard() * graphics.guiHeight() : 0;
                if (lift < 1) {
                    element.extractRenderState(graphics, delta);
                    return;
                }
                graphics.pose().pushMatrix();
                graphics.pose().translate(0, -Math.round(lift));
                element.extractRenderState(graphics, delta);
                graphics.pose().popMatrix();
            });
        }
    }
}
