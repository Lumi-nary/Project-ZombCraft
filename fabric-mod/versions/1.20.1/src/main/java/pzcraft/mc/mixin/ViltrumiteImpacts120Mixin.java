package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.ModImpacts120;

/**
 * Viltrumite Core (optional): its punch crater, super punch, thunderclap and hurled mobs break the blocks they look at;
 * the area they look at also breaks PZ's objects (ModImpacts120). Without Viltrumite this does nothing.
 */
@Pseudo
@Mixin(targets = {
        "dev.baranhan.viltrumitecore.util.PunchImpactManager",
        "dev.baranhan.viltrumitecore.util.ChargePunchManager",
        "dev.baranhan.viltrumitecore.util.ThunderClapManager"}, remap = false)
public abstract class ViltrumiteImpacts120Mixin {
    @WrapOperation(method = {"executePunch", "tickMeteorPhysics", "executeSuperPunch", "tickThunderclaps"}, require = 0,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState pzcraft$area(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
        ModImpacts120.touch(level, pos);
        return original.call(level, pos);
    }

    @Inject(method = {"executePunch", "tickMeteorPhysics", "executeSuperPunch", "tickThunderclaps"}, at = @At("RETURN"), require = 0)
    private static void pzcraft$hitPz(CallbackInfo ci) {
        ModImpacts120.flush();
    }
}
