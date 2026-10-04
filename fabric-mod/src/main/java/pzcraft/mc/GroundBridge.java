package pzcraft.mc;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Materials;
import pzcraft.protocol.Wire;

/**
 * The ground under PZ's floors. PZ's world has no underground, and the Minecraft world is void, so ground is made up as it
 * is needed: a column of ground exists "virtually" under every PZ tile that has a natural floor (no basement, no water), and
 * becomes real blocks only where something touches it - a carpet three blocks deep around Steve, the neighbours of
 * anything that is dug away, and the sphere of an explosion. The depth already made real is stored per column in the
 * chunk's own save data, so a hole stays a hole and untouched ground stays virtual.
 *
 * <p>Where a top block (just under PZ's floor) is dug away, PZ removes that tile's floor and the hole shows the layers
 * below; put a block back and the floor returns. The renderer treats virtual ground as solid and never draws a block face
 * that is hidden under an intact PZ floor.
 */
public final class GroundBridge {
    public static final int MAX_DEPTH = 48;
    private static final int CARPET_RADIUS = 6, CARPET_DEPTH = 3, CARPET_BUDGET = 900;
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

    /** Materialized depth per column of a chunk (index z * 16 + x), saved with the chunk. */
    private static final AttachmentType<byte[]> DEPTHS = AttachmentRegistry.createPersistent(
            Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "ground_depths"),
            Codec.STRING.xmap(s -> Base64.getDecoder().decode(s), b -> Base64.getEncoder().encodeToString(b)));

    /** The same arrays for the renderer thread, which has no access to the server's chunks. */
    private static final ConcurrentHashMap<Long, byte[]> MIRROR = new ConcurrentHashMap<>();
    /** Positions this bridge just filled quietly: the client's block-update mixin must not treat them as edits. */
    private static final ConcurrentHashMap<Long, Long> FRESH = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<long[]> EDITS = new ConcurrentLinkedQueue<>();
    private static volatile boolean quiet;
    private static long tick;
    private static volatile int carpetBlocks, edgeBlocks, explosionBlocks, dugReports, refillReports;

    private GroundBridge() {}

    /** Main entrypoint: the attachment type must exist before any chunk loads. */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(GroundBridge::tick);
    }

    // ---- what the ground is made of ----

    public static boolean isQuiet() { return quiet; }

    private static long chunkKey(int x, int z) { return ((long) (x >> 4) & 0xFFFFFFFFL) | (((long) (z >> 4) & 0xFFFFFFFFL) << 32); }

    /** Floor-based test: a natural floor, no basement, no water. */
    public static boolean naturalFloor(int x, int z) {
        int floor = MaterialField.floor(0, x, z);
        return floor != Materials.FLOOR_NONE && floor != Materials.FLOOR_WATER
                && (MaterialField.flags(0, x, z) & Materials.TILE_BASEMENT) == 0;
    }

    /** How many blocks deep this column has been made real. Any thread. */
    public static int materialized(int x, int z) {
        byte[] d = MIRROR.get(chunkKey(x, z));
        return d == null ? 0 : d[(z & 15) * 16 + (x & 15)] & 0xFF;
    }

    public static boolean isGroundColumn(int x, int z) { return materialized(x, z) > 0 || naturalFloor(x, z); }

    /** A block position that is solid ground nobody has made real yet: nothing is drawn against it. */
    public static boolean virtualSolid(int x, int y, int z) {
        if (y >= 0 || y < -MAX_DEPTH) return false;
        return -y > materialized(x, z) && isGroundColumn(x, z);
    }

    public static boolean floorIntact(int x, int z) { return MaterialField.floor(0, x, z) != Materials.FLOOR_NONE; }

    private static int hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return (int) (h & 0x7fffffff);
    }

    /** The block that belongs {@code depth} blocks under the floor of this column (depth 1 is just under it). */
    static BlockState virtualBlock(int x, int depth, int z, int floorClass) {
        if (depth >= MAX_DEPTH) return Blocks.BEDROCK.defaultBlockState();
        if (depth <= 3) {
            Block b = switch (floorClass) {
                case Materials.FLOOR_GRASS -> depth == 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT;
                case Materials.FLOOR_SAND -> depth <= 2 ? Blocks.SAND : Blocks.SANDSTONE;
                case Materials.FLOOR_GRAVEL -> depth == 1 ? Blocks.GRAVEL : Blocks.DIRT;
                case Materials.FLOOR_CONCRETE, Materials.FLOOR_ASPHALT -> depth == 1 ? Blocks.STONE : depth == 2 ? Blocks.GRAVEL : Blocks.DIRT;
                default -> Blocks.DIRT;
            };
            return b.defaultBlockState();
        }
        boolean deep = depth >= 24;
        int roll = hash(x, depth, z) % 1000;
        if (depth >= 3 && roll < 12) return (deep ? Blocks.DEEPSLATE_COAL_ORE : Blocks.COAL_ORE).defaultBlockState();
        if (depth >= 6 && roll >= 12 && roll < 20) return (deep ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE).defaultBlockState();
        if (depth >= 5 && roll >= 20 && roll < 28) return (deep ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE).defaultBlockState();
        if (depth >= 10 && roll >= 28 && roll < 31) return (deep ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE).defaultBlockState();
        if (depth >= 12 && roll >= 31 && roll < 38) return (deep ? Blocks.DEEPSLATE_REDSTONE_ORE : Blocks.REDSTONE_ORE).defaultBlockState();
        if (depth >= 14 && roll >= 38 && roll < 42) return (deep ? Blocks.DEEPSLATE_LAPIS_ORE : Blocks.LAPIS_ORE).defaultBlockState();
        if (depth >= 18 && roll >= 42 && roll < 44) return (deep ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DIAMOND_ORE).defaultBlockState();
        return (deep ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
    }

    // ---- making ground real ----

    /** Makes the column real down to {@code depth}. Returns how many blocks were placed. Server thread. */
    static int ensure(ServerLevel level, int x, int z, int depth) {
        depth = Math.min(depth, MAX_DEPTH);
        if (depth <= 0) return 0;
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return 0;
        byte[] depths = chunk.getAttachedOrCreate(DEPTHS, () -> new byte[256]);
        MIRROR.put(chunkKey(x, z), depths);
        int index = (z & 15) * 16 + (x & 15);
        int have = depths[index] & 0xFF;
        if (have >= depth) return 0;
        if (have == 0 && !naturalFloor(x, z)) return 0;
        int floorClass = MaterialField.floor(0, x, z);
        int placed = 0;
        quiet = true;
        try {
            long expiry = System.nanoTime() + 3_000_000_000L;
            for (int d = have + 1; d <= depth; d++) {
                BlockPos pos = new BlockPos(x, -d, z);
                if (!level.getBlockState(pos).isAir()) continue; // somebody built here
                level.setBlock(pos, virtualBlock(x, d, z, floorClass), QUIET);
                FRESH.put(pos.asLong(), expiry);
                placed++;
            }
        } finally {
            quiet = false;
        }
        depths[index] = (byte) depth;
        chunk.markUnsaved();
        return placed;
    }

    /** Every column within an explosion's reach is made real first, so vanilla's ray tracing finds ground to destroy. */
    public static void beforeExplosion(ServerLevel level, Vec3 c, float radius) {
        double reach = radius * 2.2 + 1;
        if (!Session.pzConnected || c.y - reach >= 0) return;
        int placed = 0;
        for (int x = (int) Math.floor(c.x - reach); x <= (int) Math.floor(c.x + reach); x++) {
            for (int z = (int) Math.floor(c.z - reach); z <= (int) Math.floor(c.z + reach); z++) {
                double h2 = (x + .5 - c.x) * (x + .5 - c.x) + (z + .5 - c.z) * (z + .5 - c.z);
                if (h2 > reach * reach) continue;
                int depth = (int) Math.ceil(-(c.y - Math.sqrt(reach * reach - h2)));
                if (depth > 0) placed += ensure(level, x, z, depth);
            }
        }
        explosionBlocks += placed;
    }

    /** The renderer's view of the saved depths for every chunk it draws (it may be far from the carpet). */
    private static void refreshMirror(ServerLevel level, ServerPlayer sp) {
        int cx = sp.blockPosition().getX() >> 4, cz = sp.blockPosition().getZ() >> 4;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                long key = ((long) (cx + dx) & 0xFFFFFFFFL) | (((long) (cz + dz) & 0xFFFFFFFFL) << 32);
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                byte[] depths = chunk == null ? null : chunk.getAttached(DEPTHS);
                if (depths == null) MIRROR.remove(key); else MIRROR.put(key, depths);
            }
        }
    }

    /** A ring of ground around Steve, three blocks deep, so there is always something under his crosshair to dig. */
    private static void carpet(ServerLevel level, ServerPlayer sp) {
        int cx = (int) Math.floor(sp.getX()), cz = (int) Math.floor(sp.getZ());
        int budget = CARPET_BUDGET;
        for (int dx = -CARPET_RADIUS; dx <= CARPET_RADIUS && budget > 0; dx++) {
            for (int dz = -CARPET_RADIUS; dz <= CARPET_RADIUS && budget > 0; dz++) {
                if (dx * dx + dz * dz > CARPET_RADIUS * CARPET_RADIUS) continue;
                int x = cx + dx, z = cz + dz;
                if (!TerrainCoverage.known(x + .5, z + .5)) continue;
                int made = ensure(level, x, z, CARPET_DEPTH);
                carpetBlocks += made;
                budget -= made + 1;
            }
        }
    }

    // ---- reporting digs ----

    /** Server thread, from the chunk's block-change hook. Never called for this bridge's own placements. */
    public static void onChange(BlockPos pos, BlockState old, BlockState now) {
        if (quiet || pos.getY() >= 0 || pos.getY() < -MAX_DEPTH) return;
        boolean removed = old.isSolid() && !now.isSolid(), placed = !old.isSolid() && now.isSolid();
        if (removed || placed) EDITS.add(new long[] {pos.asLong(), removed ? 1 : 0});
    }

    /** Client thread: this block update is just the ground being made real, not something to redraw. */
    public static boolean consumeFresh(BlockPos pos) {
        if (FRESH.isEmpty()) return false;
        Long expiry = FRESH.remove(pos.asLong());
        return expiry != null && expiry >= System.nanoTime(); // an entry nobody consumed in time is a real edit by now
    }

    private static void tick(MinecraftServer server) {
        tick++;
        var players = server.getPlayerList().getPlayers();
        if (!Session.pzConnected || !Session.released || players.isEmpty()) { EDITS.clear(); return; }
        ServerLevel level = server.overworld();
        long[] edit;
        int handled = 0;
        while (handled++ < 4096 && (edit = EDITS.poll()) != null) {
            BlockPos pos = BlockPos.of(edit[0]);
            int x = pos.getX(), z = pos.getZ(), depth = -pos.getY();
            if (!isGroundColumn(x, z)) continue;
            if (edit[1] == 1) {
                // Dug: what is now exposed must exist, and a dug top block takes PZ's floor with it.
                int made = ensure(level, x, z, depth + 1);
                made += ensure(level, x + 1, z, depth) + ensure(level, x - 1, z, depth);
                made += ensure(level, x, z + 1, depth) + ensure(level, x, z - 1, depth);
                edgeBlocks += made;
                if (depth == 1 && MaterialField.floor(0, x, z) != Materials.FLOOR_NONE) {
                    Session.send(Wire.MSG_GROUND_EDIT, Wire.encodeGroundEdit(x, z, true));
                    dugReports++;
                }
            } else if (depth == 1 && MaterialField.floor(0, x, z) == Materials.FLOOR_NONE) {
                Session.send(Wire.MSG_GROUND_EDIT, Wire.encodeGroundEdit(x, z, false));
                refillReports++;
            }
        }
        if (tick % 5 == 0) carpet(level, players.getFirst());
        if (tick % 20 == 0) refreshMirror(level, players.getFirst());
        if (tick % 100 == 0) {
            long now = System.nanoTime();
            FRESH.values().removeIf(expiry -> expiry < now);
        }
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("carpetBlocks", carpetBlocks);
        m.put("edgeBlocks", edgeBlocks);
        m.put("explosionBlocks", explosionBlocks);
        m.put("dugReports", dugReports);
        m.put("refillReports", refillReports);
        m.put("chunksTracked", MIRROR.size());
        m.put("pendingEdits", EDITS.size());
        return m;
    }

    /** For tests: how deep the column under tile (x, z) is real, and what its blocks are. */
    public static List<String> probe(ServerLevel level, int x, int z, int depth) {
        List<String> out = new ArrayList<>();
        for (int d = 1; d <= depth; d++) {
            out.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, -d, z)).getBlock()).getPath());
        }
        return out;
    }
}

