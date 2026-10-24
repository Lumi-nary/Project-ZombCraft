package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.PzFire;

/** Every Minecraft fire that appears (flint and steel, spread, lava, lightning) is watched: at a PZ square PZ burns too. */
@Mixin(BaseFireBlock.class)
public abstract class FirePlaceMixin {
    @Inject(method = "onPlace", at = @At("TAIL"))
    private void pzcraft$watch(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
        PzFire.placed(level, pos);
    }
}
