package pzcraft.mc.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzGuns;
import pzcraft.mc.Session;

@Mixin(value = Minecraft.class, priority = 1100)
public abstract class GunAttackMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void pzcraft$fire(CallbackInfoReturnable<Boolean> cir) {
        if (PzGuns.attack((Minecraft)(Object)this)) cir.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noGunMining(boolean held, CallbackInfo ci) {
        Minecraft mc = (Minecraft)(Object)this;
        if (Session.pzConnected && mc.player != null && PzGuns.held(mc.player.getMainHandItem())) ci.cancel();
    }
}

