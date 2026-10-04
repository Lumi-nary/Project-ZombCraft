package pzcraft.mc.mixin;

import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.*;

/** Check dynamic PZ geometry before vanilla's block-state-only occlusion cache. */
@Mixin(FlowingFluid.class)
public abstract class FluidFlowMixin {
    @Inject(method="canPassThroughWall",at=@At("HEAD"),cancellable=true)
    private static void terrain(Direction direction,BlockGetter level,BlockPos from,BlockState source,BlockPos to,BlockState target,CallbackInfoReturnable<Boolean> ci){
        if(Session.pzConnected&&CollisionField.blocksFlow(from,to))ci.setReturnValue(false);
    }
}

