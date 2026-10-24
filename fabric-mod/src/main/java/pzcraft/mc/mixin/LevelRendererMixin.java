package pzcraft.mc.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/**
 * While PZ is connected, Minecraft's world layer is only the blocks Steve has placed (plus the hand and HUD): PZ draws
 * the real world. So render the level without the sky and with a fully transparent black background, so that only
 * Minecraft's own geometry ends up in the overlay picture.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    //#if MC_26_1
    //$ /** 26.1.2 submits inline in its main pass and keeps the extracted lists: skip the submits (the exporter reads the lists). */
    //$ @Inject(method={"submitEntities","submitBlockEntities","submitBlockDestroyAnimation"},at=@At("HEAD"),cancellable=true)
    //#else
    /** Preserve extracted effects (especially mining cracks) until the native collector reads them. */
    @Inject(method="submitFeatures",at=@At("HEAD"),cancellable=true)
    //#endif
    private void pzcraft$nativeFeatures(CallbackInfo ci) {
        if(Session.nativeWorld)ci.cancel();
    }
    //#if MC_26_1
    //$ @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    //#else
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    //#endif
    private boolean pzcraft$nativeOutline(boolean renderOutline) {
        return !Session.nativeWorld && renderOutline;
    }
    /** shouldRenderSky is the second boolean parameter (renderOutline, shouldRenderSky, consistentDepthRequired). */
    //#if MC_26_1
    //$ @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    //#else
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    //#endif
    private boolean pzcraft$noSky(boolean shouldRenderSky) {
        return !Session.pzConnected && shouldRenderSky;
    }

    //#if MC_26_1
    //$ @ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true)
    //#else
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    //#endif
    private Vector4f pzcraft$transparentBackground(Vector4f fogColor) {
        return Session.pzConnected ? new Vector4f(0f, 0f, 0f, 0f) : fogColor;
    }
}
