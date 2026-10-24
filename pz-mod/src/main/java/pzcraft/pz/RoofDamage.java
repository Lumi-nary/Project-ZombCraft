package pzcraft.pz;

import java.lang.reflect.Field;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/** Keep Viewpoint's inferred roof tiles from covering holes explicitly made by a blast. */
public final class RoofDamage {
    private static final String KEY = "PzCraftRoofDestroyed";
    private static Field chunk, level, own, lent, gables;
    private static boolean failed;
    static volatile long masked;

    private RoofDamage() {}

    static void mark(IsoGridSquare square) { square.getModData().rawset(KEY, Boolean.TRUE); }

    private static boolean hole(IsoGridSquare square) {
        if (square == null || !Boolean.TRUE.equals(square.getModData().rawget(KEY))) return false;
        for (int i = 0; i < square.getObjects().size(); i++) {
            // A roof rebuilt at the same square is allowed to render again.
            if (PzMaterials.roof(square.getObjects().get(i))) return false;
        }
        return true;
    }

    /** Called after RoofGather.begin on Viewpoint's meshing thread; only its draw masks are changed. */
    public static void gathered() {
        if (failed) return;
        try {
            if (chunk == null) {
                Class<?> type = Class.forName("viewpoint.world.RoofGather");
                chunk = field(type, "chunk"); level = field(type, "level");
                own = field(type, "ownDraws"); lent = field(type, "lentDraws"); gables = field(type, "gables");
            }
            IsoChunk c = (IsoChunk) chunk.get(null);
            if (c == null || IsoWorld.instance.getCell() == null) return;
            int z = level.getInt(null);
            int[] a = (int[]) own.get(null), b = (int[]) lent.get(null), g = (int[]) gables.get(null);
            for (int i = 0; i < 64; i++) {
                IsoGridSquare sq = c.getGridSquare(i % 8, i / 8, z);
                IsoGridSquare above = c.getGridSquare(i % 8, i / 8, z + 1);
                boolean ownRoof = false;
                if (sq != null) for (int j = 0; j < sq.getObjects().size(); j++) {
                    ownRoof |= PzMaterials.roof(sq.getObjects().get(j));
                }
                // Viewpoint can lower an upper storey's roof into the empty storey below it.
                if (hole(sq) || (!ownRoof && hole(above))) {
                    if ((a[i] | b[i] | g[i]) != 0) masked++;
                    a[i] = b[i] = g[i] = 0;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            Log.error("Viewpoint roof damage masks unavailable", e);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
