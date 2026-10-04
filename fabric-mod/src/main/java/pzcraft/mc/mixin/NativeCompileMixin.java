package pzcraft.mc.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;
/** Chunk geometry is supplied by SceneExporter; compiling/sorting a second mesh wastes CPU and GPU memory. */
@Mixin(LevelRenderer.class)
public abstract class NativeCompileMixin {
    @Inject(method="compileSections",at=@At("HEAD"),cancellable=true)
    private void pzcraft$skipCompile(CallbackInfo ci) { if(Session.nativeWorld) ci.cancel(); }
}

