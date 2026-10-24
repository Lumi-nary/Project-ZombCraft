package pzcraft.mc.mixin;

//#if MC_26_1
//#else
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
//#endif
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
//#if MC_26_1
//$ // Not ported to 26.1.2 (no GlSurface; presenting is RenderSystem.flipFrame, which also polls window events): an empty mixin.
//$ @Mixin(com.mojang.blaze3d.platform.Window.class)
//$ public abstract class GlSurfaceMixin {
//$ }
//#else
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
//#endif
