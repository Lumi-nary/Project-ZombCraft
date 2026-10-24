package pzcraft.mc.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzMining;

/** The crosshair also finds PZ things Steve can mine (grass, bushes, stones, boulders): see {@link PzMining#pick}. */
@Mixin(LocalPlayer.class)
public abstract class PzMiningPickMixin {
    @Inject(method = "raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;", at = @At("RETURN"), cancellable = true)
    private void pzcraft$pzTargets(float partialTicks, Entity camera, CallbackInfoReturnable<HitResult> cir) {
        cir.setReturnValue(PzMining.pick((LocalPlayer) (Object) this, camera, partialTicks, cir.getReturnValue()));
    }
}
