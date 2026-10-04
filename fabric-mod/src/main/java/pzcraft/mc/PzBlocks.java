package pzcraft.mc;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/** The few blocks PzCraft registers. They stand in for PZ things and are never craftable or obtainable as items. */
public final class PzBlocks {
    public static final String NAMESPACE = "pzcraft";
    /** Invisible stand-in for a PZ tree (see {@link PzTreeBlock}). */
    public static Block TREE;

    private PzBlocks() {}

    /** Main entrypoint: registries are still open here. */
    public static void init() {
        TREE = register("pz_tree", PzTreeBlock::new, BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0f, 2.0f)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .noLootTable()
                .pushReaction(PushReaction.IMMOVEABLE)
                .isViewBlocking((state, level, pos, box) -> false)
                .isSuffocating((state, level, pos) -> false)
                .isRedstoneConductor((state, level, pos) -> false));
    }

    private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory,
                                  BlockBehaviour.Properties properties) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(NAMESPACE, name));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
    }
}

