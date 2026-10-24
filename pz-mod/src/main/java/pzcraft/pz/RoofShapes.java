package pzcraft.pz;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import pzcraft.protocol.Coords;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;

/**
 * The shape of PZ's roofs as Viewpoint draws them (its MapRoofs.piece and RoofPiece.height), so Minecraft's collision follows
 * the roof on the screen. PZ itself gives a roof square a flat floor at its storey, or none at all, so Steve walked inside
 * sloped roofs and blocks could be placed through them (B28). Reflection: Viewpoint is a separate mod; without it, or for a
 * roof sprite it has no shape for, the square keeps PZ's own floor.
 */
final class RoofShapes {
    private static final Object NONE = new Object();
    private static final Map<String, Object> pieces = new HashMap<>();
    private static Method piece, height;
    private static boolean failed;

    private RoofShapes() {}

    /**
     * Roof heights on this square in storeys above its floor, at n x n points (row-major, y then x; the highest roof piece
     * where several lie on the square), or null when it has no roof Viewpoint gives a shape for. Game thread.
     */
    static float[] heights(IsoGridSquare sq, int n) {
        if (failed) return null;
        float[] out = null;
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            String name = o == null || o.getSprite() == null ? null : o.getSprite().getName();
            if (name == null || !name.startsWith("roofs_")) continue;
            Object p = piece(name);
            if (p == null) continue;
            for (int sy = 0; sy < n; sy++) {
                for (int sx = 0; sx < n; sx++) {
                    float h;
                    try {
                        h = (Float) height.invoke(p, (sx + .5f) / n - .5f, (sy + .5f) / n - .5f);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        continue; // a piece without a slope (a flat or eave piece with no facing) keeps the floor
                    }
                    if (!Float.isFinite(h)) continue;
                    if (out == null) out = new float[n * n];
                    out[sy * n + sx] = Math.max(out[sy * n + sx], (float) (h / Coords.BLOCKS_PER_LEVEL));
                }
            }
        }
        return out;
    }

    private static Object piece(String sprite) {
        Object cached = pieces.get(sprite);
        if (cached != null) return cached == NONE ? null : cached;
        Object p = null;
        try {
            if (piece == null) {
                piece = Class.forName("viewpoint.world.MapRoofs").getMethod("piece", String.class);
                height = Class.forName("viewpoint.world.RoofPiece").getMethod("height", float.class, float.class);
            }
            p = piece.invoke(null, sprite);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            failed = true;
            Log.info("roof shapes: Viewpoint's roof pieces are not available (" + e + "); roofs keep PZ's flat floors");
        } catch (ReflectiveOperationException | RuntimeException e) {
            p = null;
        }
        if (pieces.size() < 4096) pieces.put(sprite, p == null ? NONE : p);
        return p;
    }
}
