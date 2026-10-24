package pzcraft.mc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Fire;
import pzcraft.protocol.Wire;

/**
 * Fire between the two worlds, Minecraft's half ({@link Fire}; PZ's half is FireBridge). Minecraft fire on a PZ zombie's
 * stand-in (fire aspect, a flame arrow, fire, lava) asks PZ to set the zombie burning, which PZ then does its own way; a
 * zombie PZ has burning keeps its stand-in on fire, so Minecraft's flames are drawn on it. Flint and steel or a fire
 * charge used on PZ ground, floors or walls, a burning arrow stuck in PZ geometry and Minecraft fire at a PZ square start
 * PZ's fire there. PZ's fires set flammable Minecraft blocks next to them alight (Minecraft's own spread goes on from
 * there, by its fire-spread rule) and Steve when he stands in one. Single player. Server thread, except {@link #receiveFires}.
 */
public final class PzFire {
    private static final int RANGE = 48, ASK_AGAIN = 40, SQUARE_AGAIN = 100, IGNITES_PER_SECOND = 32;

    private static final Set<Integer> pzBurning = new HashSet<>();
    private static final Map<Integer, Long> asked = new HashMap<>();
    private static final Set<BlockPos> fires = new HashSet<>();
    private static final Map<Fire.Spot, Long> squares = new HashMap<>();
    private static volatile List<Fire.Spot> pzFires = List.of();
    private static long tick;
    private static int ignitesThisSecond;
    private static volatile int zombiesAsked, squaresAsked, blocksLit, steveLit, surfaceUses;

    private PzFire() {}

    /** Main entrypoint (both sides). */
    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(PzFire::tick);
    }

    /** Link thread: PZ's burning squares near Steve. */
    public static void receiveFires(byte[] payload) {
        try {
            pzFires = Fire.decodeSpots(payload);
        } catch (RuntimeException e) {
            PzCraftMain.LOG.debug("bad PZ fire list: {}", e.toString());
        }
    }

    /** The PZ square a Minecraft position is in. */
    static Fire.Spot spotOf(double x, double y, double z) {
        return new Fire.Spot((int) Math.floor(x), (int) Math.floor(z), (int) Math.floor(Coords.pzZ(y + 0.01)));
    }

    // ---- zombies ----

    /** Server thread, every tick for each stand-in (ActorProxies). */
    static void proxy(Entity stand, Wire.Actor actor) {
        int id = actor.id();
        if ((actor.flags() & Wire.Actor.FLAG_BURNING) != 0) {
            pzBurning.add(id);
            asked.remove(id);
            if (stand.getRemainingFireTicks() < 20) stand.setRemainingFireTicks(40);   // Minecraft's flames on PZ's burning zombie
            return;
        }
        if (pzBurning.remove(id)) {   // PZ put it out
            stand.clearFire();
            return;
        }
        if (stand.getRemainingFireTicks() <= 0) return;
        Long at = asked.get(id);
        if (at != null && tick - at < ASK_AGAIN) return;
        asked.put(id, tick);
        zombiesAsked++;
        Session.send(Fire.MSG_IGNITE_ACTOR, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array());
    }

    /** Server thread (ActorProxies): the stand-ins PZ no longer sends. */
    static void forget(Set<Integer> present) {
        pzBurning.retainAll(present);
        asked.keySet().retainAll(present);
    }

    // ---- Minecraft fire into PZ ----

    /** Flint and steel or a fire charge used on PZ ground, a PZ floor or wall (a PZ hit is a block hit on an air voxel). */
    public static boolean useOnPz(UseOnContext context, boolean charge) {
        Level level = context.getLevel();
        if (!level.getBlockState(context.getClickedPos()).isAir()) return false;
        Vec3 at = context.getClickLocation();
        if (!CollisionField.touches(new AABB(at, at).inflate(0.05))) return false;
        if (level.isClientSide()) return true;   // the swing; the server lights it
        Direction face = context.getClickedFace();
        Vec3 front = at.add(face.getStepX() * 0.1, face.getStepY() * 0.1, face.getStepZ() * 0.1);
        Player player = context.getPlayer();
        level.playSound(null, BlockPos.containing(front), charge ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE,
                SoundSource.BLOCKS, 1.0f, level.getRandom().nextFloat() * 0.4f + 0.8f);
        if (charge) {
            if (player == null || !player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        } else if (player != null) {
            context.getItemInHand().hurtAndBreak(1, player, p->p.broadcastBreakEvent(context.getHand()));
        }
        surfaceUses++;
        ignite(spotOf(front.x, front.y, front.z), charge ? 80 : 50, true, true);
        return true;
    }

    /** Server thread: a burning arrow stuck in PZ geometry. */
    public static void arrow(AbstractArrow arrow) {
        if (arrow.isOnFire()) ignite(spotOf(arrow.getX(), arrow.getY(), arrow.getZ()), 30, true, true);
    }

    /** Server thread: a Minecraft fire block appeared (any cause: flint and steel, spread, lava, lightning). */
    public static void placed(Level level, BlockPos pos) {
        if (level instanceof ServerLevel && fires.size() < 4096) fires.add(pos.immutable());
    }

    private static void ignite(Fire.Spot spot, int energy, boolean anywhere, boolean force) {
        Long at = squares.get(spot);
        if (!force && ((at != null && tick - at < SQUARE_AGAIN) || ignitesThisSecond >= IGNITES_PER_SECOND)) return;
        squares.put(spot, tick);
        ignitesThisSecond++;
        squaresAsked++;
        Session.send(Fire.MSG_IGNITE, new Fire.Ignite(spot, energy, anywhere).encode());
    }

    // ---- once a second ----

    private static void tick(MinecraftServer server) {
        tick++;
        if (tick % 20 != 0) return;
        ignitesThisSecond = 0;
        squares.values().removeIf(at -> tick - at > SQUARE_AGAIN);
        if (!Session.pzConnected || !Session.released || server.getPlayerList().getPlayers().isEmpty()) { fires.clear(); return; }
        ServerPlayer steve = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = (ServerLevel) steve.level();
        // Minecraft fires at PZ squares: PZ lights them where it finds something to burn
        for (Iterator<BlockPos> it = fires.iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            if (!(level.getBlockState(p).getBlock() instanceof BaseFireBlock) || p.distSqr(steve.blockPosition()) > RANGE * RANGE) {
                it.remove();
                continue;
            }
            ignite(spotOf(p.getX() + .5, p.getY(), p.getZ() + .5), 30, false, false);
        }
        // PZ's fires: flammable Minecraft blocks next to them, and Steve standing in one
        Fire.Spot steveAt = spotOf(steve.getX(), steve.getY(), steve.getZ());
        for (Fire.Spot s : pzFires) {
            if (s.equals(steveAt) && !steve.fireImmune() && !steve.isInWaterOrRain()) {
                steve.setSecondsOnFire(3);
                steveLit++;
            }
            if (level.getRandom().nextInt(3) == 0) spreadIntoMinecraft(level, s);
        }
    }

    private static void spreadIntoMinecraft(ServerLevel level, Fire.Spot s) {
        int y0 = (int) Math.floor(PzTargets.floorY(s.level())), y1 = (int) Math.floor(PzTargets.floorY(s.level()) + Coords.BLOCKS_PER_LEVEL);
        List<BlockPos> flammable = new ArrayList<>();
        for (int x = s.x() - 1; x <= s.x() + 1; x++)
            for (int z = s.y() - 1; z <= s.y() + 1; z++)
                for (int y = y0 - 1; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir() && state.ignitedByLava()) flammable.add(p);
                }
        if (flammable.isEmpty()) return;
        BlockPos block = flammable.get(level.getRandom().nextInt(flammable.size()));
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOFIRETICK)) return;
        for (Direction d : Direction.values()) {
            BlockPos air = block.relative(d);
            if (!BaseFireBlock.canBePlacedAt(level, air, Direction.NORTH)) continue;
            level.setBlock(air, BaseFireBlock.getState(level, air), 11);
            blocksLit++;
            return;
        }
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pzFires", pzFires.size());
        m.put("zombiesAsked", zombiesAsked);
        m.put("squaresAsked", squaresAsked);
        m.put("surfaceUses", surfaceUses);
        m.put("blocksLit", blocksLit);
        m.put("steveLit", steveLit);
        m.put("trackedFires", fires.size());
        return m;
    }

    /** World change: nothing carries over. */
    static void reset() {
        pzBurning.clear();
        asked.clear();
        fires.clear();
        squares.clear();
        pzFires = List.of();
    }
}
