package pzcraft.mc.mixin;

import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/** PZ draws Minecraft terrain and effects through the native exporters. */
@Mixin(ChunkSectionsToRender.class)
public abstract class NativeTerrainMixin {
    @Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true)
    private void pzcraft$nativeTerrain(CallbackInfo ci) {
        if (Session.nativeWorld) ci.cancel();
    }
}

