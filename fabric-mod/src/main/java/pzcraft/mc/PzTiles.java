package pzcraft.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldTargets;

/**
 * PZ's furniture, walls and fences for Minecraft's creative tabs, from the {@code tiles.json} PZ exports with its items:
 * each entry has PZ's sprite, a name and an icon cut from that sprite. Furniture becomes the PZ item PZ's "Pick up" gives
 * (placed with W3's right-click); a wall or fence becomes a {@code pzcraft:pz_tile} stack that places PZ's sprite on the
 * edge of the floor tile clicked nearest (PZ's Brush Tool way, {@link Place}); in survival one is used up when PZ placed it.
 */
public final class PzTiles {
    public record Tile(String kind, String sprite, String name, String icon, String type) {}

    public static Item PZ_TILE;
    private static volatile List<Tile> tiles = List.of();
    private static long loadedStamp = Long.MIN_VALUE, checkedAt;

    private PzTiles() {}

    /** Main entrypoint, before registries freeze (both sides). */
    static void init() {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz_tile"));
        PZ_TILE = Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(64)));
        PayloadTypeRegistry.serverboundPlay().register(Place.TYPE, Place.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Place.TYPE, (place, context) -> request(context.player(), place));
        ServerTickEvents.END_SERVER_TICK.register(PzTiles::tick);
    }

    // ---- the list ----

    public static List<Tile> all() { refresh(); return tiles; }

    /** A stamp that changes when PZ exported again (the creative tabs rebuild then). */
    public static long stamp() { refresh(); return loadedStamp; }

    private static synchronized void refresh() {
        long now = System.nanoTime();
        if (now - checkedAt < 1_000_000_000L && loadedStamp != Long.MIN_VALUE) return;
        checkedAt = now;
        Path file = PzItemCatalog.DIR.resolve("tiles.json");
        try {
            if (!Files.isRegularFile(file)) return;
            long stamp = Files.getLastModifiedTime(file).toMillis() ^ Files.size(file);
            if (stamp == loadedStamp) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Tile> loaded = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("tiles")) {
                JsonObject o = el.getAsJsonObject();
                loaded.add(new Tile(str(o, "kind"), str(o, "sprite"), str(o, "name"), str(o, "icon"), str(o, "type")));
            }
            tiles = List.copyOf(loaded);
            loadedStamp = stamp;
            PzCraftMain.LOG.info("PZ tiles for the creative tabs: {}", loaded.size());
        } catch (IOException | RuntimeException e) {
            PzCraftMain.LOG.warn("could not read PZ's tiles.json: {}", e.toString());
        }
    }

    private static String str(JsonObject o, String key) { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null; }

    /** PZ exported this icon (so the item pack has a model for it). */
    public static boolean hasIcon(String icon) {
        return icon != null && Files.isRegularFile(PzItemCatalog.DIR.resolve("icons").resolve(icon.replaceAll("[^A-Za-z0-9_.-]", "_") + ".png"));
    }

    /** The creative stack for an entry; empty when it cannot be made (furniture before PZ's catalogue is there). */
    public static ItemStack stack(Tile t) {
        if ("furniture".equals(t.kind())) {
            if (t.type() == null) return ItemStack.EMPTY;
            for (String script : new String[] {"Base.Moveable", "Moveables.Moveable"}) {
                if (PzItemCatalog.get(script) == null) continue;
                com.google.gson.JsonObject state = new com.google.gson.JsonObject();
                state.addProperty("mv", script);
                if (t.name() != null) state.addProperty("dn", t.name());
                if (t.icon() != null) state.addProperty("ic", t.icon());
                return PzItems.stack(t.type(), 1, state, -1);
            }
            return ItemStack.EMPTY;
        }
        if (PZ_TILE == null || t.sprite() == null) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(PZ_TILE);
        CompoundTag tile = new CompoundTag();
        tile.putString("sprite", t.sprite());
        tile.putString("kind", t.kind());
        CompoundTag root = new CompoundTag();
        root.put("pz_tile", tile);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        s.set(DataComponents.ITEM_NAME, Component.literal(t.name() != null ? t.name() : t.sprite()));
        s.set(DataComponents.ITEM_MODEL, hasIcon(t.icon()) ? Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz/" + PzItemCatalog.iconKey(t.icon()))
                : Identifier.withDefaultNamespace("fence".equals(t.kind()) ? "oak_fence" : "bricks")); // no picture exported: a vanilla stand-in
        return s;
    }

    /** The PZ sprite a wall or fence stack places, or null. */
    public static String spriteOf(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != PZ_TILE) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag().getCompoundOrEmpty("pz_tile").getString("sprite").orElse(null);
    }

    // ---- placing (server) ----

    public record Place(String sprite, int x, int y, int level, boolean north) implements CustomPacketPayload {
        public static final Type<Place> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "place_tile"));
        static final StreamCodec<RegistryFriendlyByteBuf, Place> CODEC = CustomPacketPayload.codec(Place::write, Place::read);

        private void write(RegistryFriendlyByteBuf b) { b.writeUtf(sprite, 200); b.writeInt(x); b.writeInt(y); b.writeByte(level); b.writeBoolean(north); }

        private static Place read(RegistryFriendlyByteBuf b) { return new Place(b.readUtf(200), b.readInt(), b.readInt(), b.readByte(), b.readBoolean()); }

        @Override public Type<Place> type() { return TYPE; }
    }

    private record Pending(UUID player, String sprite, long tick) {}
    private static final Map<Integer, Pending> pending = new LinkedHashMap<>();
    private static final ConcurrentLinkedQueue<WorldTargets.Placed> answers = new ConcurrentLinkedQueue<>();
    private static int seq;
    private static long tick;

    /** Server thread: the player holds that wall or fence and the edge is within reach; PZ decides the rest. */
    private static void request(ServerPlayer player, Place p) {
        if (!player.isAlive() || player.isSpectator() || pending.size() >= 16 || !p.sprite().equals(spriteOf(player.getMainHandItem()))) return;
        var at = new net.minecraft.world.phys.Vec3(p.x() + .5, PzTargets.floorY(p.level()) + 1, p.y() + .5);
        if (player.getEyePosition().distanceTo(at) > player.blockInteractionRange() + 2) return;
        int id = ++seq;
        pending.put(id, new Pending(player.getUUID(), p.sprite(), tick));
        Session.send(Wire.MSG_PLACE_TILE, new WorldTargets.PlaceTile(id, p.x(), p.y(), p.level(), p.north(), p.sprite()).encode());
    }

    /** Link thread: PZ's answer. */
    public static void answer(byte[] payload) {
        if (answers.size() < 64) answers.add(WorldTargets.Placed.decode(payload));
    }

    private static void tick(MinecraftServer server) {
        tick++;
        WorldTargets.Placed a;
        while ((a = answers.poll()) != null) {
            Pending p = pending.remove(a.seq());
            ServerPlayer player = p == null ? null : server.getPlayerList().getPlayer(p.player());
            if (player == null) continue;
            if (!a.placed()) { player.sendOverlayMessage(Component.literal("It does not fit there")); continue; }
            ItemStack hand = player.getMainHandItem();
            if (!player.hasInfiniteMaterials() && p.sprite().equals(spriteOf(hand))) hand.shrink(1);
        }
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) if (tick - it.next().tick() > 200) it.remove();
    }
}
