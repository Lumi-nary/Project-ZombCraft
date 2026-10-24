package pzcraft.mc.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzFire;

/** Used on PZ ground, a PZ floor or wall, it starts PZ's own fire there (PzFire); elsewhere vanilla. */
@Mixin(FlintAndSteelItem.class)
public abstract class FlintAndSteelMixin {
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void pzcraft$lightPz(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (PzFire.useOnPz(context, false)) cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
