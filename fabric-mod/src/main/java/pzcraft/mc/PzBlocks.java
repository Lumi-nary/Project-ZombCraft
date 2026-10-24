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

/** The few blocks Project-ZombCraft registers. They stand in for PZ things and are never craftable or obtainable as items. */
public final class PzBlocks {
    /** The mod's id and the namespace of everything it registers (owner, 2026-10-16: "rename mods to Project-ZombCraft"). */
    public static final String NAMESPACE = "project-zombcraft";
    /** What the mod was called before: saved items, blocks and item models with it still load ({@link #aliasOldIds}). */
    public static final String OLD_NAMESPACE = "pzcraft";
    /** Invisible stand-in for a PZ tree (see {@link PzTreeBlock}). */
    public static Block TREE;

    private PzBlocks() {}

    /**
     * Saves made before the rename hold "pzcraft:..." items, blocks and entities: each of ours answers to its old id too.
     * Main entrypoint, after everything is registered.
     */
    static void aliasOldIds() {
        int n = 0;
        n += alias(net.minecraft.core.registries.BuiltInRegistries.ITEM);
        n += alias(net.minecraft.core.registries.BuiltInRegistries.BLOCK);
        n += alias(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE);
        PzCraftMain.LOG.info("{} ids of ours also answer to their old \"{}:\" names", n, OLD_NAMESPACE);
    }

    private static int alias(net.minecraft.core.Registry<?> registry) {
        int n = 0;
        for (Identifier id : java.util.List.copyOf(registry.keySet())) {
            if (!id.getNamespace().equals(NAMESPACE)) continue;
            ((net.fabricmc.fabric.api.event.registry.FabricRegistry) registry).addAlias(Identifier.fromNamespaceAndPath(OLD_NAMESPACE, id.getPath()), id);
            n++;
        }
        return n;
    }

    /** A saved stack's item model from before the rename ("pzcraft:pz/..."), moved to the new namespace; else unchanged. */
    public static Identifier renamed(Identifier model) {
        return model != null && model.getNamespace().equals(OLD_NAMESPACE) ? Identifier.fromNamespaceAndPath(NAMESPACE, model.getPath()) : model;
    }

    /** Main entrypoint: registries are still open here. */
    public static void init() {
        TREE = register("pz_tree", PzTreeBlock::new, BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0f, 2.0f)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .noLootTable()
                //#if MC_26_1
                //$ .pushReaction(PushReaction.BLOCK)
                //#else
                .pushReaction(PushReaction.IMMOVEABLE)
                //#endif
                //#if MC_26_1
                //$ .isViewBlocking((state, level, pos) -> false)
                //#else
                .isViewBlocking((state, level, pos, box) -> false)
                //#endif
                .isSuffocating((state, level, pos) -> false)
                .isRedstoneConductor((state, level, pos) -> false));
    }

    private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory,
                                  BlockBehaviour.Properties properties) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(NAMESPACE, name));
        return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
    }
}
