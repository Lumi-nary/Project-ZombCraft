package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;
import pzcraft.mc.Session;

/** Expose actual PZ surface support to torches, rails, redstone and other vanilla placement rules. */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class TerrainSupportMixin {
    @Inject(method = "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z", at = @At("RETURN"), cancellable = true)
    private void support(BlockGetter level, BlockPos pos, Direction face, SupportType type, CallbackInfoReturnable<Boolean> ci) {
        if (Session.pzConnected && !ci.getReturnValue() && CollisionField.supports(pos, face)) ci.setReturnValue(true);
    }
}

