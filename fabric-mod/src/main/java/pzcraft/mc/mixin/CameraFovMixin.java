package pzcraft.mc.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/** Match Viewpoint's vertical field of view, plus the overscan margin (Minecraft's FOV option is capped lower than Viewpoint allows). */
@Mixin(Camera.class)
public abstract class CameraFovMixin {
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void pzcraft$viewpointFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
        float f = Session.renderFov();
        if (Session.pzConnected && f > 10f && f < 170f) cir.setReturnValue(f);
    }
}

