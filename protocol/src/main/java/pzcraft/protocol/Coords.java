package pzcraft.protocol;

/**
 * Coordinate mapping between Project Zomboid and Minecraft.
 *
 * <pre>
 * PZ: x east, y south, z = level (0 = ground, +1 per storey, fractional while climbing)
 * MC: x east, y up,    z south
 * </pre>
 *
 * Horizontally one PZ tile is one MC block. Vertically one PZ storey is {@link #BLOCKS_PER_LEVEL} blocks, which is the
 * scale Viewpoint renders at (scene y = z * sqrt(6)), so the camera and Minecraft's physics agree about how tall things
 * are. PZ floors are exported to Minecraft as collision boxes, not blocks, so they need not sit on the block grid.
 */
public final class Coords {
    public static final double BLOCKS_PER_LEVEL = Math.sqrt(6.0); // 2.4494897

    private Coords() {}

    /**
     * Feet standing exactly on a level's floor sit this far above it, in levels. PZ finds a character's square with
     * floor(z), so a float that lands a hair under an integer level would put them on the storey below.
     */
    public static final double Z_EPS = 0.0005;

    public static double mcX(double pzX) { return pzX; }
    public static double mcY(double pzZ) { return (pzZ - Z_EPS) * BLOCKS_PER_LEVEL; }
    public static double mcZ(double pzY) { return pzY; }

    public static double pzX(double mcX) { return mcX; }
    public static double pzY(double mcZ) { return mcZ; }
    public static double pzZ(double mcY) { return mcY / BLOCKS_PER_LEVEL + Z_EPS; }

    /**
     * Yaw. MC: degrees, 0 = south (+z), 90 = west, forward = (-sin, cos) in (x, z).
     * Viewpoint's Look.yaw: radians, forward = (cos, sin) in PZ's (x east, y south), i.e. 0 = east.
     * Equating the two forward vectors gives pzYaw = mcYaw + 90 degrees.
     */
    public static final double YAW_OFFSET_RAD = Math.PI / 2;

    public static float mcYawDegToPzRad(float mcYawDeg) {
        return wrapPi((float) (Math.toRadians(mcYawDeg) + YAW_OFFSET_RAD));
    }

    public static float pzRadToMcYawDeg(float pzYawRad) {
        return (float) Math.toDegrees(wrapPi((float) (pzYawRad - YAW_OFFSET_RAD)));
    }

    /** MC pitch: degrees, positive looks down. Viewpoint's Look.pitch: radians, positive looks up. */
    public static float mcPitchDegToPzRad(float mcPitchDeg) {
        return (float) -Math.toRadians(mcPitchDeg);
    }

    public static float pzPitchRadToMcDeg(float pzPitchRad) {
        return (float) -Math.toDegrees(pzPitchRad);
    }

    private static float wrapPi(float a) {
        double tau = Math.PI * 2;
        double r = a % tau;
        if (r > Math.PI) r -= tau;
        if (r < -Math.PI) r += tau;
        return (float) r;
    }
}
