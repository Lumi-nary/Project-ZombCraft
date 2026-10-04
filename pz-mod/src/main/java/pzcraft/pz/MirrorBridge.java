package pzcraft.pz;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import pzcraft.protocol.Mirror;
import pzcraft.protocol.Wire;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.InventoryContainer;

/**
 * The hidden PZ copy of Steve's inventory. While Steve drives, Minecraft's inventory is the real one and the character's
 * pockets in PZ are made to hold the same things, so PZ can use them: a key opens its car, a recipe finds its ingredients.
 * Things only Minecraft has (TNT, gravel...) are carried as {@code PzCraft.MCItem} stand-ins.
 *
 * <p>Bookkeeping: {@code ledger} is the state Minecraft last reported and PZ conformed to; it is saved in the player's mod
 * data together with the pockets, so both roll back together. What PZ changed on its own since (crafted, used up, picked up
 * in PZ-only play) is {@code pockets - ledger}; it is sent to Minecraft as one acknowledged transaction at a time, and
 * the copy is made to equal {@code minecraft + that change}. See {@link Mirror}. Nothing is ever destroyed to make the two agree.
 */
final class MirrorBridge {
    static final String WRAPPER = "PzCraft.MCItem";
    private static final String DATA = "PzCraftMirror", STAND_IN = "pzcraftMc";
    private static final int PAYLOAD_BUDGET = 150_000;
    private static final long SCAN_MS = 500, RESEND_MS = 4000;

    /** Link thread: the latest state Minecraft sent. */
    private static final AtomicReference<String> inbox = new AtomicReference<>();
    private static volatile boolean helloWanted;

    // saved with the player
    private static Map<String, Integer> ledger = new LinkedHashMap<>();
    private static Map<String, String> sigs = new HashMap<>();
    private static Pending pending;
    private static IsoPlayer loadedFor;
    private static boolean dirty, assumeLedger;

    // this session
    private static boolean active, helloSent;
    private static int helloEpoch = -1;
    private static Map<String, Map<String, Object>> entries = new HashMap<>();
    private static Map<String, Integer> mcCounts;
    private static Set<Long> acks = Set.of();
    private static Set<String> rejectedByMc = Set.of();
    private static final Map<String, String> blobCache = new HashMap<>();
    private static final Set<String> unmirrorable = new HashSet<>();
    /** Kinds Minecraft cannot represent: they stay in the pockets and are left out of every comparison. */
    private static final Set<String> localOnly = new HashSet<>();
    /** Kinds where PZ refused to add one more copy (same item id): not retried until Minecraft stops asking for it. */
    private static final Set<String> refused = new HashSet<>();
    private static long lastScan;
    private static boolean freshState;

    /** A loop nobody foresaw (items made and dropped over and over) must not run on: the mirror stops itself and says so. */
    private static final int BREAKER_CHANGES = 300;
    private static final long BREAKER_WINDOW_MS = 10_000;
    private static long breakerStart;
    private static int breakerChanges;
    private static boolean tripped;

    private static volatile String status = "idle";
    private static volatile String last = "";
    private static int statesApplied, txSent, txAcked, created, removed, replaced, failures;

    private MirrorBridge() {}

    /** A transaction in flight: what was sent, the count changes it carries and the signatures it settles. */
    private record Pending(long id, String json, Map<String, Integer> delta, Map<String, String> sigs, long[] sentAt) {}

    // ---- link thread ----

    static void receiveState(String json) { inbox.set(json); }

    // ---- game thread ----

    static void tick(IsoPlayer player) {
        if (player == null) return;
        if (!SteveControl.drives(player)) {
            if (active) { active = false; helloSent = false; mcCounts = null; status = "idle: Steve does not drive"; }
            return;
        }
        try {
            if (tripped) return;
            if (loadedFor != player) load(player);
            if (!active) { active = true; helloSent = false; mcCounts = null; }
            int epoch = LinkService.epoch();
            if (!helloSent || epoch != helloEpoch || helloWanted) {
                helloWanted = false;
                helloEpoch = epoch;
                helloSent = LinkService.send(Wire.MSG_MIRROR_HELLO, new byte[0]);
                if (pending != null) pending.sentAt()[0] = 0; // Minecraft may have lost it: send it again with the next state
            }
            String json = inbox.getAndSet(null);
            if (json != null) { readState(json); freshState = true; }
            if (mcCounts == null) { status = "waiting for Minecraft's inventory"; return; }
            long now = System.currentTimeMillis();
            if (freshState || now - lastScan >= SCAN_MS) {
                lastScan = now;
                sync(player, now);
                freshState = false;
            }
        } catch (RuntimeException e) {
            failures++;
            status = "failed: " + e;
            last = e.toString();
            Log.error("inventory mirror failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void readState(String json) {
        Map<String, Object> m = JsonLite.object(json);
        Map<String, Map<String, Object>> es = new LinkedHashMap<>();
        Map<String, Integer> cs = new LinkedHashMap<>();
        for (Object o : (List<Object>) m.getOrDefault("groups", List.of())) {
            Map<String, Object> e = (Map<String, Object>) o;
            String key = (String) e.get("key");
            es.put(key, e);
            cs.put(key, ((Number) e.get("n")).intValue());
            if (e.get("b") instanceof String b) blobCache.put(key, b);
        }
        Set<Long> a = new HashSet<>();
        for (Object o : (List<Object>) m.getOrDefault("acks", List.of())) a.add(((Number) o).longValue());
        Set<String> rej = new HashSet<>();
        for (Object o : (List<Object>) m.getOrDefault("rej", List.of())) rej.add(String.valueOf(o));
        entries = es;
        mcCounts = cs;
        acks = a;
        rejectedByMc = rej;
        statesApplied++;
    }

    // ---- the pockets ----

    /** What the pockets hold, grouped by key. */
    private static final class Scan {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, List<InventoryItem>> items = new LinkedHashMap<>();
        final Map<String, String> sig = new HashMap<>();
    }

    private static Scan scan(IsoPlayer player) {
        Scan s = new Scan();
        for (InventoryItem item : new ArrayList<>(player.getInventory().getItems())) {
            if (item == null) continue;
            String key;
            int n = 1;
            if (WRAPPER.equals(item.getFullType())) {
                KahluaTable t = standIn(item);
                if (t == null) continue;
                key = String.valueOf(t.rawget("key"));
                n = Math.max(0, ((Number) t.rawget("n")).intValue());
            } else {
                boolean blob = ContainerBridge.needsBlob(item);
                key = keyOf(item, blob);
                if (localOnly.contains(key)) continue; // Minecraft turned it down: it is only in the pockets
                if (blob) s.sig.put(key, sig(item, 0));
            }
            s.counts.merge(key, n, Integer::sum);
            s.items.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return s;
    }

    private static KahluaTable standIn(InventoryItem item) {
        return item.hasModData() && item.getModData().rawget(STAND_IN) instanceof KahluaTable t ? t : null;
    }

    /** The key of a PZ item; must give what the Minecraft side computes from the stack it made of the same item. */
    private static String keyOf(InventoryItem item, boolean blob) {
        Integer c = item.getConditionMax() > 0 && item.getCondition() != item.getConditionMax() ? item.getCondition() : null;
        // rounded exactly as ContainerBridge.state does (float * double), so the Minecraft side sees the same text
        Double u = item instanceof DrainableComboItem d && d.getCurrentUsesFloat() < 0.999f ? Double.valueOf(Math.round(d.getCurrentUsesFloat() * 1000.0) / 1000.0) : null;
        return Mirror.key(item.getFullType(), c, item.isCooked(), u, item.isCustomName() ? item.getDisplayName() : null, blob ? item.getID() : null);
    }

    /** A cheap signature of what is inside a stateful item (condition, uses, fluid, name, what a bag holds): not age, which drifts by itself. */
    private static String sig(InventoryItem item, int depth) {
        StringBuilder b = new StringBuilder();
        b.append(item.getFullType()).append('#').append(item.getID()).append(':').append(item.getCondition());
        if (item instanceof DrainableComboItem d) b.append(":u").append(Math.round(d.getCurrentUsesFloat() * 1000));
        if (item.getMaxAmmo() > 0) b.append(":r").append(item.getCurrentAmmoCount());   // rounds in a magazine or gun
        if (item instanceof zombie.inventory.types.HandWeapon w && w.isRanged())
            b.append(w.isRoundChambered() ? "c" : "-").append(w.isContainsClip() ? "m" : "-").append(w.isJammed() ? "j" : "-");
        var fluid = item.getFluidContainer();
        if (fluid != null) b.append(":f").append(Math.round(fluid.getAmount() * 1000));
        if (item.isCustomName()) b.append(":n").append(item.getDisplayName());
        if (item.isCooked()) b.append(":k");
        if (item instanceof InventoryContainer bag && bag.getInventory() != null && depth < 4) {
            List<String> inner = new ArrayList<>();
            for (InventoryItem c : bag.getInventory().getItems()) if (c != null) inner.add(sig(c, depth + 1));
            Collections.sort(inner);
            b.append('[').append(String.join(",", inner)).append(']');
        }
        if (depth > 0) return b.toString();
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < b.length(); i++) { h ^= b.charAt(i); h *= 0x100000001b3L; }
        return Long.toHexString(h) + "." + b.length();
    }

    // ---- one round: acknowledgement, what PZ changed, the transaction, then the pockets ----

    @SuppressWarnings("unchecked")
    private static void sync(IsoPlayer player, long now) {
        // Minecraft applied the transaction in flight: its state holds those changes now, except what it turned down (that
        // stays in the pockets only, and must be left out of the scan below or it would look like something PZ removed)
        boolean settled = pending != null && acks.contains(pending.id());
        Map<String, Integer> settledDelta = Map.of();
        if (settled) {
            for (String k : pending.delta().keySet()) if (rejectedByMc.contains(k)) localOnly.add(k);
            settledDelta = new LinkedHashMap<>(pending.delta());
            settledDelta.keySet().removeAll(localOnly);
        }
        Scan p = scan(player);
        if (assumeLedger) { // mirror stand-ins exist but the ledger is gone: Minecraft wins, loudly
            ledger = new LinkedHashMap<>(p.counts);
            assumeLedger = false;
            Log.info("inventory mirror: ledger missing, assuming the pockets are in step with Minecraft");
        }
        Map<String, Integer> change = Mirror.delta(p.counts, ledger);           // pockets - ledger
        if (settled) {
            change = Mirror.delta(change, settledDelta);                        // (minus the part just settled)
            for (Map.Entry<String, String> e : pending.sigs().entrySet()) sigs.put(e.getKey(), e.getValue());
            txAcked++;
            pending = null;
            dirty = true;
        }
        Map<String, Integer> target = Mirror.apply(mcCounts, change);
        // in-place changes of stateful items (a bag's contents, a bottle's fluid, condition)
        Map<String, String> repSigs = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.sig.entrySet()) {
            String known = sigs.get(e.getKey());
            if (known == null) { sigs.put(e.getKey(), e.getValue()); dirty = true; }
            else if (!known.equals(e.getValue()) && (pending == null || !e.getValue().equals(pending.sigs().get(e.getKey())))) repSigs.put(e.getKey(), e.getValue());
        }
        if (pending == null && (!change.isEmpty() || !repSigs.isEmpty())) sendTx(p, change, repSigs, now);
        else if (pending != null && now - pending.sentAt()[0] >= RESEND_MS) resend(now);

        int changesBefore = created + removed;
        List<String[]> made = conform(player, p, target);
        if (now - breakerStart > BREAKER_WINDOW_MS) { breakerStart = now; breakerChanges = 0; }
        breakerChanges += created + removed - changesBefore;
        if (breakerChanges > BREAKER_CHANGES) {
            tripped = true;
            status = "STOPPED: " + breakerChanges + " items made or dropped within " + BREAKER_WINDOW_MS / 1000 + " s";
            last = status;
            failures++;
            Log.info("ERROR inventory mirror stopped itself: " + status + " (transaction in flight: " + (pending == null ? "none" : pending.id()) + ")");
            return;
        }

        // The ledger is the part of Minecraft's state the pockets hold: what they hold now minus what PZ itself changed. It is
        // taken from a fresh look at the pockets, never from what conform meant to do: where an item could not be made (its bytes
        // have not arrived yet, PZ has no such type, PZ refused a second item with the same id) it is simply absent from the
        // ledger, so that it never looks like something PZ removed. An item PZ made for one of Minecraft's keys but that has a
        // key of its own (a plain /pzgive stack becomes a whole item with its PZ id) stays in the ledger under Minecraft's key:
        // the next round then sees one item changing in place and swaps it, instead of dropping it and making it again.
        Map<String, Integer> held = new LinkedHashMap<>(scan(player).counts);
        for (String[] m : made) {
            if (m[0].equals(m[1])) continue;
            held.merge(m[1], -1, Integer::sum);
            held.merge(m[0], 1, Integer::sum);
        }
        held.values().removeIf(n -> n <= 0);
        Map<String, Integer> l = Mirror.apply(held, negate(change));
        if (!l.equals(ledger)) { ledger = l; dirty = true; }
        sigs.keySet().retainAll(union(p.sig.keySet(), ledger.keySet()));
        if (dirty) { save(player); dirty = false; }
        status = pending != null ? "syncing (transaction " + pending.id() + " in flight)" : "in step";
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        return u;
    }

    private static void sendTx(Scan p, Map<String, Integer> change, Map<String, String> repSigs, long now) {
        long id = 1 + ThreadLocalRandom.current().nextLong(1L << 50);
        List<Object> add = new ArrayList<>(), rem = new ArrayList<>(), rep = new ArrayList<>();
        int budget = PAYLOAD_BUDGET;
        Map<String, Integer> sent = new LinkedHashMap<>();
        Map<String, String> sentSigs = new LinkedHashMap<>();
        // A stateful item whose key changed (its condition or name, or a stack /pzgive made that now has its PZ item id) is one
        // item changing in place, not one leaving and another arriving: Minecraft swaps it in its own slot, so nothing moves.
        Map<String, Integer> rest = new LinkedHashMap<>(change);
        for (Map.Entry<String, Integer> e : change.entrySet()) {
            if (e.getValue() != -1 || Mirror.isMc(e.getKey()) || !rest.containsKey(e.getKey())) continue;
            String[] old = e.getKey().split("\\|", -1);
            for (Map.Entry<String, Integer> f : change.entrySet()) {
                if (f.getValue() != 1 || Mirror.isMc(f.getKey()) || !rest.containsKey(f.getKey())) continue;
                String[] cur = f.getKey().split("\\|", -1);
                if (cur.length != 6 || old.length != 6 || cur[5].isEmpty() || !cur[0].equals(old[0]) || !(old[5].isEmpty() || old[5].equals(cur[5]))) continue;
                Map<String, Object> entry = entryFor(p, f.getKey(), 1);
                if (entry == null) continue;
                int size = 160 + (entry.get("b") instanceof String b ? b.length() : 0);
                if (size > budget) continue;
                budget -= size;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", e.getKey());
                r.put("e", entry);
                rep.add(r);
                sent.put(e.getKey(), -1);
                sent.put(f.getKey(), 1);
                if (p.sig.containsKey(f.getKey())) sentSigs.put(f.getKey(), p.sig.get(f.getKey()));
                if (entry.get("b") instanceof String b) blobCache.put(f.getKey(), b);
                rest.remove(e.getKey());
                rest.remove(f.getKey());
                break;
            }
        }
        for (Map.Entry<String, Integer> e : rest.entrySet()) {
            String key = e.getKey();
            int d = e.getValue();
            if (d < 0) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("key", key);
                r.put("n", -d);
                rem.add(r);
                sent.put(key, d);
                continue;
            }
            Map<String, Object> entry = entryFor(p, key, d);
            if (entry == null) continue;
            int size = 160 + (entry.get("b") instanceof String b ? b.length() : 0) + (entry.get("mc") instanceof String s ? s.length() : 0);
            if (size > budget) { last = "too large to mirror: " + key; continue; } // stays in the pockets only
            budget -= size;
            add.add(entry);
            sent.put(key, d);
            if (entry.get("b") instanceof String b) blobCache.put(key, b);
        }
        for (Map.Entry<String, String> e : repSigs.entrySet()) {
            Map<String, Object> entry = entryFor(p, e.getKey(), 1);
            if (entry == null) continue;
            int size = 160 + (entry.get("b") instanceof String b ? b.length() : 0);
            if (size > budget) continue;
            budget -= size;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", e.getKey());
            r.put("e", entry);
            rep.add(r);
            sentSigs.put(e.getKey(), e.getValue());
            if (entry.get("b") instanceof String b) blobCache.put(e.getKey(), b);
        }
        if (add.isEmpty() && rem.isEmpty() && rep.isEmpty()) return;
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", id);
        tx.put("now", GameTime.getInstance().getWorldAgeHours() / 24.0); // so food that leaves PZ here keeps spoiling
        tx.put("add", add);
        tx.put("rem", rem);
        tx.put("rep", rep);
        String json = JsonLite.write(tx);
        pending = new Pending(id, json, sent, sentSigs, new long[] {now});
        dirty = true;
        if (LinkService.send(Wire.MSG_MIRROR_TX, json.getBytes(StandardCharsets.UTF_8))) txSent++;
        last = "sent transaction " + id + ": " + add.size() + " added, " + rem.size() + " removed, " + rep.size() + " changed";
        Log.info("inventory mirror: " + last + " " + sent + (sentSigs.isEmpty() ? "" : " in place: " + sentSigs.keySet()));
    }

    private static void resend(long now) {
        pending.sentAt()[0] = now;
        if (LinkService.send(Wire.MSG_MIRROR_TX, pending.json().getBytes(StandardCharsets.UTF_8))) txSent++;
    }

    /** The entry that creates {@code n} of this key on the Minecraft side; null if the pockets no longer have it. */
    private static Map<String, Object> entryFor(Scan p, String key, int n) {
        List<InventoryItem> list = p.items.get(key);
        if (list == null || list.isEmpty()) return null;
        InventoryItem item = list.get(0);
        Map<String, Object> e = new LinkedHashMap<>();
        if (Mirror.isMc(key)) {
            KahluaTable t = standIn(item);
            if (t == null) return null;
            e.put("key", key);
            e.put("t", "mc");
            e.put("n", n);
            e.put("mc", String.valueOf(t.rawget("snbt")));
            return e;
        }
        e.putAll(ContainerBridge.state(item)); // condition, cooked, uses, name, and the whole item's bytes where it has them
        e.put("key", key);
        e.put("t", item.getFullType());
        e.put("n", n);
        return e; // its age travels along (food keeps spoiling), but is not part of the key
    }

    // ---- making the pockets equal the target ----

    private static Map<String, Integer> negate(Map<String, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) out.put(e.getKey(), -e.getValue());
        return out;
    }

    /**
     * Makes the pockets hold {@code target}. A kind that cannot be made stays short; the caller looks at the pockets again.
     * Returns, for each item made, Minecraft's key it was made for and its own key (these differ for a stack that had no PZ id).
     */
    private static List<String[]> conform(IsoPlayer player, Scan p, Map<String, Integer> target) {
        List<String[]> made = new ArrayList<>();
        ItemContainer inv = player.getInventory();
        double days = GameTime.getInstance().getWorldAgeHours() / 24.0;
        refused.removeIf(k -> mcCounts.getOrDefault(k, 0) <= p.counts.getOrDefault(k, 0)); // Minecraft no longer asks for the extra copy
        for (String key : union(p.counts.keySet(), target.keySet())) {
            int have = p.counts.getOrDefault(key, 0), need = target.getOrDefault(key, 0);
            if (Mirror.isMc(key)) { conformStandIn(inv, p, key, have, need); continue; }
            if (need < have) {
                List<InventoryItem> list = p.items.get(key);
                for (int i = 0; i < have - need && list != null && !list.isEmpty(); i++) drop(player, inv, list.remove(list.size() - 1));
            } else if (need > have && !unmirrorable.contains(key) && !refused.contains(key)) {
                Map<String, Object> entry = entries.get(key);
                if (entry == null) continue;
                Map<String, Object> make = new LinkedHashMap<>(entry);
                if (!make.containsKey("b") && blobCache.containsKey(key)) make.put("b", blobCache.get(key));
                if (make.containsKey("i") && !(make.get("b") instanceof String)) { helloWanted = true; continue; } // bytes not here yet: ask again
                for (int i = have; i < need; i++) {
                    InventoryItem item = ContainerBridge.create(make, days);
                    if (item == null) { unmirrorable.add(key); last = "PZ has no item type for " + entry.get("t"); Log.info("inventory mirror: " + last); break; }
                    inv.AddItem(item);
                    if (!inv.getItems().contains(item)) { // PZ cannot hold two items with the same id: the extra copy stays in Minecraft only
                        refused.add(key);
                        last = "PZ refused a second " + entry.get("t") + " with the same item id";
                        Log.info("inventory mirror: " + last + " (" + key + ")");
                        break;
                    }
                    created++;
                    boolean blob = ContainerBridge.needsBlob(item);
                    String own = keyOf(item, blob);
                    made.add(new String[] {key, own});
                    if (blob) sigs.put(own, sig(item, 0));
                    dirty = true;
                }
            }
        }
        return made;
    }

    private static void drop(IsoPlayer player, ItemContainer inv, InventoryItem item) {
        if (item.isEquipped()) player.removeFromHands(item);
        if (player.isEquippedClothing(item)) player.removeWornItem(item);
        inv.Remove(item);
        removed++;
        dirty = true;
    }

    /** A Minecraft-only stack is one stand-in item per key, with the count in its mod data. */
    private static int conformStandIn(ItemContainer inv, Scan p, String key, int have, int need) {
        List<InventoryItem> list = p.items.getOrDefault(key, List.of());
        if (need == 0) {
            for (InventoryItem w : list) { inv.Remove(w); removed++; }
            dirty = true;
            return 0;
        }
        InventoryItem keep = list.isEmpty() ? null : list.get(0);
        Map<String, Object> entry = entries.get(key);
        if (keep == null) {
            if (entry == null || unmirrorable.contains(key)) return 0;
            keep = InventoryItemFactory.CreateItem(WRAPPER);
            if (keep == null) { unmirrorable.add(key); last = "PZ has no " + WRAPPER + " (is the script installed?)"; return 0; }
            KahluaTable t = LuaManager.platform.newTable();
            t.rawset("key", key);
            t.rawset("snbt", String.valueOf(entry.get("mc")));
            t.rawset("label", String.valueOf(entry.getOrDefault("label", "Minecraft item")));
            keep.getModData().rawset(STAND_IN, t);
            inv.AddItem(keep);
            created++;
        } else if (have == need && list.size() == 1) {
            return need;
        }
        KahluaTable t = standIn(keep);
        t.rawset("n", (double) need);
        keep.setName(t.rawget("label") + (need > 1 ? " x" + need : ""));
        keep.setCustomName(true);
        for (int i = 1; i < list.size(); i++) { inv.Remove(list.get(i)); removed++; } // one stand-in per key
        replaced++;
        dirty = true;
        return need;
    }

    // ---- saving with the player ----

    @SuppressWarnings("unchecked")
    private static void load(IsoPlayer player) {
        loadedFor = player;
        ledger = new LinkedHashMap<>();
        sigs = new HashMap<>();
        pending = null;
        assumeLedger = false;
        if (player.getModData().rawget(DATA) instanceof KahluaTable d) {
            if (d.rawget("ledger") instanceof KahluaTable t) for (var it = t.iterator(); it.advance(); ) ledger.put(String.valueOf(it.getKey()), ((Number) it.getValue()).intValue());
            if (d.rawget("sigs") instanceof KahluaTable t) for (var it = t.iterator(); it.advance(); ) sigs.put(String.valueOf(it.getKey()), String.valueOf(it.getValue()));
            if (d.rawget("pending") instanceof String s && !s.isEmpty()) {
                Map<String, Object> m = JsonLite.object(s);
                Map<String, Integer> delta = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("delta")).entrySet()) delta.put(e.getKey(), ((Number) e.getValue()).intValue());
                Map<String, String> ps = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("sigs")).entrySet()) ps.put(e.getKey(), String.valueOf(e.getValue()));
                pending = new Pending(((Number) m.get("id")).longValue(), (String) m.get("json"), delta, ps, new long[] {0});
            }
            Log.info("inventory mirror: loaded ledger of " + ledger.size() + " kinds" + (pending != null ? " and transaction " + pending.id() : ""));
        } else {
            for (InventoryItem item : player.getInventory().getItems()) if (item != null && WRAPPER.equals(item.getFullType())) assumeLedger = true;
            Log.info("inventory mirror: first use for this character" + (assumeLedger ? " (stand-ins found without a ledger)" : ""));
        }
    }

    private static void save(IsoPlayer player) {
        KahluaTable d = LuaManager.platform.newTable();
        KahluaTable l = LuaManager.platform.newTable(), s = LuaManager.platform.newTable();
        for (Map.Entry<String, Integer> e : ledger.entrySet()) l.rawset(e.getKey(), (double) e.getValue());
        for (Map.Entry<String, String> e : sigs.entrySet()) s.rawset(e.getKey(), e.getValue());
        d.rawset("ledger", l);
        d.rawset("sigs", s);
        if (pending != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pending.id());
            m.put("json", pending.json());
            m.put("delta", pending.delta());
            m.put("sigs", pending.sigs());
            d.rawset("pending", JsonLite.write(m));
        }
        player.getModData().rawset(DATA, d);
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("status", status);
        m.put("ledgerKinds", ledger.size());
        m.put("pending", pending == null ? 0 : pending.id());
        m.put("states", statesApplied);
        m.put("txSent", txSent);
        m.put("txAcked", txAcked);
        m.put("created", created);
        m.put("removed", removed);
        m.put("updated", replaced);
        m.put("unmirrorable", unmirrorable.size());
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}

