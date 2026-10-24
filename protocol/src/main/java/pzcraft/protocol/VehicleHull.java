package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** A full snapshot of moving PZ vehicle footprints, in Minecraft coordinates. */
public record VehicleHull(double bottom, double top, double x1, double z1, double x2, double z2,
                          double x3, double z3, double x4, double z4) {
    public static final int MAX_VEHICLES = 64;
    private static final double STRIP = 0.125;

    public VehicleHull {
        for (double v : new double[] {bottom, top, x1, z1, x2, z2, x3, z3, x4, z4})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("non-finite vehicle hull");
        if (top <= bottom || top - bottom > 20 || Math.max(Math.max(z1, z2), Math.max(z3, z4))
                - Math.min(Math.min(z1, z2), Math.min(z3, z4)) > 40
                || Math.max(Math.max(x1, x2), Math.max(x3, x4)) - Math.min(Math.min(x1, x2), Math.min(x3, x4)) > 40)
            throw new IllegalArgumentException("invalid vehicle dimensions");
    }

    public static byte[] encode(List<VehicleHull> hulls) {
        if (hulls.size() > MAX_VEHICLES) throw new IllegalArgumentException("too many vehicles");
        var b = ByteBuffer.allocate(4 + hulls.size() * 80).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(hulls.size());
        for (var h : hulls) for (double v : new double[] {h.bottom, h.top, h.x1, h.z1, h.x2, h.z2, h.x3, h.z3, h.x4, h.z4}) b.putDouble(v);
        return b.array();
    }

    public static List<VehicleHull> decode(byte[] bytes) {
        if (bytes.length < 4) throw new IllegalArgumentException("truncated vehicles");
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        if (n < 0 || n > MAX_VEHICLES || b.remaining() != n * 80) throw new IllegalArgumentException("invalid vehicle snapshot");
        var out = new ArrayList<VehicleHull>(n);
        for (int i = 0; i < n; i++) out.add(new VehicleHull(b.getDouble(), b.getDouble(), b.getDouble(), b.getDouble(),
                b.getDouble(), b.getDouble(), b.getDouble(), b.getDouble(), b.getDouble(), b.getDouble()));
        return List.copyOf(out);
    }

    /** Thin conservative strips follow the rotated polygon; diagonal cars do not acquire a giant square wall. */
    public double[] boxes() {
        double[] xs = {x1, x2, x3, x4}, zs = {z1, z2, z3, z4};
        double lo = Math.min(Math.min(z1, z2), Math.min(z3, z4));
        double hi = Math.max(Math.max(z1, z2), Math.max(z3, z4));
        var values = new ArrayList<Double>();
        for (double z = lo; z < hi; z += STRIP) {
            double end = Math.min(hi, z + STRIP), minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                if (zs[i] >= z && zs[i] <= end) { minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); }
                if (zs[j] != zs[i]) for (double cut : new double[] {z, end}) {
                    double t = (cut - zs[i]) / (zs[j] - zs[i]);
                    if (t >= 0 && t <= 1) {
                        double x = xs[i] + t * (xs[j] - xs[i]);
                        minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    }
                }
            }
            if (maxX > minX) for (double v : new double[] {minX, bottom, z, maxX, top, end}) values.add(v);
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }
}
