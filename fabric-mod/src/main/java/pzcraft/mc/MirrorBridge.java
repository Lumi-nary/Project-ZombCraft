package pzcraft.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import pzcraft.protocol.Mirror;
import pzcraft.protocol.Wire;

/**
 * Steve's inventory as PZ's hidden character sees it. Minecraft's inventory is the real one: this sends what it holds as
 * groups of identical items (so PZ can hold the same: keys, bags, and stand-ins for what only Minecraft has), and applies the
 * transactions PZ sends back (what it crafted, used up or picked up) exactly once. Nothing is destroyed: what does not fit
 * is dropped at Steve's feet, what is missing is skipped. See {@link Mirror} and PZ's MirrorBridge.
 */
public final class MirrorBridge {
    private static final int WORN_FIRST = 36, RING = 16, PAYLOAD_BUDGET = 150_000, REJECTED_KEPT = 32;
    private static final EquipmentSlot[] WORN = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
    /** The ids of PZ transactions already applied, saved with the player so a resend after a restart is never applied twice. */
    private static AttachmentType<List<Long>> APPLIED;

    private static final ConcurrentLinkedQueue<String> transactions = new ConcurrentLinkedQueue<>();
    private static volatile boolean helloRequested;
    private static final Map<String, Boolean> sentBlobs = new LinkedHashMap<>();
    private static Map<String, Integer> lastSent = Map.of();
    private static long lastSentAt, seq;
    private static boolean dirty;
    private static final List<String> rejected = new ArrayList<>();
    private static volatile int sent, applied, duplicates, added, removed, replaced, rejectedCount, skipped, failures, unencodableStacks;
    private static volatile String last = "";

    private MirrorBridge() {}

    /** Main entrypoint, with the registries still open. */
    public static void init() {
        APPLIED = AttachmentRegistry.createPersistent(Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "mirror_applied"), Codec.LONG.listOf());
        ServerTickEvents.END_SERVER_TICK.register(MirrorBridge::tick);
    }

    // ---- link thread ----

    static void hello() { helloRequested = true; }

    static void receiveTransaction(byte[] payload) {
        if (transactions.size() < 16) transactions.add(new String(payload, StandardCharsets.UTF_8));
    }

    // ---- the slots Steve carries things in ----

    private static ItemStack get(ServerPlayer p, int i) { return i < WORN_FIRST ? p.getInventory().getItem(i) : p.getItemBySlot(WORN[i - WORN_FIRST]); }

    private static void set(ServerPlayer p, int i, ItemStack s) {
        if (i < WORN_FIRST) p.getInventory().setItem(i, s); else p.setItemSlot(WORN[i - WORN_FIRST], s);
    }

    private static int slotCount() { return WORN_FIRST + WORN.length; }

    private static String snbt(MinecraftServer server, ItemStack one) {
        var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        Tag tag = ItemStack.CODEC.encodeStart(ops, one.copyWithCount(1)).result().orElseThrow();
        return tag.toString();
    }

    private static ItemStack fromSnbt(MinecraftServer server, String text, int count) {
        try {
            var ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            ItemStack s = ItemStack.CODEC.parse(ops, TagParser.parseCompoundFully(text)).result().orElse(ItemStack.EMPTY);
            if (!s.isEmpty()) s.setCount(count);
            return s;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return ItemStack.EMPTY;
        }
    }

    /** The key of a stack: what PZ computes from the item it made of it (PZ types), or a hash of its text (everything else). */
    private static String keyOf(MinecraftServer server, ItemStack st) {
        String type = PzItems.typeOf(st);
        if (type == null) return Mirror.mcKey(snbt(server, st));
        JsonObject s = PzItems.stateOf(st);
        return Mirror.key(type, s.has("c") ? s.get("c").getAsInt() : null, s.has("k"), s.has("u") ? s.get("u").getAsDouble() : null,
                s.has("nm") ? s.get("nm").getAsString() : null, s.has("i") ? s.get("i").getAsInt() : null);
    }

    private static boolean sameKey(MinecraftServer server, ItemStack st, String key) {
        try {
            return keyOf(server, st).equals(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static final class Group {
        int count;
        ItemStack sample;
    }

    private static Map<String, Group> groups(MinecraftServer server, ServerPlayer p) {
        Map<String, Group> m = new LinkedHashMap<>();
        int unencodable = 0;
        for (int i = 0; i < slotCount(); i++) {
            ItemStack st = get(p, i);
            if (st.isEmpty()) continue;
            String key;
            try {
                key = keyOf(server, st);
            } catch (RuntimeException e) { // a stack that cannot be written as text cannot be mirrored: it stays in Minecraft only
                unencodable++;
                continue;
            }
            Group g = m.computeIfAbsent(key, k -> new Group());
            g.count += st.getCount();
            if (g.sample == null) g.sample = st;
        }
        unencodableStacks = unencodable;
        return m;
    }

    // ---- state for PZ ----

    private static void tick(MinecraftServer server) {
        if (!Session.pzConnected || APPLIED == null) return;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        ServerPlayer player = players.getFirst();
        try {
            String tx;
            while ((tx = transactions.poll()) != null) applyTransaction(server, player, tx);
            if (helloRequested) { helloRequested = false; sentBlobs.clear(); dirty = true; }
            long now = System.currentTimeMillis();
            if (!dirty && server.getTickCount() % 4 != 0) return;
            Map<String, Group> groups = groups(server, player);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map.Entry<String, Group> e : groups.entrySet()) counts.put(e.getKey(), e.getValue().count);
            if (!dirty && counts.equals(lastSent) && now - lastSentAt < 5000) return;
            send(server, player, groups, counts, now);
        } catch (RuntimeException e) {
            failures++;
            last = "failed: " + e;
            PzCraftClient.LOG.error("inventory mirror failed", e);
        }
    }

    private static void send(MinecraftServer server, ServerPlayer player, Map<String, Group> groups, Map<String, Integer> counts, long now) {
        JsonArray list = new JsonArray();
        int budget = PAYLOAD_BUDGET, skip = 0;
        Map<String, Boolean> newlySent = new LinkedHashMap<>();
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            String key = e.getKey();
            Group g = e.getValue();
            JsonObject entry = new JsonObject();
            String type = PzItems.typeOf(g.sample);
            entry.addProperty("key", key);
            entry.addProperty("n", g.count);
            int size = 160;
            if (type == null) {
                entry.addProperty("t", "mc");
                String text = snbt(server, g.sample);
                entry.addProperty("mc", text);
                entry.addProperty("label", g.sample.getHoverName().getString());
                size += text.length();
            } else {
                entry.addProperty("t", type);
                for (Map.Entry<String, JsonElement> f : PzItems.stateOf(g.sample).entrySet()) {
                    if (f.getKey().equals("b") && sentBlobs.containsKey(key)) continue; // PZ has these bytes already
                    entry.add(f.getKey(), f.getValue());
                    if (f.getKey().equals("b")) size += f.getValue().getAsString().length();
                }
            }
            if (size > budget) { skip++; continue; } // too much for one message: stays in Minecraft only
            budget -= size;
            if (entry.has("b")) newlySent.put(key, true);
            list.add(entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("seq", ++seq);
        root.add("groups", list);
        JsonArray acks = new JsonArray();
        for (long id : player.getAttachedOrElse(APPLIED, List.of())) acks.add(id);
        root.add("acks", acks);
        JsonArray rej = new JsonArray();
        for (String key : rejected) rej.add(key);
        root.add("rej", rej);
        root.addProperty("skipped", skip);
        Session.send(Wire.MSG_MIRROR_STATE, root.toString().getBytes(StandardCharsets.UTF_8));
        sentBlobs.putAll(newlySent);
        lastSent = counts;
        lastSentAt = now;
        dirty = false;
        skipped = skip;
        sent++;
    }

    // ---- what PZ changed ----

    private static void applyTransaction(MinecraftServer server, ServerPlayer player, String json) {
        JsonObject tx = JsonParser.parseString(json).getAsJsonObject();
        long id = tx.get("id").getAsLong();
        List<Long> ring = new ArrayList<>(player.getAttachedOrElse(APPLIED, List.of()));
        if (ring.contains(id)) { duplicates++; dirty = true; return; } // a resend: already applied, say so again
        double days = tx.has("now") ? tx.get("now").getAsDouble() : -1;
        int a = 0, r = 0, c = 0;
        for (JsonElement e : tx.getAsJsonArray("rem")) {
            JsonObject o = e.getAsJsonObject();
            r += remove(server, player, o.get("key").getAsString(), o.get("n").getAsInt());
        }
        for (JsonElement e : tx.getAsJsonArray("rep")) {
            JsonObject o = e.getAsJsonObject();
            if (replace(server, player, o.get("key").getAsString(), o.getAsJsonObject("e"), days)) c++;
        }
        for (JsonElement e : tx.getAsJsonArray("add")) a += add(server, player, e.getAsJsonObject(), days);
        ring.add(id);
        while (ring.size() > RING) ring.removeFirst();
        player.setAttached(APPLIED, ring);
        applied++;
        added += a;
        removed += r;
        replaced += c;
        dirty = true;
        last = "applied " + id + ": " + a + " added, " + r + " removed, " + c + " changed";
        PzCraftClient.LOG.info("inventory mirror: {} (rem {}, add {}, rep {})", last, tx.get("rem"), keys(tx.getAsJsonArray("add"), "key"), keys(tx.getAsJsonArray("rep"), "key"));
    }

    private static List<String> keys(JsonArray array, String field) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsJsonObject().get(field).getAsString());
        return out;
    }

    private static int remove(MinecraftServer server, ServerPlayer p, String key, int n) {
        int done = 0;
        // Identical items are interchangeable, so what PZ used up (a round put into a magazine, say) comes from the stack in
        // Steve's off-hand first, then the one he holds, then the first in slot order.
        int offhand = WORN_FIRST + WORN.length - 1, selected = p.getInventory().getSelectedSlot();
        int[] order = new int[slotCount()];
        int k = 0;
        order[k++] = offhand;
        if (selected != offhand) order[k++] = selected;
        for (int i = 0; i < slotCount(); i++) if (i != offhand && i != selected) order[k++] = i;
        for (int idx = 0; idx < order.length && done < n; idx++) {
            int i = order[idx];
            ItemStack st = get(p, i);
            if (st.isEmpty() || !sameKey(server, st, key)) continue;
            int take = Math.min(n - done, st.getCount());
            st.shrink(take);
            if (st.isEmpty()) set(p, i, ItemStack.EMPTY);
            done += take;
        }
        return done;
    }

    /** An item PZ changed in place (a bag's contents, a bottle's fluid): the new stack takes the old one's slot. */
    private static boolean replace(MinecraftServer server, ServerPlayer p, String key, JsonObject entry, double days) {
        ItemStack fresh = PzItems.stack(entry.get("t").getAsString(), 1, entry, days);
        if (fresh.isEmpty()) { reject(key); return false; }
        for (int i = 0; i < slotCount(); i++) {
            ItemStack st = get(p, i);
            if (!st.isEmpty() && sameKey(server, st, key)) { set(p, i, fresh); return true; }
        }
        return false;
    }

    private static int add(MinecraftServer server, ServerPlayer p, JsonObject entry, double days) {
        int n = entry.get("n").getAsInt(), done = 0;
        String type = entry.get("t").getAsString();
        String key = entry.get("key").getAsString();
        while (done < n) {
            ItemStack stack = type.equals("mc") ? fromSnbt(server, entry.get("mc").getAsString(), 1) : PzItems.stack(type, 1, entry, days);
            if (stack.isEmpty()) { reject(key); break; } // Minecraft has no such item: it stays with PZ only
            int take = Math.min(n - done, Math.max(1, stack.getMaxStackSize()));
            stack.setCount(take);
            p.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY); // a full inventory drops it at Steve's feet
            done += take;
        }
        return done;
    }

    private static void reject(String key) {
        rejectedCount++;
        if (!rejected.contains(key)) rejected.add(key);
        while (rejected.size() > REJECTED_KEPT) rejected.removeFirst();
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("states", sent);
        m.put("transactions", applied);
        m.put("duplicates", duplicates);
        m.put("added", added);
        m.put("removed", removed);
        m.put("changed", replaced);
        m.put("rejected", rejectedCount);
        m.put("skipped", skipped);
        m.put("unencodable", unencodableStacks);
        m.put("blobsSent", sentBlobs.size());
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}

