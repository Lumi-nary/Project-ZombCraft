package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.*;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.*;

@Mixin(WalkNodeEvaluator.class)
public abstract class TerrainPathMixin {
    @Inject(method="getFloorLevel(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)D",at=@At("RETURN"),cancellable=true)
    private static void floor(BlockGetter level,BlockPos pos,CallbackInfoReturnable<Double> ci){
        if(!Session.pzConnected)return;
        var shape=CollisionField.localShape(pos.below());
        if(!shape.isEmpty())ci.setReturnValue(Math.max(ci.getReturnValue(),pos.getY()-1+shape.max(Direction.Axis.Y)));
    }
    @Inject(method="hasCollisions",at=@At("HEAD"),cancellable=true)
    private void clearance(AABB box,CallbackInfoReturnable<Boolean> ci){
        if(Session.pzConnected&&CollisionField.overlapsDeeply(box,.001))ci.setReturnValue(true);
    }
}

