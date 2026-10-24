package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzRest;

/**
 * A sleeper in a PZ bed has no bed block under him: vanilla would wake him on the next tick. These tell it the PZ bed is
 * there (while PZ still reports it) and which way it lies, and forget it when he gets up.
 */
@Mixin(LivingEntity.class)
public abstract class PzBedMixin {
    @Shadow public abstract java.util.Optional<BlockPos> getSleepingPos();

    @Inject(method = "checkBedExists", at = @At("HEAD"), cancellable = true)
    private void pzcraft$pzBedExists(CallbackInfoReturnable<Boolean> cir) {
        getSleepingPos().ifPresent(pos -> { if (PzRest.bedAt(pos) != null) cir.setReturnValue(true); });
    }

    @Inject(method = "getBedOrientation", at = @At("HEAD"), cancellable = true)
    private void pzcraft$pzBedOrientation(CallbackInfoReturnable<Direction> cir) {
        getSleepingPos().ifPresent(pos -> {
            Direction d = PzRest.bedAt(pos);
            if (d != null) cir.setReturnValue(d);
        });
    }

    @Inject(method = "stopSleeping", at = @At("HEAD"))
    private void pzcraft$leavePzBed(CallbackInfo ci) {
        getSleepingPos().ifPresent(PzRest::stoppedSleeping);
    }
}
