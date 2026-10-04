package pzcraft.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.*;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class NativeBlockEntityMixin {
    @Inject(method="submit",at=@At("HEAD"),cancellable=true)
    private void nativeOnly(BlockEntityRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera,CallbackInfo ci){
        if(Session.nativeWorld&&!EntitySceneExporter.isNativeCollector(collector))ci.cancel();
    }
}

