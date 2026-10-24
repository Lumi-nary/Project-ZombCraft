package pzcraft.mc.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzMining;

/**
 * Attacking a PZ thing under the crosshair mines it ({@link PzMining}); vanilla would punch the empty air it stands in.
 * A held gun is left to GunAttackMixin.
 */
@Mixin(Minecraft.class)
public abstract class PzMiningAttackMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void pzcraft$startMining(CallbackInfoReturnable<Boolean> cir) {
        Boolean ended = PzMining.startAttack((Minecraft) (Object) this);
        if (ended != null) cir.setReturnValue(ended);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void pzcraft$continueMining(boolean down, CallbackInfo ci) {
        if (PzMining.continueAttack((Minecraft) (Object) this, down)) ci.cancel();
    }
}
