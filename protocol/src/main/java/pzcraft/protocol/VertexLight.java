package pzcraft.protocol;

/**
 * Minecraft's own light at a native vertex (blocks in {@link SceneLink}, entities, hands and particles in
 * {@link EntityLink}), carried in the vertex's emission value so the vertex layout stays as it is. A plain emission is
 * 0..1 as before; a value from {@link #pack} is {@link #MARK} plus sky light * 256 plus block light * 16 plus the
 * emission in fifteenths (whole numbers, exact in a float). PZ's shaders light Minecraft geometry with it where PZ has no
 * light of its own (under PZ's ground) and combine the two elsewhere (a Minecraft roof shades, a torch lights).
 */
public final class VertexLight {
    public static final float MARK = 4096f;

    private VertexLight() {}

    /** Emission (0..1) with Minecraft's block and sky light (0..15 each). */
    public static float pack(float emission, int block, int sky) {
        int e = Math.round(Math.max(0f, Math.min(1f, emission)) * 15f);
        return MARK + clamp(sky) * 256 + clamp(block) * 16 + e;
    }

    /** Emission with Minecraft's packed light coordinates (block light << 4, sky light << 20); unknown (-1): plain emission. */
    public static float packCoords(float emission, int lightCoords) {
        if (lightCoords < 0) return emission;
        return pack(emission, (lightCoords >> 4) & 15, (lightCoords >> 20) & 15);
    }

    public static boolean has(float value) { return value >= MARK - 0.5f; }

    public static float emission(float value) { return has(value) ? (code(value) & 15) / 15f : value; }

    public static int block(float value) { return has(value) ? (code(value) >> 4) & 15 : 0; }

    public static int sky(float value) { return has(value) ? (code(value) >> 8) & 15 : 0; }

    private static int code(float value) { return Math.round(value - MARK); }

    private static int clamp(int level) { return Math.max(0, Math.min(15, level)); }
}
