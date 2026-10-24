package pzcraft.mc.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** 1.20.1 stand-in for 26.x's Minecraft.disconnectFromWorld (renames.txt). */
public final class Worlds {
    private Worlds() {}

    /** 26.x's CollisionContext.placementContext: the placing player's context, or none (1.20.1's of(null) throws). */
    public static net.minecraft.world.phys.shapes.CollisionContext placementContext(net.minecraft.world.entity.player.Player player) {
        return player == null ? net.minecraft.world.phys.shapes.CollisionContext.empty() : net.minecraft.world.phys.shapes.CollisionContext.of(player);
    }

    /** What 1.20.1's pause screen does for "Save and Quit": the world is saved as the integrated server stops. */
    public static void leave(Minecraft mc, Component message) {
        if (mc.level != null) mc.level.disconnect();
        mc.clearLevel(new GenericDirtMessageScreen(message));
        mc.setScreen(new TitleScreen()); // WorldBootstrap waits for the title screen before opening the next world
    }
}
