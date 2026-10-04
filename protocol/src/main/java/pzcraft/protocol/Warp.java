package pzcraft.protocol;

/**
 * Rotation-only re-projection ("time-warp") of a picture rendered through camera A so it can be drawn for camera B,
 * which has since turned a little. A pure rotation maps screen positions through a homography, so the fixed-function
 * pipeline can do it: draw one screen-filling quad whose four corners carry projective texture coordinates
 * {@code (s, t, 0, q)} from {@link #corners}; the hardware divides by q per pixel and the interpolation is exact.
 *
 * <p>Angles are Minecraft's: yaw 0 = south, 90 = west (increasing turns right); pitch positive looks down.
 * FOV is vertical in degrees; aspect is width / height.
 */
public final class Warp {
    private Warp() {}

    /** Largest turn between the two cameras we are willing to re-project; beyond it the picture is simply drawn as is. */
    public static final float MAX_DELTA_DEG = 25f;

    /**
     * Corner texture coordinates for a quad drawn over the whole screen of camera B, in the corner order
     * (-1,-1), (1,-1), (1,1), (-1,1) of normalized device coordinates, as {@code s,t,q} triples into {@code out[0..11]}.
     *
     * <p>The picture may have been rendered with a wider field of view than B shows (overscan, so a re-projection has
     * picture left beyond the screen edge); the mapping always accounts for that. When the cameras' angles differ by more
     * than {@link #MAX_DELTA_DEG} the rotation is skipped (the picture is shown for A's direction) and false is returned.
     */
    public static boolean corners(float oldYaw, float oldPitch, float oldFov, float oldAspect,
                                  float newYaw, float newPitch, float newFov, float newAspect, float[] out) {
        if (!(oldFov > 5f && oldFov < 170f && newFov > 5f && newFov < 170f && oldAspect > 0.1f && newAspect > 0.1f)) {
            identity(out);
            return false;
        }
        boolean rotate = Math.abs(wrap(newYaw - oldYaw)) <= MAX_DELTA_DEG && Math.abs(newPitch - oldPitch) <= MAX_DELTA_DEG;
        if (!rotate) {
            newYaw = oldYaw;
            newPitch = oldPitch;
        }
        float[] r = {-1f, 1f, 1f, -1f};
        float[] u = {-1f, -1f, 1f, 1f};
        double[] rn = right(newYaw), un = up(newYaw, newPitch), fn = fwd(newYaw, newPitch);
        double[] ro = right(oldYaw), uo = up(oldYaw, oldPitch), fo = fwd(oldYaw, oldPitch);
        double tn = Math.tan(Math.toRadians(newFov) / 2), to = Math.tan(Math.toRadians(oldFov) / 2);
        for (int i = 0; i < 4; i++) {
            double[] d = new double[3];
            for (int k = 0; k < 3; k++) d[k] = r[i] * tn * newAspect * rn[k] + u[i] * tn * un[k] + fn[k];
            double q = dot(d, fo);
            if (q < 0.05) {
                identity(out);
                return false;
            }
            out[i * 3] = (float) ((dot(d, ro) / (to * oldAspect) + q) / 2);
            out[i * 3 + 1] = (float) ((dot(d, uo) / to + q) / 2);
            out[i * 3 + 2] = (float) q;
        }
        return rotate;
    }

    /** The no-op mapping: corner coordinates that show the picture unchanged. */
    public static void identity(float[] out) {
        float[] r = {0f, 1f, 1f, 0f};
        float[] u = {0f, 0f, 1f, 1f};
        for (int i = 0; i < 4; i++) {
            out[i * 3] = r[i];
            out[i * 3 + 1] = u[i];
            out[i * 3 + 2] = 1f;
        }
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg > 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    private static double[] right(float yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new double[] {-Math.cos(y), 0, -Math.sin(y)};
    }

    private static double[] up(float yawDeg, float pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        return new double[] {-Math.sin(y) * Math.sin(p), Math.cos(p), Math.cos(y) * Math.sin(p)};
    }

    private static double[] fwd(float yawDeg, float pitchDeg) {
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        return new double[] {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }
}
