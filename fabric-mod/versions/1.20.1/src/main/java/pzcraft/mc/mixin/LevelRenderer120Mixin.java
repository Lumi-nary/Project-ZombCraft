package pzcraft.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/**
 * 1.20.1 version of LevelRendererMixin's video-layer part: while PZ is connected Minecraft draws no sky, so its world
 * layer is transparent wherever there are no blocks and PZ's own picture shows through (1.20.1 already clears to alpha 0;
 * the sky dome is what made the layer opaque). The native-scene parts of the 26.x mixin are not needed: 1.20.1 has no
 * native scene export yet.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRenderer120Mixin {
    /** With native blocks PZ draws the terrain: skip Minecraft's own chunk layers (the world layer keeps entities). */
    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void pzcraft$nativeTerrain(net.minecraft.client.renderer.RenderType layer, PoseStack poseStack, double x, double y, double z,
                                       Matrix4f projection, CallbackInfo ci) {
        if (Session.nativeWorld) ci.cancel();
    }

    /** PZ draws the selection box of native blocks (SceneLink.writeSelection). */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "renderLevel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean pzcraft$nativeOutline(boolean renderOutline) {
        return !Session.nativeWorld && renderOutline;
    }

    /** Create's Flywheel visuals follow whether PZ draws the world (FlywheelCompat120). */
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void pzcraft$flywheel(CallbackInfo ci) {
        pzcraft.mc.FlywheelCompat120.frame(net.minecraft.client.Minecraft.getInstance());
    }

    @Inject(method = "renderSky(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void pzcraft$noSky(PoseStack poseStack, Matrix4f projection, float partialTick, Camera camera, boolean foggy, Runnable setupFog, CallbackInfo ci) {
        if (Session.pzConnected) ci.cancel();
    }
}
