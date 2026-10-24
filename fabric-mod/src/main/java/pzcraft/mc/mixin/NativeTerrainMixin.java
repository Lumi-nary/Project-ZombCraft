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
    //#if MC_26_1
    //$ @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true)
    //#else
    @Inject(method = "renderLayers", at = @At("HEAD"), cancellable = true)
    //#endif
    private void pzcraft$nativeTerrain(CallbackInfo ci) {
        if (Session.nativeWorld) ci.cancel();
    }
}
