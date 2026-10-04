package pzcraft.pz;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.ItemRules;
import pzcraft.protocol.Wire;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.DrainableComboItem;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoWorldInventoryObject;

/**
 * Minecraft opens a PZ container (a fridge, a crate, a shelf, a corpse, the items on the floor of a tile...) as a chest screen. PZ sends what is inside, grouped into
 * stacks of identical items, and when the screen closes Minecraft sends only the difference: what Steve took out and what he
 * put in. PZ removes and creates exactly those items, so everything not shown stays untouched. Item state that matters
 * (condition, freshness, uses left, cooked, a custom name) travels with each stack; an item that carries more than that (a
 * bag with things in it, a drink, clothes with their dirt and patches, a weapon with its parts) travels as the bytes PZ
 * itself saves it as, so it comes back exactly as it left.
 */
final class ContainerBridge {
    private static final ConcurrentLinkedQueue<Integer> opens = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> commits = new ConcurrentLinkedQueue<>();
    private static volatile int opened, committed, itemsTaken, itemsAdded, failures;
    private static volatile String last = "";
    /** The link ring holds 256 KiB; a screen contents stay well inside that. */
    private static final int PAYLOAD_BUDGET = 160_000;

    private ContainerBridge() {}

    /** Link thread. */
    static void receiveOpen(int objectId) { if (opens.size() < 16) opens.add(objectId); }

    static void receiveCommit(String json) { if (commits.size() < 16) commits.add(json); }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null) return;
        Integer id;
        while ((id = opens.poll()) != null) {
            try { open(player, id); } catch (RuntimeException e) { failures++; last = e.toString(); Log.error("container open failed", e); }
        }
        String json;
        while ((json = commits.poll()) != null) {
            try { commit(json); } catch (RuntimeException e) { failures++; last = e.toString(); Log.error("container commit failed", e); }
        }
    }

    // ---- open ----

    /** Something that holds PZ items: a container, or the items lying on a tile. */
    private interface Source {
        String type();
        List<InventoryItem> items();
        void remove(InventoryItem item);
        void add(InventoryItem item);
        void changed();
    }

    private record Boxed(ItemContainer container) implements Source {
        public String type() { return container.getType() == null ? "" : container.getType(); }
        public List<InventoryItem> items() { return container.getItems(); }
        public void remove(InventoryItem item) { container.Remove(item); }
        public void add(InventoryItem item) { container.AddItem(item); }
        public void changed() { container.setDirty(true); }
    }

    /** The things lying on the floor of one tile (and on whatever stands on it) are one container of their own. */
    private record Floor(IsoGridSquare square) implements Source {
        public String type() { return "floor"; }
        public List<InventoryItem> items() {
            List<InventoryItem> list = new ArrayList<>();
            for (IsoWorldInventoryObject w : square.getWorldObjects()) if (w != null && w.getItem() != null) list.add(w.getItem());
            return list;
        }
        public void remove(InventoryItem item) {
            for (IsoWorldInventoryObject w : new ArrayList<>(square.getWorldObjects())) {
                if (w.getItem() != item) continue;
                square.transmitRemoveItemFromSquare(w);
                w.removeFromWorld();
                w.removeFromSquare();
                item.setWorldItem(null);
                return;
            }
        }
        public void add(InventoryItem item) {
            float spread = 0.25f + (float) Math.random() * 0.5f;
            square.AddWorldInventoryItem(item, spread, 0.25f + (float) Math.random() * 0.5f, 0f);
        }
        public void changed() { square.invalidateRenderChunkLevel(68L); }
    }

    /** What a fridge, a crate, a corpse or a tile of floor items holds: a fridge has two containers (fridge and freezer), most furniture one. */
    private static List<Source> sourcesOf(Object target) {
        List<Source> list = new ArrayList<>();
        if (target instanceof IsoDeadBody body) {
            if (body.getContainer() != null) list.add(new Boxed(body.getContainer()));
        } else if (target instanceof IsoGridSquare square) {
            list.add(new Floor(square));
        } else if (target instanceof IsoObject object) {
            for (int i = 0; i < object.getContainerCount(); i++) {
                ItemContainer c = object.getContainerByIndex(i);
                if (c != null) list.add(new Boxed(c));
            }
        }
        return list;
    }

    private static IsoGridSquare squareOf(Object target) {
        if (target instanceof IsoDeadBody body) return body.getSquare();
        if (target instanceof IsoGridSquare square) return square;
        return target instanceof IsoObject object ? object.getSquare() : null;
    }

    private static void open(IsoPlayer player, int id) {
        Object target = InteractionBridge.targetById(id);
        IsoGridSquare sq = target == null ? null : squareOf(target);
        if (sq == null) return;
        if (Math.hypot(sq.x + .5 - player.getX(), sq.y + .5 - player.getY()) > 8 || Math.abs(sq.z - player.getZ()) > 1.5) return;
        List<Source> containers = sourcesOf(target);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("name", titleOf(target, containers));
        message.put("x", sq.x);
        message.put("y", sq.y);
        message.put("z", sq.z);
        List<Object> list = new ArrayList<>();
        int budget = PAYLOAD_BUDGET, skipped = 0;
        for (Source container : containers) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", container.type());
            List<Object> items = new ArrayList<>();
            for (Group g : group(container)) {
                Map<String, Object> entry = new LinkedHashMap<>(g.state);
                entry.put("t", g.type);
                entry.put("n", g.items.size());
                int size = 120 + (g.state.get("b") instanceof String b ? b.length() : 0);
                if (size > budget) { skipped++; continue; } // too much for one screen: it stays in PZ, untouched
                budget -= size;
                items.add(entry);
            }
            c.put("items", items);
            list.add(c);
        }
        message.put("containers", list);
        message.put("skipped", skipped);
        message.put("now", GameTime.getInstance().getWorldAgeHours() / 24.0);
        LinkService.send(Wire.MSG_CONTAINER_CONTENTS, JsonLite.write(message).getBytes(StandardCharsets.UTF_8));
        opened++;
        last = "opened " + titleOf(target, containers) + " with " + containers.size() + " container(s)";
    }

    private static String titleOf(Object target, List<Source> containers) {
        if (target instanceof IsoDeadBody) return "Corpse";
        if (target instanceof IsoGridSquare) return "On the floor";
        String name = target instanceof IsoObject object ? object.getName() : null;
        if (name == null || name.isBlank()) name = containers.isEmpty() || containers.get(0).type().isEmpty() ? "Container" : containers.get(0).type();
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    // ---- stacks of identical items ----

    private static final class Group {
        final String type;
        final Map<String, Object> state;
        final List<InventoryItem> items = new ArrayList<>();
        Group(String type, Map<String, Object> state) { this.type = type; this.state = state; }
    }

    private static List<Group> group(Source container) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (InventoryItem item : container.items()) {
            if (item == null) continue;
            Map<String, Object> state = state(item);
            String key = key(item.getFullType(), state);
            groups.computeIfAbsent(key, k -> new Group(item.getFullType(), state)).items.add(item);
        }
        return new ArrayList<>(groups.values());
    }

    /** The state of an item that is worth keeping when it leaves PZ's world: condition, age, uses left, cooked, custom name. */
    static Map<String, Object> state(InventoryItem item) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (item.getConditionMax() > 0 && item.getCondition() != item.getConditionMax()) s.put("c", item.getCondition());
        if (item instanceof zombie.inventory.types.Food && perishable(item)) s.put("a", round(item.getAge(), 2)); // carried food keeps ageing
        if (item.isCooked()) s.put("k", 1);
        if (item instanceof DrainableComboItem d && d.getCurrentUsesFloat() < 0.999f) s.put("u", round(d.getCurrentUsesFloat(), 3));
        if (item.isCustomName()) s.put("nm", item.getDisplayName()); // the plain name: getName() adds "Worn," and the like
        if (item instanceof zombie.inventory.types.HandWeapon gun && gun.isRanged())
            s.put("g", Map.of("ammo", gun.getCurrentAmmoCount(), "capacity", gun.getMaxAmmo(),
                    "chamber", gun.isRoundChambered(), "clip", gun.isContainsClip(), "jammed", gun.isJammed()));
        if (item.getMaxAmmo() > 0 && !(item instanceof zombie.inventory.types.HandWeapon))   // a magazine: its rounds
            s.put("g", Map.of("ammo", item.getCurrentAmmoCount(), "capacity", item.getMaxAmmo()));
        if (needsBlob(item)) {
            String b = blob(item);
            if (b != null) {
                s.put("b", b);
                s.put("i", item.getID());
            }
        }
        return s;
    }

    static String kind(InventoryItem item) {
        var script = item.getScriptItem();
        String t = script == null ? "" : String.valueOf(script.getItemType()).toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    /** Whether an item holds more than the few numbers {@link #state} lists, so it has to travel as the bytes PZ saves it as. */
    static boolean needsBlob(InventoryItem item) {
        if (item instanceof InventoryContainer bag && bag.getInventory() != null && !bag.getInventory().getItems().isEmpty()) return true;
        var fluid = item.getFluidContainer();
        if (fluid != null && fluid.getAmount() > 0) return true;
        if (hasRealModData(item)) return true;
        if (item.getMaxAmmo() > 0) return true;     // magazines and guns carry their rounds: no two are alike
        return ItemRules.single(kind(item));
    }

    /**
     * Mod data worth carrying the item whole for. PZ itself writes {@code modData.customName} into every item it loads
     * ({@code InventoryItem.load} calls {@code setCustomName}, which stores the key even for false), so after any restart a bare
     * "customName" is on all items: counting it split every stack of identical items into whole one-item stacks (64 rounds
     * became 64 stacks that could not fit into Minecraft's 36 slots).
     */
    static boolean hasRealModData(InventoryItem item) {
        if (!item.hasModData()) return false;
        for (var it = item.getModData().iterator(); it.advance(); )
            if (!"customName".equals(String.valueOf(it.getKey()))) return true;
        return false;
    }

    /** The item as PZ saves it, so that it can be loaded again unchanged. Null if it cannot be saved. */
    static String blob(InventoryItem item) {
        for (int size = 1 << 12; size <= 1 << 21; size <<= 2) {
            ByteBuffer buffer = ByteBuffer.allocate(size);
            try {
                item.saveWithSize(buffer, false);
                buffer.flip();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return Base64.getEncoder().encodeToString(bytes);
            } catch (java.nio.BufferOverflowException e) {
                // a bag with a lot in it: try a bigger buffer
            } catch (IOException | RuntimeException e) {
                Log.error("cannot save " + item.getFullType() + " for Minecraft", e);
                return null;
            }
        }
        return null;
    }

    static InventoryItem fromBlob(String type, String blob) {
        try {
            InventoryItem item = InventoryItem.loadItem(ByteBuffer.wrap(Base64.getDecoder().decode(blob)), IsoWorld.getWorldVersion());
            return item != null && item.getFullType().equals(type) ? item : null; // the save item numbering differs between worlds
        } catch (IOException | RuntimeException e) {
            Log.error("cannot load " + type + " from Minecraft", e);
            return null;
        }
    }

    /** Canned and dried food never rots, so its age is not tracked (and such stacks merge freely). */
    private static boolean perishable(InventoryItem item) {
        var script = item.getScriptItem();
        return script != null && script.getDaysTotallyRotten() < 1_000_000;
    }

    private static double round(float v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    /** Must agree with the Minecraft side's grouping: type plus the rounded state in a fixed order. */
    static String key(String type, Map<String, Object> s) {
        return type + "|" + whole(s.get("c")) + "|" + fixed(s.get("a"), 2) + "|" + (s.containsKey("k") ? "1" : "") + "|"
                + fixed(s.get("u"), 3) + "|" + s.getOrDefault("nm", "") + "|" + s.getOrDefault("i", "");
    }

    private static String whole(Object v) { return v == null ? "" : String.valueOf(((Number) v).intValue()); }

    private static String fixed(Object v, int places) {
        return v == null ? "" : String.format(Locale.ROOT, "%." + places + "f", ((Number) v).doubleValue());
    }

    // ---- commit ----

    @SuppressWarnings("unchecked")
    private static void commit(String json) {
        Map<String, Object> m = JsonLite.object(json);
        Object object = InteractionBridge.targetById(((Number) m.get("id")).intValue());
        if (object == null) { last = "commit for an object that is gone"; return; }
        List<Source> containers = sourcesOf(object);
        double now = GameTime.getInstance().getWorldAgeHours() / 24.0;
        List<Object> deltas = (List<Object>) m.get("containers");
        for (int i = 0; i < deltas.size() && i < containers.size(); i++) {
            Map<String, Object> d = (Map<String, Object>) deltas.get(i);
            Source container = containers.get(i);
            for (Object o : (List<Object>) d.getOrDefault("removed", List.of())) take(container, (Map<String, Object>) o);
            for (Object o : (List<Object>) d.getOrDefault("added", List.of())) put(container, (Map<String, Object>) o, now);
            container.changed();
        }
        committed++;
        last = "committed";
    }

    /**
     * Removes what Steve took. Items are matched by type and the state that cannot drift (condition, cooked, name); of those,
     * the ones whose age and uses left are closest to what was shown go first, because a food's age keeps rising while the
     * screen is open and the shown value would otherwise stop matching.
     */
    private static void take(Source container, Map<String, Object> entry) {
        String type = (String) entry.get("t");
        int n = ((Number) entry.getOrDefault("n", 1)).intValue();
        if (entry.get("i") instanceof Number id) { // an item that travelled whole is found by its number
            for (InventoryItem item : container.items()) {
                if (item != null && item.getID() == id.intValue() && item.getFullType().equals(type)) {
                    container.remove(item);
                    itemsTaken++;
                    return;
                }
            }
        }
        List<InventoryItem> matching = new ArrayList<>();
        for (InventoryItem item : container.items()) {
            if (item == null || !item.getFullType().equals(type)) continue;
            Map<String, Object> s = state(item);
            if (!whole(s.get("c")).equals(whole(entry.get("c")))) continue;
            if (s.containsKey("k") != entry.containsKey("k")) continue;
            if (!String.valueOf(s.getOrDefault("nm", "")).equals(String.valueOf(entry.getOrDefault("nm", "")))) continue;
            matching.add(item);
        }
        double age = entry.containsKey("a") ? ((Number) entry.get("a")).doubleValue() : 0;
        double uses = entry.containsKey("u") ? ((Number) entry.get("u")).doubleValue() : 1;
        matching.sort(java.util.Comparator.comparingDouble(item -> {
            Map<String, Object> s = state(item);
            double a = s.containsKey("a") ? ((Number) s.get("a")).doubleValue() : 0;
            double u = s.containsKey("u") ? ((Number) s.get("u")).doubleValue() : 1;
            return Math.abs(a - age) + Math.abs(u - uses);
        }));
        for (int k = 0; k < n && k < matching.size(); k++) {
            container.remove(matching.get(k));
            itemsTaken++;
        }
    }

    private static void put(Source container, Map<String, Object> entry, double now) {
        int n = ((Number) entry.getOrDefault("n", 1)).intValue();
        for (int k = 0; k < n; k++) {
            InventoryItem item = create(entry, now);
            if (item == null) continue;
            container.add(item);
            itemsAdded++;
        }
    }

    /** One PZ item from a stack entry ({@code t} type, state fields, {@code b} whole-item bytes); null if PZ has no such type. */
    static InventoryItem create(Map<String, Object> entry, double now) {
        String type = (String) entry.get("t");
        InventoryItem item = entry.get("b") instanceof String b ? fromBlob(type, b) : null;
        boolean whole = item != null; // the saved bytes carry all of its state, and its age counts on from that save
        if (item == null) item = InventoryItemFactory.CreateItem(type);
        if (item == null) return null;
        if (!whole) {
            if (entry.containsKey("c")) item.setCondition(((Number) entry.get("c")).intValue());
            if (entry.containsKey("a")) {
                double since = entry.containsKey("d") ? Math.max(0, now - ((Number) entry.get("d")).doubleValue()) : 0; // aged while carried
                item.setAge((float) (((Number) entry.get("a")).doubleValue() + since));
            }
            if (entry.containsKey("k")) item.setCooked(true);
            if (entry.containsKey("u") && item instanceof DrainableComboItem d) d.setCurrentUsesFloat(((Number) entry.get("u")).floatValue());
            if (entry.containsKey("nm")) { item.setName((String) entry.get("nm")); item.setCustomName(true); }
        }
        return item;
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opened", opened);
        m.put("committed", committed);
        m.put("itemsTaken", itemsTaken);
        m.put("itemsAdded", itemsAdded);
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}

