package pzcraft.mc;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
//#if MC_1_20
//$ import net.fabricmc.fabric.api.networking.v1.FabricPacket;
//$ import net.fabricmc.fabric.api.networking.v1.PacketType;
//$ import net.minecraft.network.FriendlyByteBuf;
//#else
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//#endif
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//#if MC_1_20
//#else
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer: Steve is put where the player's PZ character stands. In single player the client moves him on its own
 * integrated server; a client of a Minecraft server asks the server with {@link Place}, and the server moves the player
 * who asked (at most once a second; the server trusts its players, as PZ's own does). Until a player who joined or
 * respawned has been put there, the server does not take them for flying: they are held in the air while the PZ floor
 * under them arrives (ServerFloatingMixin, where PZ's floors also count as ground).
 */
public final class ServerPlacement {
    //#if MC_1_20
    //$ // 1.20.1 Fabric API: a FabricPacket (the same record, the same channel id and byte layout)
    //$ public record Place(double x, double y, double z, float yawDeg, float pitchDeg) implements FabricPacket {
    //$     public static final PacketType<Place> TYPE = PacketType.create(Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "place"), Place::read);
    //$
    //$     @Override public void write(FriendlyByteBuf b) {
    //$         b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yawDeg); b.writeFloat(pitchDeg);
    //$     }
    //$
    //$     private static Place read(FriendlyByteBuf b) {
    //$         return new Place(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat());
    //$     }
    //$
    //$     @Override public PacketType<?> getType() { return TYPE; }
    //$ }
    //#else
    public record Place(double x, double y, double z, float yawDeg, float pitchDeg) implements CustomPacketPayload {
        public static final Type<Place> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "place"));
        static final StreamCodec<RegistryFriendlyByteBuf, Place> CODEC = CustomPacketPayload.codec(Place::write, Place::read);

        private void write(RegistryFriendlyByteBuf b) {
            b.writeDouble(x); b.writeDouble(y); b.writeDouble(z); b.writeFloat(yawDeg); b.writeFloat(pitchDeg);
        }

        private static Place read(RegistryFriendlyByteBuf b) {
            return new Place(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat(), b.readFloat());
        }

        @Override public Type<Place> type() { return TYPE; }
    }
    //#endif

    private static final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> lastPlaced = new ConcurrentHashMap<>();

    private ServerPlacement() {}

    /** Main entrypoint (both sides: the packet type must be known to client and server). */
    static void init() {
        //#if MC_1_20
        //$ ServerPlayNetworking.registerGlobalReceiver(Place.TYPE, (place, player, sender) -> place(player, place));
        //#else
        PayloadTypeRegistry.serverboundPlay().register(Place.TYPE, Place.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Place.TYPE, (place, context) -> place(context.player(), place));
        //#endif
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> awaiting.add(handler.player.getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            awaiting.remove(handler.player.getUUID());
            lastPlaced.remove(handler.player.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> { if (!alive) awaiting.add(player.getUUID()); });
    }

    /** This player joined or respawned and has not been put at their PZ character yet. */
    public static boolean awaiting(ServerPlayer player) { return awaiting.contains(player.getUUID()); }

    /** Server thread. */
    private static void place(ServerPlayer player, Place p) {
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z()) || Math.abs(p.y()) > 4096) return;
        UUID id = player.getUUID();
        long now = System.currentTimeMillis();
        Long last = lastPlaced.get(id);
        if (last != null && now - last < 1000) return;
        lastPlaced.put(id, now);
        awaiting.remove(id);
        player.setDeltaMovement(0, 0, 0);
        player.connection.teleport(p.x(), p.y(), p.z(), p.yawDeg(), p.pitchDeg());
        PzCraftMain.LOG.info("{} put at their PZ character ({}, {}, {})", player.getName().getString(),
                String.format("%.2f", p.x()), String.format("%.2f", p.y()), String.format("%.2f", p.z()));
    }
}
