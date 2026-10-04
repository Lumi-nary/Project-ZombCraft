package pzcraft.mc.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/**
 * Minecraft drops to 30 fps after 60 s without its own input and to 10 fps after 10 min or when its window is minimized.
 * PZ feeds input over the shared link, so Minecraft always looks idle; with the throttle on, the overlay would get slower
 * and laggier the longer the game runs. While PZ is connected, never throttle.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateThrottleMixin {
    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noThrottle(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        if (Session.pzConnected) cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
    }
}

