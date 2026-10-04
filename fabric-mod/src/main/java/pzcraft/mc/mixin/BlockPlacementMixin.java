package pzcraft.mc.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;
import pzcraft.mc.Session;

/**
 * PZ's walls, furniture, floors and cars are collision boxes, not blocks, so vanilla placement would happily put a block
 * inside them. Refuse placements that would sink into PZ geometry; blocks may still sit flush against it.
 */
@Mixin(BlockItem.class)
public abstract class BlockPlacementMixin {
    private static final double ALLOWED_OVERLAP = 0.12;

    @Inject(method = "canPlace", at = @At("RETURN"), cancellable = true)
    private void pzcraft$notInsidePz(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !Session.pzConnected) return;
        VoxelShape shape = state.getCollisionShape(context.getLevel(), context.getClickedPos(), CollisionContext.placementContext(context.getPlayer()));
        if (shape.isEmpty()) return; // flowers, torches, rails: nothing to collide
        for (AABB box : shape.toAabbs()) {
            if (CollisionField.overlapsDeeply(box.move(context.getClickedPos()), ALLOWED_OVERLAP)) {
                cir.setReturnValue(false);
                return;
            }
        }
    }
}

