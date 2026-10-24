package pzcraft.mc.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/**
 * 1.20.1 version of LevelRendererMixin's transparent background: 1.20.1's fog shader blends by the fog colour's alpha
 * (fog.glsl), so a fog colour of alpha 0 is no fog at all, as 26.x gets from its transparent fog colour. Without it
 * distant blocks fade to Minecraft's sky colour over PZ's picture.
 */
@Mixin(FogRenderer.class)
public abstract class Fog120Mixin {
    /** The overlay uses premultiplied alpha: transparent pixels must also have zero RGB. */
    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void pzcraft$transparentClear(CallbackInfo ci) {
        if (Session.pzConnected) RenderSystem.clearColor(0f, 0f, 0f, 0f);
    }

    @Inject(method = "levelFogColor", at = @At("TAIL"))
    private static void pzcraft$noFog(CallbackInfo ci) {
        if (Session.pzConnected) RenderSystem.setShaderFogColor(0f, 0f, 0f, 0f);
    }
}
