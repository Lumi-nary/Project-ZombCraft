package pzcraft.mc.mixin;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.EntitySceneExporter;
import pzcraft.mc.Session;
@Mixin(EntityRenderDispatcher.class)
public abstract class NativeEntity120Mixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void pzcraft$nativeEntities(CallbackInfo ci) {
        if (Session.nativeWorld && EntitySceneExporter.ready() && !EntitySceneExporter.capturing()) ci.cancel();
    }
}
