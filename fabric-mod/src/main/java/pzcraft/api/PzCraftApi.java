package pzcraft.api;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import pzcraft.mc.ExplosionBridge;
import pzcraft.mc.MaterialField;
import pzcraft.mc.Session;
import pzcraft.protocol.Coords;

/**
 * Public API for other Minecraft mods running alongside Project-ZombCraft. Everything here is safe to call whether or
 * not PZ is connected (calls then do nothing and return false), so a mod can call it unconditionally once it has
 * checked {@code FabricLoader.getInstance().isModLoaded("project-zombcraft")}.
 *
 * <p>How the two worlds fit together, for mod authors:
 * <ul>
 * <li>PZ's buildings, walls, doors, windows, furniture and floors are <b>not Minecraft blocks</b>. Minecraft sees air
 *     there (plus collision), so code that loops over blocks and breaks the non-air ones never reaches them. Call
 *     {@link #blast} or {@link #breakArea} for the same spot to damage PZ's objects too. Vanilla explosions
 *     ({@code Level.explode}) already do this on their own.</li>
 * <li>Each PZ zombie near Steve is an invisible, AI-less Minecraft zombie at its spot (the stand-in). Hurting it hurts
 *     PZ's zombie; explosion damage knocks it down; a fast push (velocity of a block a tick or more) knocks it down
 *     towards the push; to carry one, tag it with {@link #HELD_TAG}.</li>
 * <li>While {@link #isNativeWorld()} is true PZ draws Minecraft's blocks, block entities and entities itself, from
 *     their block models and their (Block)EntityRenderers. Geometry drawn any other way (custom GL, instancing) only
 *     reaches PZ as a flat picture over its scene. Full-screen post effects ({@code PostChain}) run, but cannot change
 *     PZ's own pixels: they apply to what Minecraft drew and add over the rest.</li>
 * </ul>
 */
public final class PzCraftApi {
    /** Bumped when methods are added; existing methods keep working. */
    public static final int VERSION = 2;

    /**
     * Entity tag ({@code entity.addTag(HELD_TAG)}) for a mod that picks up or carries a PZ zombie's stand-in: while it is
     * on, the stand-in stays where the mod puts it and PZ's zombie follows it (and cannot bite). Remove it to let go; a
     * stand-in flung fast when let go (or at any time) is knocked down in PZ like an explosion would. Viltrumite's
     * grab tag {@code ViltrumiteGrabbed} works the same way.
     */
    public static final String HELD_TAG = "pzcraft.held";

    private PzCraftApi() {}

    /** True while Project Zomboid is connected to this Minecraft. */
    public static boolean isPzConnected() { return Session.pzConnected; }

    /** True while PZ draws Minecraft's world natively (client side). */
    public static boolean isNativeWorld() { return Session.nativeWorld; }

    /**
     * Damages PZ's objects as a Minecraft explosion of this radius would (walls and doors stop part of the blast; what
     * the blast reaches is smashed, removed or toppled by PZ). Minecraft blocks are not touched: break them yourself, or
     * use {@code Level.explode}, which already does both. Server thread.
     *
     * @return true if the blast was sent to PZ
     */
    public static boolean blast(ServerLevel level, Vec3 center, float radius, boolean fire) {
        if (level == null || center == null || !Session.pzConnected || radius < 0.5f || MaterialField.sectionCount() == 0) return false;
        ExplosionBridge.before(level, center, Math.min(radius, 16f), fire);
        return true;
    }

    /**
     * Damages PZ's objects inside a box, for abilities that break every block in an area (punch craters, shockwaves,
     * lasers). The box becomes a blast at its centre sized to reach its sides. Server thread.
     *
     * @return true if the blast was sent to PZ
     */
    public static boolean breakArea(ServerLevel level, AABB box) {
        if (box == null) return false;
        double half = Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) / 2;
        return blast(level, box.getCenter(), (float) (half * 0.75 + 0.5), false);
    }

    /**
     * True if a PZ object (wall, door, window, furniture, solid thing) stands in the Minecraft block at x, y, z, where
     * Minecraft itself sees air. Uses the world data PZ has streamed to Minecraft; false for parts not streamed yet.
     */
    public static boolean hasPzObject(int x, int y, int z) {
        if (!Session.pzConnected) return false;
        int level = (int) Math.floor((y + 0.5) / Coords.BLOCKS_PER_LEVEL);
        return MaterialField.solid(level, x, z) != 0 || MaterialField.props(level, x, z) != 0
                || MaterialField.edgeWest(level, x, z) != 0 || MaterialField.edgeNorth(level, x, z) != 0
                || MaterialField.edgeWest(level, x + 1, z) != 0 || MaterialField.edgeNorth(level, x, z + 1) != 0;
    }
}
