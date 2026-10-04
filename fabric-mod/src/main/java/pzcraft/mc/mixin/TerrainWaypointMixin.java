package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.*;

@Mixin(PathNavigation.class)
public abstract class TerrainWaypointMixin {
    @Shadow @Final protected Level level;
    @Inject(method="getGroundY",at=@At("RETURN"),cancellable=true)
    private void floor(Vec3 target,CallbackInfoReturnable<Double> ci) {
        if(!Session.pzConnected)return;
        var pos=BlockPos.containing(target);var shape=CollisionField.localShape(pos.below());
        if(!shape.isEmpty()) {
            double y=pos.getY()-1+shape.max(Direction.Axis.Y);
            ci.setReturnValue(level.getBlockState(pos.below()).isAir()?y:Math.max(y,ci.getReturnValue()));
        }
    }
    @Inject(method="isStableDestination",at=@At("RETURN"),cancellable=true)
    private void support(BlockPos pos,CallbackInfoReturnable<Boolean> ci) {
        if(Session.pzConnected&&!ci.getReturnValue()&&!CollisionField.localShape(pos.below()).isEmpty())ci.setReturnValue(true);
    }
}

