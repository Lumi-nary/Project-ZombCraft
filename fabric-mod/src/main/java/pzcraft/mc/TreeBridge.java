package pzcraft.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import pzcraft.protocol.Materials;
import pzcraft.protocol.Wire;

/**
 * PZ draws and owns its trees; Steve punches them through invisible {@link PzTreeBlock} stacks that follow the trees PZ
 * reports near him. Placement and removal here are quiet (no neighbour updates), so only gameplay removals - mining, an
 * explosion, a command - count as felling: PZ is told to topple the real tree and Minecraft drops the logs.
 */
public final class TreeBridge {
    /** Blocks around Steve that get tree blocks. Within a few chunks, which the integrated server keeps loaded. */
    private static final int RADIUS = 36;
    private static final int INTERVAL = 10, SWEEP_INTERVAL = 40, MAX_CHANGES = 48;
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
    private static final int QUIET_REMOVE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
    /** How long a felled tile is left alone while PZ topples the tree and reports it gone. */
    private static final long FELLED_HOLD = 400;

    /** Tile key -> {size, kind, height} of the block stacks this bridge placed. Server thread only. */
    private static final Map<Long, int[]> placed = new HashMap<>();
    private static final Map<Long, Long> felledUntil = new HashMap<>();
    private static long tick;
    private static volatile int fellCount, placedCount, staleRemoved, orphansRemoved, wantedCount, blockedCount;
    private static volatile List<Map<String, Object>> nearest = List.of();

    private TreeBridge() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(TreeBridge::tick);
    }

    public static int heightFor(int size) { return size <= 2 ? 2 : size == 3 ? 3 : 4; }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    private static BlockState stateFor(MaterialField.Tree t) {
        return PzBlocks.TREE.defaultBlockState().setValue(PzTreeBlock.SIZE, t.size()).setValue(PzTreeBlock.KIND, t.kind());
    }

    private static void tick(MinecraftServer server) {
        tick++;
        if (PzBlocks.TREE == null || tick % INTERVAL != 0) return;
        var players = server.getPlayerList().getPlayers();
        if (!Session.pzConnected || !Session.released || players.isEmpty()) return;
        ServerLevel level = server.overworld();
        ServerPlayer sp = players.getFirst();
        try {
            reconcile(level, sp);
            if (tick % SWEEP_INTERVAL == 0) sweepOrphans(level, sp);
        } catch (RuntimeException e) {
            PzCraftClient.LOG.error("tree reconcile failed", e);
        }
    }

    private static boolean intact(ServerLevel level, MaterialField.Tree t, int height) {
        BlockState want = stateFor(t);
        for (int y = 0; y < height; y++) if (level.getBlockState(new BlockPos(t.x(), y, t.y())) != want) return false;
        return true;
    }

    private static void reconcile(ServerLevel level, ServerPlayer sp) {
        List<MaterialField.Tree> trees = MaterialField.trees(sp.getX(), sp.getZ(), RADIUS);
        Set<Long> wanted = new HashSet<>();
        int changes = 0, blocked = 0;
        for (MaterialField.Tree t : trees) {
            long k = t.key();
            wanted.add(k);
            if (felledUntil.getOrDefault(k, 0L) > tick) continue;           // PZ is still toppling it
            if (!TerrainCoverage.known(t.x() + 0.5, t.y() + 0.5)) continue; // its column has not been fully acknowledged
            int height = heightFor(t.size());
            int[] have = placed.get(k);
            if (have != null && have[0] == t.size() && have[1] == t.kind() && have[2] == height && intact(level, t, height)) continue;
            if (changes >= MAX_CHANGES) break;
            if (place(level, sp, t, height)) { changes++; placedCount++; } else blocked++;
        }
        blockedCount = blocked;
        wantedCount = trees.size();
        felledUntil.values().removeIf(until -> until <= tick);

        // Stale: placed here, but PZ no longer reports the tree (felled by PZ, burnt, out of range).
        var it = placed.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (wanted.contains(e.getKey())) continue;
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
            if (!TerrainCoverage.known(x + 0.5, z + 0.5) && Math.hypot(x - sp.getX(), z - sp.getZ()) <= RADIUS + 8) continue;
            removeStack(level, x, z, e.getValue()[2]);
            staleRemoved++;
            it.remove();
        }

        List<Map<String, Object>> near = new ArrayList<>();
        trees.stream().sorted(java.util.Comparator.comparingDouble(t -> Math.hypot(t.x() + .5 - sp.getX(), t.y() + .5 - sp.getZ())))
                .limit(6).forEach(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("x", t.x()); m.put("z", t.y()); m.put("size", t.size()); m.put("kind", t.kind());
                    m.put("distance", Math.round(Math.hypot(t.x() + .5 - sp.getX(), t.y() + .5 - sp.getZ()) * 100) / 100.0);
                    m.put("placed", placed.containsKey(t.key()));
                    near.add(m);
                });
        nearest = List.copyOf(near);
    }

    /** One stack of invisible blocks on a tile, unless something else (or Steve) is already in the way. */
    private static boolean place(ServerLevel level, ServerPlayer sp, MaterialField.Tree t, int height) {
        if (level.getChunkSource().getChunkNow(t.x() >> 4, t.y() >> 4) == null) return false;
        BlockState want = stateFor(t);
        AABB steve = sp.getBoundingBox();
        for (int y = 0; y < height; y++) {
            BlockPos pos = new BlockPos(t.x(), y, t.y());
            BlockState current = level.getBlockState(pos);
            if (current != want && !current.isAir() && !current.is(PzBlocks.TREE)) return false; // someone built here
            if (steve.intersects(new AABB(pos))) return false;                                      // do not wall Steve in
        }
        int[] old = placed.get(t.key());
        if (old != null && old[2] != height) removeStack(level, t.x(), t.y(), old[2]);
        for (int y = 0; y < height; y++) {
            BlockPos pos = new BlockPos(t.x(), y, t.y());
            if (level.getBlockState(pos) != want) level.setBlock(pos, want, QUIET);
        }
        placed.put(t.key(), new int[] {t.size(), t.kind(), height});
        return true;
    }

    private static void removeStack(ServerLevel level, int x, int z, int height) {
        for (int y = 0; y < Math.max(height, 4); y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).is(PzBlocks.TREE)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET_REMOVE);
        }
    }

    /** Tree blocks saved from an earlier session or left behind by anything else have no placement record: remove them. */
    private static void sweepOrphans(ServerLevel level, ServerPlayer sp) {
        int cx = sp.blockPosition().getX() >> 4, cz = sp.blockPosition().getZ() >> 4;
        Set<Long> wanted = new HashSet<>();
        for (MaterialField.Tree t : MaterialField.trees(sp.getX(), sp.getZ(), RADIUS + 16)) wanted.add(t.key());
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (chunk == null) continue;
                int index = chunk.getSectionIndexFromSectionY(0);
                if (index < 0 || index >= chunk.getSections().length) continue;
                var section = chunk.getSection(index);
                if (!section.maybeHas(state -> state.is(PzBlocks.TREE))) continue;
                for (int y = 0; y < 4; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    if (!section.getBlockState(x, y, z).is(PzBlocks.TREE)) continue;
                    int wx = ((cx + dx) << 4) + x, wz = ((cz + dz) << 4) + z;
                    if (wanted.contains(key(wx, wz)) && TerrainCoverage.known(wx + 0.5, wz + 0.5)) continue; // PZ still has it
                    if (!TerrainCoverage.known(wx + 0.5, wz + 0.5)) continue; // cannot tell yet
                    level.setBlock(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), QUIET_REMOVE);
                    placed.remove(key(wx, wz));
                    orphansRemoved++;
                }
            }
        }
    }

    // ---- felling ----

    /** Called from {@link PzTreeBlock} when a tree block was removed by gameplay. Server thread. */
    static void felled(ServerLevel level, BlockPos pos, BlockState state) {
        int tx = pos.getX(), tz = pos.getZ();
        long k = key(tx, tz);
        for (int dy = -4; dy <= 4; dy++) {
            BlockPos other = pos.offset(0, dy, 0);
            if (dy != 0 && level.getBlockState(other).is(PzBlocks.TREE)) level.setBlock(other, Blocks.AIR.defaultBlockState(), QUIET_REMOVE);
        }
        if (placed.remove(k) == null && felledUntil.containsKey(k)) return; // already handled for this stack
        felledUntil.put(k, tick + FELLED_HOLD);
        int size = state.getValue(PzTreeBlock.SIZE), kind = state.getValue(PzTreeBlock.KIND);
        Session.send(Wire.MSG_TREE_FELLED, Wire.encodeTile(tx, tz, 0));
        drops(level, new BlockPos(tx, 0, tz), size, kind, level.getRandom());
        fellCount++;
        PzCraftClient.LOG.info("tree felled at ({}, {}) size {} kind {}: PZ told to topple it", tx, tz, size, kind);
    }

    private static void drops(ServerLevel level, BlockPos base, int size, int kind, net.minecraft.util.RandomSource random) {
        int[] yield = {1, 1, 2, 3, 4, 5, 6, 8};
        int logs = Math.max(1, yield[Math.max(1, Math.min(8, size)) - 1] - 1); // PZ's own count, at least one
        Block.popResource(level, base, new ItemStack(logFor(kind), logs));
        Block.popResource(level, base, new ItemStack(Items.STICK, 1 + size / 2));
        if (size >= 2 && random.nextInt(4) == 0) Block.popResource(level, base, new ItemStack(saplingFor(kind)));
    }

    private static net.minecraft.world.item.Item logFor(int kind) {
        return switch (kind) {
            case Materials.TREE_SPRUCE -> Items.SPRUCE_LOG;
            case Materials.TREE_BIRCH -> Items.BIRCH_LOG;
            case Materials.TREE_MAPLE -> Items.DARK_OAK_LOG;
            case Materials.TREE_FLOWERING -> Items.CHERRY_LOG;
            default -> Items.OAK_LOG;
        };
    }

    private static net.minecraft.world.item.Item saplingFor(int kind) {
        return switch (kind) {
            case Materials.TREE_SPRUCE -> Items.SPRUCE_SAPLING;
            case Materials.TREE_BIRCH -> Items.BIRCH_SAPLING;
            case Materials.TREE_MAPLE -> Items.DARK_OAK_SAPLING;
            case Materials.TREE_FLOWERING -> Items.CHERRY_SAPLING;
            default -> Items.OAK_SAPLING;
        };
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wanted", wantedCount);
        m.put("placedStacks", placed.size());
        m.put("placedTotal", placedCount);
        m.put("blocked", blockedCount);
        m.put("staleRemoved", staleRemoved);
        m.put("orphansRemoved", orphansRemoved);
        m.put("felled", fellCount);
        m.put("nearest", nearest);
        return m;
    }
}

