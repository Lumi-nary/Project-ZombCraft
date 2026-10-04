package pzcraft.protocol;

/**
 * PZ measures a melee weapon's {@code maxRange} between the centres of two characters in tiles (a katana reaches 1.4),
 * which in Minecraft feels like holding a stick: a vanilla sword reaches three blocks from the eye. Both games use this one
 * factor for every melee reach (Better Combat's attack range, the Minecraft-side guard and PZ's own range check), so a
 * katana reaches about three blocks and the other weapons keep PZ's ordering (a hammer shorter than a katana).
 */
public final class MeleeReach {
    public static final double SCALE = 2.1;

    private MeleeReach() {}

    /** The reach in Minecraft blocks of a weapon with this PZ {@code maxRange}. */
    public static double blocks(double pzMaxRange) { return pzMaxRange * SCALE; }
}
