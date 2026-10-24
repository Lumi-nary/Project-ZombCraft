package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import pzcraft.mc.PzMining;

/**
 * Better Combat (optional) swings a held weapon instead of mining when the block under the crosshair is air, and a PZ
 * thing stands in an air voxel. Its check sees the thing's stand-in block instead: stones, bushes and boulders are mined;
 * grass keeps Better Combat's own "swing through grass", as vanilla grass does. Without Better Combat this does nothing.
 */
@Pseudo
@Mixin(targets = "net.bettercombat.client.AttackInteractor")
public abstract class BetterCombatMiningMixin {
    @WrapOperation(method = "isTargetingMineableBlock", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState pzcraft$pzTarget(ClientLevel level, BlockPos pos, Operation<BlockState> original) {
        return PzMining.standInAt(pos, original.call(level, pos));
    }
}
