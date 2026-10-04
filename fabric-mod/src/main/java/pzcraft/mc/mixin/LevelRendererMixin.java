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
    /** Preserve extracted effects (especially mining cracks) until the native collector reads them. */
    @Inject(method="submitFeatures",at=@At("HEAD"),cancellable=true)
    private void pzcraft$nativeFeatures(CallbackInfo ci) {
        if(Session.nativeWorld)ci.cancel();
    }
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean pzcraft$nativeOutline(boolean renderOutline) {
        return !Session.nativeWorld && renderOutline;
    }
    /** shouldRenderSky is the second boolean parameter (renderOutline, shouldRenderSky, consistentDepthRequired). */
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private boolean pzcraft$noSky(boolean shouldRenderSky) {
        return !Session.pzConnected && shouldRenderSky;
    }

    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true)
    private Vector4f pzcraft$transparentBackground(Vector4f fogColor) {
        return Session.pzConnected ? new Vector4f(0f, 0f, 0f, 0f) : fogColor;
    }
}

