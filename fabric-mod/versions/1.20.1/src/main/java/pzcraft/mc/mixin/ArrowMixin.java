package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.CollisionField;

/**
 * Arrows and tridents stop at PZ's walls, doors, windows, furniture, floors and cars instead of flying through them. A PZ
 * hit is a block hit on an air voxel, so vanilla sticks the arrow there and keeps it (its remembered block, air, never
 * changes); it falls once PZ's box is gone (the wall was broken, the car drove off).
 */
@Mixin(AbstractArrow.class)
public abstract class ArrowMixin {
    @Shadow private @Nullable BlockState lastState;

    @Shadow protected boolean inGround;

    @Shadow private void startFalling() {}

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult pzcraft$stopAtPz(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return CollisionField.clip(context.getFrom(), context.getTo(), original.call(level, context));
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void pzcraft$fallWhenPzBoxGone(CallbackInfo ci) {
        AbstractArrow self = (AbstractArrow) (Object) this;
        if (self.level().isClientSide() || !inGround || lastState == null || !lastState.isAir()) return;
        if (!CollisionField.holdsProjectile(self.position())) { startFalling(); return; }
        if (!pzcraft$litPz && self.isOnFire()) {   // a burning arrow in PZ geometry starts PZ's fire there
            pzcraft$litPz = true;
            pzcraft.mc.PzFire.arrow(self);
        }
    }

    @org.spongepowered.asm.mixin.Unique private boolean pzcraft$litPz;
}
