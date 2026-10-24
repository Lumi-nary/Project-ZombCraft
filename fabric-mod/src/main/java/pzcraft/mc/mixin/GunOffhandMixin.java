package pzcraft.mc.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzGuns;

/**
 * Two hands are on the pistol, so as far as the local player is concerned the off-hand is empty while it is in the main hand:
 * the first-person hands, the hotbar and the model never get to draw an off-hand item, even for the tick or two before the
 * server has moved it back to the inventory ({@link PzGuns#init}).
 */
@Mixin(value = LivingEntity.class, priority = 1100)
public abstract class GunOffhandMixin {
    @Inject(method = "getOffhandItem", at = @At("HEAD"), cancellable = true)
    private void pzcraft$emptyOffhandWithGun(CallbackInfoReturnable<ItemStack> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof LocalPlayer && PzGuns.held(self.getMainHandItem())) cir.setReturnValue(ItemStack.EMPTY);
    }
}
