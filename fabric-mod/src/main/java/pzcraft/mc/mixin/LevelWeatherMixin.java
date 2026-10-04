package pzcraft.mc.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/** PZ has its own weather; Minecraft's rain and thunder must not show up in the overlay. Client side only. */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {
    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noRain(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (Session.pzConnected && ((Level) (Object) this).isClientSide()) cir.setReturnValue(0.0F);
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noThunder(float partialTicks, CallbackInfoReturnable<Float> cir) {
        if (Session.pzConnected && ((Level) (Object) this).isClientSide()) cir.setReturnValue(0.0F);
    }
}

