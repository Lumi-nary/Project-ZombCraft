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

/**
 * Snowballs, eggs, ender pearls, fireballs, potions, wind charges, fishing hooks and reach attacks find what they hit with
 * a block ray, which knows nothing of PZ's walls: they flew through them. The ray now stops at PZ's boxes too.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    private static final String CLIP =
            "Lnet/minecraft/world/level/Level;clipIncludingBorder(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;";

    @WrapOperation(
            method = {
                    "getHitResult(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;FLnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;",
                    "getHitEntitiesAlong(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Ljava/util/function/Predicate;Lnet/minecraft/world/phys/Vec3;FLnet/minecraft/world/level/ClipContext$Block;)Lcom/mojang/datafixers/util/Either;",
                    "getManyEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;FLnet/minecraft/world/level/ClipContext$Block;ZZ)Ljava/util/Collection;"
            },
            at = @At(value = "INVOKE", target = CLIP))
    private static BlockHitResult pzcraft$stopAtPz(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return CollisionField.clip(context.getFrom(), context.getTo(), original.call(level, context));
    }
}
