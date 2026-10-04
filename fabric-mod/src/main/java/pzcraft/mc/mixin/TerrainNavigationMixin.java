package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.*;

/** Vanilla's air-column scan would move every PZ target to the void world's build ceiling. */
@Mixin(GroundPathNavigation.class)
public abstract class TerrainNavigationMixin {
    @Inject(method="findSurfacePosition",at=@At("HEAD"),cancellable=true)
    private void surface(LevelChunk chunk,BlockPos pos,int reach,CallbackInfoReturnable<BlockPos> ci) {
        if(!Session.pzConnected)return;
        var below=CollisionField.localShape(pos.below());
        if(!below.isEmpty()){ci.setReturnValue(pos);return;}
        var here=CollisionField.localShape(pos);
        if(!here.isEmpty()&&here.max(net.minecraft.core.Direction.Axis.Y)<.9)ci.setReturnValue(pos.above());
    }
}

