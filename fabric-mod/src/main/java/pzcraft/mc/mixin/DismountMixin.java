package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;
import pzcraft.mc.Session;

/** Beds and vehicles use the same real PZ floor and clearance as Steve's movement. */
@Mixin(DismountHelper.class)
public abstract class DismountMixin {
    @Inject(method = "nonClimbableShape", at = @At("RETURN"), cancellable = true)
    private static void shape(BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> ci) {
        if (Session.pzConnected) ci.setReturnValue(net.minecraft.world.phys.shapes.Shapes.or(ci.getReturnValue(), CollisionField.localShape(pos)));
    }
    @Inject(method = "canDismountTo(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/AABB;)Z", at = @At("RETURN"), cancellable = true)
    private static void clearance(CollisionGetter level, LivingEntity passenger, AABB box, CallbackInfoReturnable<Boolean> ci) {
        if (Session.pzConnected && ci.getReturnValue() && CollisionField.overlapsDeeply(box, 0.001)) ci.setReturnValue(false);
    }
    @Inject(method = "findSafeDismountLocation", at = @At("RETURN"), cancellable = true)
    private static void safe(net.minecraft.world.entity.EntityType<?> type, CollisionGetter level, BlockPos pos, boolean dangerous,
                             CallbackInfoReturnable<net.minecraft.world.phys.Vec3> ci) {
        if (Session.pzConnected && ci.getReturnValue() != null
                && CollisionField.overlapsDeeply(type.getDimensions().makeBoundingBox(ci.getReturnValue()), 0.001)) ci.setReturnValue(null);
    }
}

