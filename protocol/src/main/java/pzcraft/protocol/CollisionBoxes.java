package pzcraft.protocol;

import java.util.Arrays;

/**
 * Turns a {@link CollisionSection} into axis-aligned boxes in Minecraft coordinates:
 * {@code [minX, minY, minZ, maxX, maxY, maxZ]} repeated. These are collision-only shapes, not blocks, so
 * placed blocks never fight with PZ geometry.
 */
public final class CollisionBoxes {
    /**
     * Floors are slabs this thick under the walking surface. Minecraft's movement clips against every box in its swept
     * path, so a thin slab cannot be tunnelled through; keeping it thin leaves headroom under the storey above (PZ's
     * storeys are only 2.45 blocks tall and Steve is 1.8).
     */
    public static final double FLOOR_THICKNESS = 0.1;
    /** Half-thickness of a wall edge. */
    public static final double EDGE_HALF = 0.1;
    /** A low fence (PZ lets you vault it): knee height for Steve, low enough to jump (Minecraft jumps 1.25 blocks). */
    public static final double LOW_EDGE = 1.0;

    private CollisionBoxes() {}

    public static double[] build(CollisionSection s) {
        double h = Coords.BLOCKS_PER_LEVEL;
        double base = s.level * h;
        double[] out = new double[CollisionSection.TILES * 6 * 4];
        int n = 0;
        for (int i = 0; i < CollisionSection.TILES; i++) {
            int f = s.flags[i] & 0xFF;
            if (f == 0) continue;
            double tx = s.cx * CollisionSection.SIZE + (i % CollisionSection.SIZE);
            double ty = s.cy * CollisionSection.SIZE + (i / CollisionSection.SIZE);
            if (n + 6 * (CollisionSection.RAMP_N * CollisionSection.RAMP_N + 3) > out.length) out = Arrays.copyOf(out, out.length * 2);

            if ((f & CollisionSection.SOLID) != 0) {
                int steps = s.heights[i] & 0xFF;
                double top = steps == 0 ? h : h * steps / CollisionSection.HEIGHT_STEPS;
                n = box(out, n, tx, base, ty, tx + 1, base + top, ty + 1);
            }
            short[] ramp = (f & CollisionSection.RAMP) != 0 ? s.rampHeights[i] : null;
            if (ramp != null) {
                int rn = CollisionSection.RAMP_N;
                double step = 1.0 / rn;
                for (int sy = 0; sy < rn; sy++) {
                    for (int sx = 0; sx < rn; sx++) {
                        double top = base + (ramp[sy * rn + sx] / 256.0) * h;
                        n = box(out, n, tx + sx * step, Math.min(base, top) - FLOOR_THICKNESS, ty + sy * step,
                                tx + (sx + 1) * step, top, ty + (sy + 1) * step);
                    }
                }
            } else if ((f & CollisionSection.FLOOR) != 0) {
                n = box(out, n, tx, base - FLOOR_THICKNESS, ty, tx + 1, base, ty + 1);
            }
            if ((f & CollisionSection.EDGE_N) != 0) {
                double top = (f & CollisionSection.EDGE_N_LOW) != 0 ? LOW_EDGE : h;
                n = box(out, n, tx, base, ty - EDGE_HALF, tx + 1, base + top, ty + EDGE_HALF);
            }
            if ((f & CollisionSection.EDGE_W) != 0) {
                double top = (f & CollisionSection.EDGE_W_LOW) != 0 ? LOW_EDGE : h;
                n = box(out, n, tx - EDGE_HALF, base, ty, tx + EDGE_HALF, base + top, ty + 1);
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static int box(double[] out, int n, double x0, double y0, double z0, double x1, double y1, double z1) {
        out[n] = x0; out[n + 1] = y0; out[n + 2] = z0; out[n + 3] = x1; out[n + 4] = y1; out[n + 5] = z1;
        return n + 6;
    }
}
