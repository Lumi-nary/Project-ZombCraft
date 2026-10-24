package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pzcraft.mc.CollisionField;

/** Minecraft's mobs cannot see, target or shoot through PZ's walls, closed doors, floors and cars. */
@Mixin(LivingEntity.class)
public abstract class LineOfSightMixin {
    @WrapOperation(
            method = "hasLineOfSight(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;D)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult pzcraft$pzBlocksSight(Level level, ClipContext context, Operation<BlockHitResult> original) {
        return CollisionField.clip(context.getFrom(), context.getTo(), original.call(level, context));
    }
}
