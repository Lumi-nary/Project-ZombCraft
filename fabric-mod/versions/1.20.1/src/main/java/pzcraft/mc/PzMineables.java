package pzcraft.mc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import pzcraft.protocol.Materials;
import pzcraft.protocol.WorldTargets;

/**
 * How Minecraft mines a PZ thing. Each one borrows a vanilla block (its "stand-in") for the tool rules, sounds and
 * particles: a pickaxe for rock, an axe for wood, shears and hoes for plants, with Efficiency, Haste, Mining Fatigue,
 * water and being off the ground exactly as for that block. Its own hardness sets how long it takes; a thing that needs
 * the right tool (rock) drops nothing without it, as stone does.
 */
public final class PzMineables {
    public record Def(BlockState standIn, float hardness, boolean requiresTool) {
        /** Whether the player's main-hand item gets the drops (vanilla's harvest check, against the stand-in). */
        public boolean harvests(Player player) {
            return !requiresTool || player.getMainHandItem().isCorrectToolForDrops(standIn);
        }

        /** Vanilla's mining progress per tick (BlockBehaviour.getDestroyProgress), with this thing's hardness; creative breaks at once. */
        public float progressPerTick(Player player) {
            if (hardness <= 0 || player.isCreative()) return 1f;
            return player.getDestroySpeed(standIn) / hardness / (harvests(player) ? 30f : 100f);
        }
    }

    private PzMineables() {}

    public static Def def(int kind, int variant) {
        return switch (kind) {
            case WorldTargets.KIND_GRASS -> new Def(Blocks.GRASS.defaultBlockState(), 0f, false);
            case WorldTargets.KIND_BUSH -> new Def(Blocks.OAK_LEAVES.defaultBlockState(), 0.6f, false);
            case WorldTargets.KIND_COVER -> switch (variant) {
                case WorldTargets.COVER_TWIGS, WorldTargets.COVER_BRANCH -> new Def(Blocks.OAK_LOG.defaultBlockState(), 0.2f, false);
                case WorldTargets.COVER_LOG -> new Def(Blocks.OAK_LOG.defaultBlockState(), 1.0f, false);
                case WorldTargets.COVER_LIMESTONE -> new Def(Blocks.CALCITE.defaultBlockState(), 0.3f, false);
                case WorldTargets.COVER_LARGE_STONE, WorldTargets.COVER_LARGE_STONE_TWIGS -> new Def(Blocks.COBBLESTONE.defaultBlockState(), 0.6f, false);
                default -> new Def(Blocks.COBBLESTONE.defaultBlockState(), 0.3f, false); // picked up by hand, as in PZ
            };
            case WorldTargets.KIND_BOULDER -> {
                float size = 2f + WorldTargets.boulderSize(variant); // small 2, medium 3, large 4
                yield switch (WorldTargets.boulderKind(variant)) {
                    case WorldTargets.BOULDER_IRON -> new Def(Blocks.IRON_ORE.defaultBlockState(), size, true);
                    case WorldTargets.BOULDER_COPPER -> new Def(Blocks.COPPER_ORE.defaultBlockState(), size, true);
                    case WorldTargets.BOULDER_LIMESTONE -> new Def(Blocks.CALCITE.defaultBlockState(), 2f, true);
                    default -> new Def(Blocks.STONE.defaultBlockState(), 3f, true);
                };
            }
            case WorldTargets.KIND_WINDOW -> WorldTargets.edgeFlag(variant)
                    ? edge(Materials.MAT_WOOD, 2f)                              // a smashed window: its frame and wall
                    : new Def(Blocks.GLASS.defaultBlockState(), 0f, false);     // the first hit smashes the glass
            case WorldTargets.KIND_WALL -> edge(WorldTargets.edgeMaterial(variant), 2f);   // a storey of wall: two blocks' worth
            case WorldTargets.KIND_DOOR -> edge(WorldTargets.edgeMaterial(variant), 1.5f); // vanilla's doors: 3 (wood), 5 (iron)
            case WorldTargets.KIND_FENCE, WorldTargets.KIND_BARRICADE -> edge(WorldTargets.edgeMaterial(variant), 1f);
            case WorldTargets.KIND_FURNITURE -> {                         // always yields PZ's own item: no tool needed for it
                Def d = edge(WorldTargets.edgeMaterial(variant), 1f);
                yield new Def(d.standIn(), d.hardness(), false);
            }
            case WorldTargets.KIND_SOLID -> edge(WorldTargets.edgeMaterial(variant), 1.5f);
            default -> new Def(Blocks.STONE.defaultBlockState(), 3f, true);
        };
    }

    /** A wall, door, fence or barricade of this material: the matching vanilla block's tools, sounds and hardness, times {@code size}. */
    private static Def edge(int material, float size) {
        return switch (material) {
            case Materials.MAT_BRICK -> new Def(Blocks.BRICKS.defaultBlockState(), 2f * size, true);
            case Materials.MAT_STONE -> new Def(Blocks.STONE_BRICKS.defaultBlockState(), 1.5f * size, true);
            case Materials.MAT_CERAMIC -> new Def(Blocks.TERRACOTTA.defaultBlockState(), 1.25f * size, true);
            case Materials.MAT_METAL -> new Def(Blocks.IRON_BLOCK.defaultBlockState(), 3f * size, true);
            case Materials.MAT_METAL_HEAVY -> new Def(Blocks.IRON_BLOCK.defaultBlockState(), 5f * size, true);
            case Materials.MAT_PLASTER -> new Def(Blocks.CALCITE.defaultBlockState(), 0.75f * size, false); // drywall breaks by hand
            case Materials.MAT_GLASS -> new Def(Blocks.GLASS.defaultBlockState(), 0.3f * size, false);
            case Materials.MAT_FABRIC -> new Def(Blocks.WHITE_WOOL.defaultBlockState(), 0.8f * size, false);
            case Materials.MAT_NATURAL -> new Def(Blocks.OAK_LEAVES.defaultBlockState(), 0.6f * size, false);
            case Materials.MAT_PLASTIC -> new Def(Blocks.OAK_PLANKS.defaultBlockState(), 1f * size, false);
            default -> new Def(Blocks.OAK_PLANKS.defaultBlockState(), 2f * size, false);          // wood and anything unknown
        };
    }

    /**
     * Grass, bushes and loose ground cover sit in a voxel a block may be placed into (as on vanilla's short grass); a
     * boulder is solid, so a block goes against it.
     */
    public static boolean replaceable(int kind) {
        return kind == WorldTargets.KIND_GRASS || kind == WorldTargets.KIND_BUSH || kind == WorldTargets.KIND_COVER;
    }

    /** Low things Steve hits a zombie through, as vanilla lets you attack through grass. */
    public static boolean attackThrough(int kind) { return kind != WorldTargets.KIND_BOULDER && kind != WorldTargets.KIND_SOLID; }

    /** One kind of item that falls out, and how many. */
    public record Drop(Item item, int count) {
        public ItemStack stack() { return new ItemStack(item, count); }
    }

    /** What falls out when it is mined with the right tool (owner-approved defaults, 2026-10-16). */
    public static List<Drop> drops(int kind, int variant, RandomSource r, boolean shears) {
        List<Drop> out = new ArrayList<>();
        switch (kind) {
            case WorldTargets.KIND_GRASS -> {
                if (shears) add(out, Items.GRASS, 1);
                else if (r.nextInt(8) == 0) add(out, Items.WHEAT_SEEDS, 1); // vanilla short grass
            }
            case WorldTargets.KIND_BUSH -> {
                if (shears) add(out, Items.DEAD_BUSH, 1);
                else add(out, Items.STICK, 1 + r.nextInt(2));
            }
            case WorldTargets.KIND_COVER -> {
                switch (variant) {
                    case WorldTargets.COVER_STONES -> add(out, Items.COBBLESTONE, 2);
                    case WorldTargets.COVER_LARGE_STONE -> add(out, Items.COBBLESTONE, 3);
                    case WorldTargets.COVER_STONE_TWIGS -> { add(out, Items.COBBLESTONE, 1); add(out, Items.STICK, 1); }
                    case WorldTargets.COVER_LARGE_STONE_TWIGS -> { add(out, Items.COBBLESTONE, 3); add(out, Items.STICK, 1); }
                    case WorldTargets.COVER_TWIGS -> add(out, Items.STICK, 2);
                    case WorldTargets.COVER_BRANCH -> add(out, Items.STICK, 3);
                    case WorldTargets.COVER_LOG -> add(out, Items.OAK_LOG, 1);
                    default -> add(out, Items.COBBLESTONE, 1); // a stone, a flat stone, limestone
                }
            }
            case WorldTargets.KIND_BOULDER -> {
                int size = WorldTargets.boulderSize(variant) + 1;
                switch (WorldTargets.boulderKind(variant)) {
                    case WorldTargets.BOULDER_IRON -> { add(out, Items.RAW_IRON, size); add(out, Items.COBBLESTONE, 2); }
                    case WorldTargets.BOULDER_COPPER -> { add(out, Items.RAW_COPPER, 2 * size); add(out, Items.COBBLESTONE, 2); }
                    case WorldTargets.BOULDER_FLINT -> { add(out, Items.FLINT, 1 + r.nextInt(4)); add(out, Items.COBBLESTONE, 2); }
                    case WorldTargets.BOULDER_LIMESTONE -> add(out, Items.COBBLESTONE, 2 + r.nextInt(4));
                    default -> add(out, Items.COBBLESTONE, 4 + r.nextInt(5));
                }
            }
            case WorldTargets.KIND_WALL -> wallDrops(out, WorldTargets.edgeMaterial(variant));
            case WorldTargets.KIND_WINDOW -> {
                if (WorldTargets.edgeFlag(variant)) wallDrops(out, Materials.MAT_WOOD); // the frame; smashed glass drops nothing, as vanilla glass
            }
            case WorldTargets.KIND_DOOR -> {
                switch (WorldTargets.edgeMaterial(variant)) {
                    case Materials.MAT_METAL, Materials.MAT_METAL_HEAVY -> add(out, Items.IRON_DOOR, 1);
                    case Materials.MAT_GLASS -> { }
                    default -> add(out, Items.OAK_DOOR, 1);
                }
            }
            case WorldTargets.KIND_FENCE -> {
                switch (WorldTargets.edgeMaterial(variant)) {
                    case Materials.MAT_METAL, Materials.MAT_METAL_HEAVY -> add(out, Items.IRON_BARS, 2);
                    case Materials.MAT_STONE -> add(out, Items.COBBLESTONE_WALL, 1);
                    case Materials.MAT_BRICK -> add(out, Items.BRICK_WALL, 1);
                    case Materials.MAT_NATURAL -> add(out, Items.STICK, 2);
                    default -> add(out, Items.OAK_FENCE, 1);
                }
            }
            case WorldTargets.KIND_FURNITURE -> { } // PZ's own item reaches Steve's inventory through the mirror
            case WorldTargets.KIND_SOLID -> wallDrops(out, WorldTargets.edgeMaterial(variant)); // what PZ cannot pick up breaks into its material
            case WorldTargets.KIND_BARRICADE -> {
                if (WorldTargets.edgeMaterial(variant) == Materials.MAT_METAL) add(out, Items.IRON_BARS, 2);
                int planks = WorldTargets.barricadePlanks(variant);
                if (planks > 0) add(out, Items.OAK_PLANKS, planks);
            }
            default -> { }
        }
        return out;
    }

    /** A storey-high wall's worth of the material's Minecraft block (owner-approved defaults, 2026-10-16). */
    private static void wallDrops(List<Drop> out, int material) {
        switch (material) {
            case Materials.MAT_BRICK -> add(out, Items.BRICKS, 2);
            case Materials.MAT_STONE -> add(out, Items.COBBLESTONE, 2);
            case Materials.MAT_CERAMIC -> add(out, Items.TERRACOTTA, 2);
            case Materials.MAT_METAL -> add(out, Items.IRON_BARS, 4);
            case Materials.MAT_METAL_HEAVY -> add(out, Items.IRON_INGOT, 2);
            case Materials.MAT_PLASTER -> add(out, Items.WHITE_CONCRETE, 2);
            case Materials.MAT_FABRIC -> add(out, Items.WHITE_WOOL, 2);
            case Materials.MAT_NATURAL -> add(out, Items.STICK, 2);
            case Materials.MAT_GLASS, Materials.MAT_PLASTIC -> { }
            default -> add(out, Items.OAK_PLANKS, 4);
        }
    }

    private static void add(List<Drop> out, Item item, int count) { out.add(new Drop(item, count)); }
}
