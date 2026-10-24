package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pzcraft.mc.CollisionField;

/** 1.20.1 version of ProjectileUtilMixin: projectiles stop at PZ's walls (1.20.1's getHitResult clips with Level.clip). */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtil120Mixin {
    @WrapOperation(
            method = "getHitResult(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/phys/HitResult;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult pzcraft$stopAtPz(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return CollisionField.clip(context.getFrom(), context.getTo(), original.call(level, context));
    }
}
