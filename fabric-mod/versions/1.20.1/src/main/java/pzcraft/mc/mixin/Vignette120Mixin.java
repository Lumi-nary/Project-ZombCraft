package pzcraft.mc.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/**
 * 1.20.1 only: the vignette (fancy graphics) blends alpha as (ONE, ZERO), so it writes the texture's opaque alpha over the
 * whole screen and the HUD layer PZ draws over its own picture became a black sheet. PZ is the picture here: no vignette
 * while it is connected (the world border tint goes with it).
 */
@Mixin(Gui.class)
public abstract class Vignette120Mixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noVignette(GuiGraphics graphics, Entity entity, CallbackInfo ci) {
        if (Session.pzConnected) ci.cancel();
    }
}
