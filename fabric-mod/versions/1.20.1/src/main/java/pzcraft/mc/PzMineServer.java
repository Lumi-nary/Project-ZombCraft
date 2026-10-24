package pzcraft.mc;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldTargets;

/**
 * The server's half of mining PZ things: Steve's client says it finished mining a target ({@link Mine}); the server checks
 * that PZ still reports it and that it is within reach, wears the tool, and asks PZ to remove it. Only when PZ answers
 * that it was there and is gone do the drops fall (a target PZ could not remove drops nothing, so nothing can be mined
 * twice). Single player today: a dedicated server gets no targets from the PZ server yet (multiplayer M3).
 */
public final class PzMineServer {
    public record Mine(int kind, int variant, int x, int y, int level, int key) implements net.fabricmc.fabric.api.networking.v1.FabricPacket {
        public static final net.fabricmc.fabric.api.networking.v1.PacketType<Mine> TYPE=net.fabricmc.fabric.api.networking.v1.PacketType.create(new ResourceLocation(PzBlocks.NAMESPACE,"mine_pz"),Mine::read);
        public void write(net.minecraft.network.FriendlyByteBuf b){b.writeByte(kind);b.writeByte(variant);b.writeInt(x);b.writeInt(y);b.writeByte(level);b.writeInt(key);}
        private static Mine read(net.minecraft.network.FriendlyByteBuf b){return new Mine(b.readUnsignedByte(),b.readUnsignedByte(),b.readInt(),b.readInt(),b.readByte(),b.readInt());}
        @Override public net.fabricmc.fabric.api.networking.v1.PacketType<?> getType(){return TYPE;}
    }

    private record Pending(UUID player, WorldTargets.Target target, boolean harvest, boolean shears, long tick) {}

    /** Ticks after which an unanswered request is dropped (PZ paused or gone): nothing falls. */
    private static final long TIMEOUT_TICKS = 200;
    private static final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private static final ConcurrentLinkedQueue<WorldTargets.Mined> answers = new ConcurrentLinkedQueue<>();
    private static int seq;
    private static long tick;
    private static volatile int requested, removed, refused, missed, expired;

    private PzMineServer() {}

    /** Main entrypoint (both sides: the packet type must be known to client and server). */
    static void init() {
        ServerPlayNetworking.registerGlobalReceiver(Mine.TYPE, (mine,player,sender)->request(player,mine));
        ServerTickEvents.END_SERVER_TICK.register(PzMineServer::tick);
    }

    /** Link thread: PZ's answer. */
    public static void answer(byte[] payload) {
        if (answers.size() < 256) answers.add(WorldTargets.Mined.decode(payload));
    }

    /** Server thread. */
    private static void request(ServerPlayer player, Mine m) {
        WorldTargets.Target t = PzTargets.find(m.kind(), m.x(), m.y(), m.level(), m.key());
        if (t == null || !player.isAlive() || player.isSpectator() || pending.size() >= 32) { refused++; return; }
        var center = PzTargets.center(t);
        if (player.getEyePosition().distanceTo(center) > (player.isCreative()?5.0:4.5) + 2) { refused++; return; }
        PzMineables.Def def = PzMineables.def(t.kind(), t.variant());
        ItemStack tool = player.getMainHandItem();
        boolean harvest = def.harvests(player), shears = tool.is(Items.SHEARS);
        BlockPos pos = PzTargets.voxel(t);
        tool.mineBlock(player.level(), def.standIn(), pos, player); // tool wear, as for the stand-in block
        int id = ++seq;
        pending.put(id, new Pending(player.getUUID(), t, harvest, shears, tick));
        Session.send(Wire.MSG_PZ_MINE, WorldTargets.Mine.of(id, t).encode());
        requested++;
    }

    private static void tick(MinecraftServer server) {
        tick++;
        WorldTargets.Mined a;
        while ((a = answers.poll()) != null) {
            Pending p = pending.remove(a.seq());
            if (p == null) continue;
            if (!a.removed()) { missed++; PzTargets.unhide(p.target()); continue; }
            removed++;
            ServerPlayer player = server.getPlayerList().getPlayer(p.player());
            ServerLevel level = player != null ? (ServerLevel)player.level() : server.overworld();
            if (player != null) player.causeFoodExhaustion(0.005f); // as for breaking a block
            if (!p.harvest()) continue;
            BlockPos pos = PzTargets.voxel(p.target());
            for (PzMineables.Drop drop : PzMineables.drops(p.target().kind(), p.target().variant(), level.getRandom(), p.shears())) {
                Block.popResource(level, pos, drop.stack());
            }
        }
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (tick - p.tick() > TIMEOUT_TICKS) { it.remove(); expired++; PzTargets.unhide(p.target()); }
        }
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", requested);
        m.put("removed", removed);
        m.put("refused", refused);
        m.put("missed", missed);
        m.put("expired", expired);
        m.put("pending", pending.size());
        return m;
    }
}
