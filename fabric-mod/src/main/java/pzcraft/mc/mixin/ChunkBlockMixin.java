package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;
import pzcraft.protocol.Wire;

/** Tells PZ whenever a block becomes solid or stops being solid, so PZ's zombies can treat it as an obstacle. */
@Mixin(LevelChunk.class)
public abstract class ChunkBlockMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void pzcraft$reportBlock(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        // Tree blocks stand in for PZ trees: invisible, and PZ already treats the tile as a tree.
        if (pzcraft.mc.PzBlocks.TREE != null && (state.is(pzcraft.mc.PzBlocks.TREE) || old != null && old.is(pzcraft.mc.PzBlocks.TREE))) return;
        boolean server = ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel;
        // Ground being made real is not an edit: nothing changes on screen, and PZ must not hear of it.
        if (pos.getY() < 0 && (server ? pzcraft.mc.GroundBridge.isQuiet() : pzcraft.mc.GroundBridge.consumeFresh(pos))) return;
        if (old != null) pzcraft.mc.BlockSceneExporter.changed();
        if (old == null || !Session.pzConnected || !server) return;
        if (pos.getY() < 0) {
            pzcraft.mc.GroundBridge.onChange(pos, old, state); // dug or refilled ground: PZ's floor follows
            return;                                           // PZ has no solid blocks below its ground
        }
        boolean was = old.isSolid(), now = state.isSolid();
        if (was != now) {
            Session.send(Wire.MSG_BLOCK, Wire.encodeBlock(pos.getX(), pos.getY(), pos.getZ(), now));
        }
    }
}

