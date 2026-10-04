package pzcraft.mc.mixin;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.EntitySceneExporter;
import pzcraft.mc.Session;

/**
 * While PZ draws the world natively, particles are drawn there too (EntitySceneExporter), behind PZ walls and Minecraft
 * blocks where they belong. Minecraft's own particle pass would paint them over everything in the video layer, which
 * has no depth for PZ's walls, so it is skipped; the extracted particles stay for the exporter.
 */
@Mixin(ParticlesRenderState.class)
public abstract class ParticlesSubmitMixin {
    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    private void pzcraft$nativeParticles(SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        if (Session.nativeWorld && !EntitySceneExporter.isNativeCollector(collector)) ci.cancel();
    }
}

