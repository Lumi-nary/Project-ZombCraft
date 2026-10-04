package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.pathfinder.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.*;

/** Dynamic terrain is outside vanilla's block-state path cache. Query it at the point of use. */
@Mixin(PathfindingContext.class)
public abstract class PathContextMixin {
    @Inject(method="getPathTypeFromState",at=@At("RETURN"),cancellable=true)
    private void terrain(int x,int y,int z,CallbackInfoReturnable<PathType> ci){
        if(!Session.pzConnected||ci.getReturnValue()!=PathType.OPEN)return;
        var shape=CollisionField.localShape(new BlockPos(x,y,z));
        if(!shape.isEmpty()&&shape.max(Direction.Axis.Y)>.5)ci.setReturnValue(PathType.BLOCKED);
    }
}

