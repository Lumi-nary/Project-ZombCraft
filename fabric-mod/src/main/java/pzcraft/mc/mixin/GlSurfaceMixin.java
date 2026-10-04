package pzcraft.mc.mixin;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/**
 * Experiment, off by default (-Dpzcraft.skipPresent=true): nobody looks at Minecraft's own window, so skip the blit to the
 * swapchain and the buffer swap. Measured on the dev PC it is slower (30 fps / 68 ms against 47 fps / 43 ms): the swap is
 * what pushes the frame's commands to the GPU promptly, and a plain glFlush in its place did not recover that.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlSurface")
public abstract class GlSurfaceMixin {
    private static final boolean SKIP = Boolean.getBoolean("pzcraft.skipPresent");

    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noBlit(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
        if (SKIP && Session.pzConnected && Session.released) ci.cancel();
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noPresent(CallbackInfo ci) {
        if (SKIP && Session.pzConnected && Session.released) {
            org.lwjgl.opengl.GL11C.glFlush();
            ci.cancel();
        }
    }
}

