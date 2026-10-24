package pzcraft.mc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import pzcraft.protocol.Wire;

/**
 * PZ's zombies break what Steve built in Minecraft, as they break what a player builds in PZ. A zombie after the player
 * (PZ's {@link Wire.Actor#FLAG_CHASING}) that has stood pressed against a Minecraft block for two seconds thumps it every
 * 1.5 s: the block cracks (vanilla's breaking stages, which the native renderer draws), the zombie door-attack and the
 * block's hit sounds play, PZ plays its thump sound and noise at the zombie (more zombies come), and at the end the
 * block breaks with its drops. As in PZ, a crowd breaks through fast and one zombie slowly: a thump does full damage
 * when four or more zombies push at the block, a share of it when fewer do. A block's health follows vanilla's hardness
 * and blast resistance (dirt 3 thumps, planks 14, stone 18, obsidian never in practice); unbreakable blocks, PzCraft's
 * own (trees, ground), what lies under the zombie's feet and what has no collision are left alone. Cracks heal after 30 s
 * without a thump. Single player (the zombies come over the player link).
 */
public final class ZombieSiege {
    private static final int SCAN = 5, PRESSED = 40, THUMP_EVERY = 30, HEAL_AFTER = 600, FULL_CROWD = 4;
    private record Track(double x, double z, long since) {}
    private static final class Damage { double thumps; long last; final int crack; Damage(int crack) { this.crack = crack; } }

    private static final Map<Integer, Track> tracks = new HashMap<>();
    private static final Map<Integer, Long> lastThump = new HashMap<>();
    private static final Map<BlockPos, Damage> damage = new HashMap<>();
    private static long tick;
    private static int nextCrack = -0x5A0000;
    private static volatile int thumps, broken;

    private ZombieSiege() {}

    /** Main entrypoint (both sides). */
    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ZombieSiege::tick);
    }

    private static void tick(MinecraftServer server) {
        tick++;
        if (tick % SCAN != 0 || !Session.pzConnected || !Session.released) return;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        ServerPlayer steve = players.getFirst();
        ServerLevel level = steve.level();
        List<Wire.Actor> actors = Session.actors;
        Set<Integer> present = new HashSet<>();
        Map<Integer, BlockPos> pressing = new HashMap<>();
        for (Wire.Actor a : actors) {
            if ((a.flags() & Wire.Actor.FLAG_CHASING) == 0 || (a.flags() & Wire.Actor.FLAG_ANIMAL) != 0) continue;
            present.add(a.id());
            Track t = tracks.get(a.id());
            if (t == null || Math.hypot(a.x() - t.x(), a.z() - t.z()) > 0.25) { tracks.put(a.id(), new Track(a.x(), a.z(), tick)); continue; }
            if (tick - t.since() < PRESSED) continue;              // still getting somewhere
            BlockPos block = blockInTheWay(level, a, steve);
            if (block != null) pressing.put(a.id(), block);
        }
        tracks.keySet().retainAll(present);
        lastThump.keySet().retainAll(present);
        for (var e : pressing.entrySet()) {
            if (tick - lastThump.getOrDefault(e.getKey(), Long.MIN_VALUE / 2) < THUMP_EVERY) continue;
            lastThump.put(e.getKey(), tick);
            int crowd = 0;
            for (BlockPos other : pressing.values()) if (other.distManhattan(e.getValue()) <= 2) crowd++;
            thump(level, e.getValue(), e.getKey(), Math.min(1.0, crowd / (double) FULL_CROWD));
        }
        for (Iterator<Map.Entry<BlockPos, Damage>> it = damage.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (tick - e.getValue().last < HEAL_AFTER && !level.getBlockState(e.getKey()).isAir()) continue;
            level.destroyBlockProgress(e.getValue().crack, e.getKey(), -1);
            it.remove();
        }
    }

    /** The block between the zombie and Steve, right in front of it at its legs or chest (straight ahead, or along one axis). */
    private static BlockPos blockInTheWay(ServerLevel level, Wire.Actor a, ServerPlayer steve) {
        double dx = steve.getX() - a.x(), dz = steve.getZ() - a.z(), d = Math.hypot(dx, dz);
        if (d < 0.5 || d > 24) return null;
        dx /= d; dz /= d;
        for (double reach : new double[] {0.6, 1.0}) {
            for (double up : new double[] {0.3, 1.2}) {
                double y = a.y() + up;
                for (double[] dir : new double[][] {{dx, dz}, {Math.signum(dx), 0}, {0, Math.signum(dz)}}) {
                    if (dir[0] == 0 && dir[1] == 0) continue;
                    BlockPos p = BlockPos.containing(a.x() + dir[0] * reach, y, a.z() + dir[1] * reach);
                    if (p.getY() >= Math.floor(a.y() + 0.05) && breakable(level, p)) return p;
                }
            }
        }
        return null;
    }

    private static boolean breakable(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.getDestroySpeed(level, p) < 0) return false;
        if (PzBlocks.NAMESPACE.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace())) return false;
        if (p.getY() < 0 && GroundBridge.isGroundColumn(p.getX(), p.getZ())) return false; // PZ's ground, under a dug hole
        return !s.getCollisionShape(level, p).isEmpty();
    }

    /** Thumps a block of this kind takes from a crowd: vanilla's hardness and blast resistance, from 2 up to 600. */
    static double health(float hardness, float resistance) {
        return Math.max(2, Math.min(600, Math.round(hardness * 4 + resistance * 2)));
    }

    private static void thump(ServerLevel level, BlockPos pos, int zombie, double share) {
        BlockState state = level.getBlockState(pos);
        Damage d = damage.computeIfAbsent(pos, p -> new Damage(nextCrack++));
        d.thumps += share;
        d.last = tick;
        thumps++;
        double health = health(state.getDestroySpeed(level, pos), state.getBlock().getExplosionResistance());
        SoundType sound = state.getSoundType();
        boolean metal = sound == SoundType.METAL || sound == SoundType.COPPER || sound == SoundType.ANVIL;
        level.playSound(null, pos, metal ? SoundEvents.ZOMBIE_ATTACK_IRON_DOOR : SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 0.5f, 0.9f + level.getRandom().nextFloat() * 0.2f);
        level.playSound(null, pos, sound.getHitSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 4, sound.getPitch() * 0.5f);
        Session.send(Wire.MSG_ZOMBIE_THUMP, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(zombie).array());
        if (d.thumps < health) {
            level.destroyBlockProgress(d.crack, pos, Math.min(9, (int) (10 * d.thumps / health)));
            return;
        }
        level.destroyBlockProgress(d.crack, pos, -1);
        damage.remove(pos);
        level.destroyBlock(pos, true);
        broken++;
        PzCraftMain.LOG.info("zombies broke {} at {}", state.getBlock(), pos);
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thumps", thumps);
        m.put("broken", broken);
        m.put("damaged", damage.size());
        return m;
    }
}
