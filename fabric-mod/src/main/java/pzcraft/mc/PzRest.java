package pzcraft.mc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.BedRule;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.WorldTargets;

/**
 * Sitting on PZ seats and sleeping in PZ beds (W4), server side. Right-clicking one sends {@link Use}: a seat (chair, couch,
 * bench: PZ's seating data) seats Steve on a {@link PzSeat}; a bed runs vanilla's sleeping rules (the dimension's bed rule,
 * no monsters near: PZ's zombie proxies count) and sets the respawn point on it. PZ gives every seat its bed flag (it lets
 * you sleep in a chair), so a seat is always sat on first, and a second right-click while sitting on one lies down on it
 * when the night allows sleep (before, every chair became a bed at night and nothing could be sat on, B21). Vanilla then does the rest: the night skips when enough players sleep (PZ's clock follows, as with
 * Minecraft beds), the sleeper wakes at day, "Leave Bed" stands him up. {@link #bedAt} tells vanilla the bed is still there.
 */
public final class PzRest {
    public record Use(int kind, int x, int y, int level, int key) implements CustomPacketPayload {
        public static final Type<Use> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "rest_pz"));
        static final StreamCodec<RegistryFriendlyByteBuf, Use> CODEC = CustomPacketPayload.codec(Use::write, Use::read);

        private void write(RegistryFriendlyByteBuf b) { b.writeByte(kind); b.writeInt(x); b.writeInt(y); b.writeByte(level); b.writeInt(key); }

        private static Use read(RegistryFriendlyByteBuf b) { return new Use(b.readUnsignedByte(), b.readInt(), b.readInt(), b.readByte(), b.readInt()); }

        @Override public Type<Use> type() { return TYPE; }
    }

    /** Where Steve sleeps in a PZ bed -> that bed (PZ's target) and the way it lies. */
    private record Bed(WorldTargets.Target target, Direction facing) {}
    private static final Map<BlockPos, Bed> beds = new ConcurrentHashMap<>();
    private static volatile int sat, slept, refused;

    private PzRest() {}

    /** Main entrypoint (both sides). */
    static void init() {
        PayloadTypeRegistry.serverboundPlay().register(Use.TYPE, Use.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Use.TYPE, (use, context) -> use(context.player(), use));
    }

    public static boolean restsOn(WorldTargets.Target t) {
        return t != null && (t.kind() == WorldTargets.KIND_FURNITURE || t.kind() == WorldTargets.KIND_SOLID)
                && (WorldTargets.isSeat(t.variant()) || WorldTargets.isBed(t.variant()));
    }

    /** Server thread. */
    private static void use(ServerPlayer player, Use u) {
        WorldTargets.Target t = PzTargets.find(u.kind(), u.x(), u.y(), u.level(), u.key());
        if (!restsOn(t) || !player.isAlive() || player.isSpectator() || player.isSleeping()
                || player.getEyePosition().distanceTo(PzTargets.center(t)) > player.blockInteractionRange() + 2) { refused++; return; }
        ServerLevel level = player.level();
        boolean bed = WorldTargets.isBed(t.variant()), seat = WorldTargets.isSeat(t.variant());
        BlockPos top = BlockPos.containing(t.x() + .5, PzTargets.floorY(t.level()) + t.y1() - .01, t.y() + .5);
        BedRule rule = level.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, top);
        boolean seated = player.getVehicle() instanceof PzSeat ps && !ps.isCar();
        if (seat && !(bed && seated && rule.canSleep(level))) sit(player, level, t);
        else if (bed) sleep(player, level, t, top, rule);
    }

    private static Vec3 surface(WorldTargets.Target t) {
        return new Vec3(t.x() + .5, PzTargets.floorY(t.level()) + t.y1(), t.y() + .5);
    }

    private static float yaw(int face) {
        return switch (face) { case WorldTargets.FACE_N -> 180f; case WorldTargets.FACE_E -> -90f; case WorldTargets.FACE_W -> 90f; default -> 0f; };
    }

    private static void sit(ServerPlayer player, ServerLevel level, WorldTargets.Target t) {
        if (player.isPassenger()) player.stopRiding();
        PzSeat seat = new PzSeat(PzSeat.TYPE, level);
        Vec3 at = surface(t);
        seat.setPos(at.x, at.y, at.z);
        seat.setYRot(yaw(WorldTargets.pieceFace(t.variant())));
        seat.seat(t);
        level.addFreshEntity(seat);
        if (player.startRiding(seat, true, true)) sat++;
        else { seat.discard(); refused++; }
    }

    /** ServerPlayer.startSleepInBed's steps, for a bed that is a PZ object instead of a bed block. */
    private static void sleep(ServerPlayer player, ServerLevel level, WorldTargets.Target t, BlockPos pos, BedRule rule) {
        if (rule.canSetSpawn(level)) {
            // forced: there is no bed block to check at respawn; the PZ floor under the spot is found as for any spawn
            player.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), pos, player.getYRot(), player.getXRot()), true), true);
        }
        if (!rule.canSleep(level)) {
            var message = rule.asProblem().message();
            if (message != null) player.sendOverlayMessage(message);
            refused++;
            return;
        }
        if (!player.isCreative()) {
            Vec3 c = Vec3.atBottomCenterOf(pos);
            List<Monster> monsters = level.getEntitiesOfClass(Monster.class, new AABB(c.x - 8, c.y - 5, c.z - 8, c.x + 8, c.y + 5, c.z + 8),
                    m -> m.isPreventingPlayerRest(level, player));
            if (!monsters.isEmpty()) {
                var message = Player.BedSleepingProblem.NOT_SAFE.message();
                if (message != null) player.sendOverlayMessage(message);
                refused++;
                return;
            }
        }
        if (player.isPassenger()) player.stopRiding();
        Vec3 at = surface(t);
        beds.put(pos, new Bed(t, Direction.fromYRot(yaw(WorldTargets.pieceFace(t.variant())))));
        player.setPos(at.x, at.y + .02, at.z);
        player.setPose(Pose.SLEEPING);
        player.setSleepingPos(pos);
        player.setDeltaMovement(Vec3.ZERO);
        ((pzcraft.mc.mixin.PlayerSleepAccessor) player).pzcraft$setSleepCounter(0);
        level.updateSleepingPlayerList();
        slept++;
    }

    /**
     * Whether a sleeper's bed position is a PZ bed that is still there (LivingEntity.checkBedExists), and which way it lies
     * (getBedOrientation); null when it is no PZ bed. Until PZ has reported again, it is still there.
     */
    public static Direction bedAt(BlockPos pos) {
        Bed b = beds.get(pos);
        if (b == null) return null;
        WorldTargets.Target t = b.target();
        if (PzTargets.fresh() && PzTargets.find(t.kind(), t.x(), t.y(), t.level(), t.key()) == null) {
            beds.remove(pos);
            return null;
        }
        return b.facing();
    }

    public static void stoppedSleeping(BlockPos pos) { beds.remove(pos); }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sat", sat);
        m.put("slept", slept);
        m.put("refused", refused);
        m.put("bedsInUse", beds.size());
        return m;
    }
}
