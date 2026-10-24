package pzcraft.mc.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/** 1.20.1 version of CameraFovMixin: the world is drawn with PZ's field of view (GameRenderer.getFov; the hand keeps its own). */
@Mixin(GameRenderer.class)
public abstract class CameraFov120Mixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void pzcraft$viewpointFov(Camera camera, float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        float f = Session.renderFov();
        if (useFovSetting && Session.pzConnected && f > 10f && f < 170f) cir.setReturnValue((double) f);
    }
}
