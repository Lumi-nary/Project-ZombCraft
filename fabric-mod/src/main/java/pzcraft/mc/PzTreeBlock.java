package pzcraft.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.entity.player.Player;
import pzcraft.protocol.Materials;

/**
 * Stands in for a PZ tree. PZ draws the tree itself, so this block is invisible: it exists so Steve can aim at the trunk,
 * punch it with vanilla mining rules (hands, axes, Efficiency, Haste, cracks, sounds, hunger) and collide with it. Its
 * state carries the PZ tree's size (which sets how long it takes to fell) and species (which log it drops). A tree is a
 * short stack of these blocks; breaking any of them fells the whole tree and tells PZ to topple the real one.
 */
public final class PzTreeBlock extends Block {
    public static final IntegerProperty SIZE = IntegerProperty.create("size", 1, 8);
    public static final IntegerProperty KIND = IntegerProperty.create("kind", 0, Materials.TREE_KINDS - 1);
    private static final VoxelShape TRUNK = Shapes.box(0.25, 0.0, 0.25, 0.75, 1.0, 0.75);

    public PzTreeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SIZE, 3).setValue(KIND, Materials.TREE_OAK));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SIZE, KIND);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return TRUNK; }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return TRUNK; }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }

    /** PZ trees have health (log yield - 1) * 80, at least 40: a size 3 tree is as tough as a vanilla log, a size 8 one seven times. */
    public static float toughness(int size) {
        int[] yield = {1, 1, 2, 3, 4, 5, 6, 8};
        int clamped = Math.max(1, Math.min(8, size));
        return Math.max(40, (yield[clamped - 1] - 1) * 80) / 80f;
    }

    /** Vanilla mining progress per tick, scaled by how big the PZ tree is. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return super.getDestroyProgress(state, player, level, pos) / toughness(state.getValue(SIZE));
    }

    /**
     * Called when the block was removed by gameplay (mining, an explosion, a command), but not by {@link TreeBridge}'s own
     * quiet placement and removal: the tree is felled. PZ topples the real tree and Minecraft drops its logs.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        TreeBridge.felled(level, pos, state);
    }
}

