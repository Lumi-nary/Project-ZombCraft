package pzcraft.mc;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Materials;

/**
 * Footsteps on PZ's floors (StepSoundMixin): PZ's floors are collision, not blocks, so Minecraft took the step sound from the
 * block under them (the ground made to match outdoors, nothing at all upstairs): grass under an asphalt road, dirt under a
 * wooden floor, silence on a second floor. Standing on a PZ floor, a step sounds like a block of its material; on a Minecraft
 * block (one placed on the floor, or where the floor was dug away) it is that block's own sound.
 */
public final class PzFloorSounds {
    /** How far the feet may be from a storey's floor and still stand on it. */
    private static final double ON_FLOOR = 0.07;

    private PzFloorSounds() {}

    /** The block whose step sound belongs to where this entity stands on a PZ floor, or null when it does not. */
    public static BlockState standIn(Entity e) {
        double y = e.getY();
        int level = (int) Math.round(Coords.pzZ(y) - Coords.Z_EPS);
        if (Math.abs(y - level * Coords.BLOCKS_PER_LEVEL) > ON_FLOOR) return null;
        int floor = MaterialField.floor(level, Mth.floor(e.getX()), Mth.floor(e.getZ()));
        var block = switch (floor) {
            case Materials.FLOOR_GRASS -> Blocks.GRASS_BLOCK;
            case Materials.FLOOR_DIRT -> Blocks.DIRT;
            case Materials.FLOOR_SAND -> Blocks.SAND;
            case Materials.FLOOR_GRAVEL -> Blocks.GRAVEL;
            case Materials.FLOOR_CONCRETE, Materials.FLOOR_ASPHALT -> Blocks.STONE;
            case Materials.FLOOR_WOOD -> Blocks.OAK_PLANKS;
            //#if MC_26_1
            //$ case Materials.FLOOR_CARPET -> Blocks.WHITE_WOOL;   // white
            //#else
            case Materials.FLOOR_CARPET -> Blocks.WOOL.asList().getFirst();   // white
            //#endif
            case Materials.FLOOR_CERAMIC -> Blocks.CALCITE;
            case Materials.FLOOR_METAL -> Blocks.IRON_BLOCK;
            case Materials.FLOOR_SNOW -> Blocks.SNOW_BLOCK;
            default -> null;
        };
        return block == null ? null : block.defaultBlockState();
    }
}
