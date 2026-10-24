package pzcraft.mc.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzFire;

/** Used on PZ ground, a PZ floor or wall, it starts PZ's own fire there (PzFire); elsewhere vanilla. */
@Mixin(FireChargeItem.class)
public abstract class FireChargeMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void pzcraft$lightPz(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (PzFire.useOnPz(context, true)) cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
