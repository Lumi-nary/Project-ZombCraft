package pzcraft.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Wire;

/**
 * A PZ container (a fridge, a crate, a shelf...) opens as a vanilla chest screen. PZ sends what is inside as stacks of
 * identical items; each PZ container gets whole rows (a fridge shows its fridge and its freezer one above the other). When
 * the screen closes, only the difference goes back: what Steve took out and what he put in, so anything not shown stays
 * untouched. Only PZ items may be put in; anything else is handed back to Steve.
 */
public final class ContainerBridge {
    private static final int SPARE_SLOTS = 3, MAX_ROWS = 6;
    private static volatile int opened, committed, itemsTaken, itemsAdded, hiddenStacks;
    private static volatile String last = "";

    private ContainerBridge() {}

    // ---- open ----

    /** Link thread: PZ sent the contents of the container Steve right-clicked. */
    static void receive(byte[] payload) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            PzCraftClient.LOG.warn("container contents unreadable: {}", e.toString());
            return;
        }
        server.execute(() -> {
            try {
                open(server, root);
            } catch (RuntimeException e) {
                last = "open failed: " + e;
                PzCraftClient.LOG.error("container open failed", e);
            }
        });
    }

    /** What the screen showed for one PZ container: where its rows are, and the stacks it was filled with. */
    private record Segment(int firstSlot, int slots, Map<String, JsonObject> shown) {}

    private static void open(MinecraftServer server, JsonObject root) {
        if (server.getPlayerList().getPlayers().isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        int objectId = root.get("id").getAsInt();
        double now = root.has("now") ? root.get("now").getAsDouble() : -1;
        JsonArray containers = root.getAsJsonArray("containers");
        int count = Math.min(containers.size(), MAX_ROWS);
        if (count == 0) return;

        // The screen's stacks: each group of identical items is split by the stack size of its Minecraft item.
        List<List<ItemStack>> stacks = new ArrayList<>();
        List<String> types = new ArrayList<>();
        int unknown = 0;
        for (int i = 0; i < count; i++) {
            JsonObject c = containers.get(i).getAsJsonObject();
            types.add(c.has("type") ? c.get("type").getAsString() : "");
            List<ItemStack> list = new ArrayList<>();
            for (JsonElement e : c.getAsJsonArray("items")) {
                JsonObject g = e.getAsJsonObject();
                int n = g.get("n").getAsInt();
                ItemStack proto = PzItems.stack(g.get("t").getAsString(), 1, g, now);
                if (proto.isEmpty()) { unknown++; continue; } // PZ has an item type Minecraft has no entry for
                for (int left = n; left > 0; ) {
                    int take = Math.min(left, Math.max(1, proto.getMaxStackSize()));
                    ItemStack s = proto.copy();
                    s.setCount(take);
                    list.add(s);
                    left -= take;
                }
            }
            stacks.add(list);
        }

        int[] rows = layout(stacks);
        int totalRows = 0;
        for (int r : rows) totalRows += r;
        SimpleContainer container = new SimpleContainer(totalRows * 9);
        List<Segment> segments = new ArrayList<>();
        int hidden = unknown + (root.has("skipped") ? root.get("skipped").getAsInt() : 0), first = 0;
        for (int i = 0; i < count; i++) {
            int slots = rows[i] * 9;
            List<ItemStack> list = stacks.get(i);
            for (int s = 0; s < slots && s < list.size(); s++) container.setItem(first + s, list.get(s));
            hidden += Math.max(0, list.size() - slots);
            segments.add(new Segment(first, slots, tally(container, first, slots, now)));
            first += slots;
        }
        hiddenStacks = hidden;

        String name = root.has("name") ? root.get("name").getAsString() : "Container";
        StringBuilder title = new StringBuilder(name);
        if (count > 1) {
            title.append(" (");
            for (int i = 0; i < count; i++) title.append(i > 0 ? " / " : "").append(types.get(i).isEmpty() ? "container " + (i + 1) : types.get(i));
            title.append(')');
        }
        if (hidden > 0) title.append(" - ").append(hidden).append(" more stacks not shown");

        double tx = root.has("x") ? root.get("x").getAsDouble() + .5 : player.getX();
        double ty = root.has("z") ? Coords.mcY(root.get("z").getAsInt()) + 1 : player.getY();
        double tz = root.has("y") ? root.get("y").getAsDouble() + .5 : player.getZ();
        final int totalRowsFinal = totalRows;
        player.openMenu(new SimpleMenuProvider((containerId, inventory, p) ->
                new PzMenu(containerId, inventory, container, totalRowsFinal, objectId, segments, now, tx, ty, tz), Component.literal(title.toString())));
        opened++;
        last = "opened " + name + " with " + count + " container(s), " + totalRows + " row(s), " + hidden + " hidden";
    }

    /** Whole rows for each PZ container, at most six rows in all (the biggest container gives up rows first). */
    private static int[] layout(List<List<ItemStack>> stacks) {
        int[] rows = new int[stacks.size()];
        int total = 0;
        for (int i = 0; i < rows.length; i++) {
            rows[i] = Math.max(1, (stacks.get(i).size() + SPARE_SLOTS + 8) / 9);
            total += rows[i];
        }
        while (total > MAX_ROWS) {
            int big = 0;
            for (int i = 1; i < rows.length; i++) if (rows[i] > rows[big]) big = i;
            if (rows[big] <= 1) break;
            rows[big]--;
            total--;
        }
        return rows;
    }

    private static MenuType<?> menuType(int rows) {
        return switch (Math.max(1, Math.min(MAX_ROWS, rows))) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /**
     * The kinds of item (type and PZ state) in a run of slots, each with how many there are ({@code n}) and the state PZ
     * needs to find or create it. This is the screen picture of one PZ container, before and after Steve used it.
     */
    private static Map<String, JsonObject> tally(SimpleContainer container, int first, int slots, double openedAt) {
        Map<String, JsonObject> m = new LinkedHashMap<>();
        for (int s = first; s < first + slots; s++) {
            ItemStack stack = container.getItem(s);
            String type = PzItems.typeOf(stack);
            if (type == null) continue;
            JsonObject state = PzItems.stateOf(stack);
            JsonObject entry = m.computeIfAbsent(PzItems.stateKey(type, state), k -> {
                JsonObject o = state.deepCopy();
                o.addProperty("t", type);
                o.addProperty("n", 0);
                return o;
            });
            entry.addProperty("n", entry.get("n").getAsInt() + stack.getCount());
            // Where stacks of one kind left PZ at different times, what Steve put in is the one that did not come from this screen.
            if (state.has("d") && Math.abs(state.get("d").getAsDouble() - openedAt) > 1e-9) entry.addProperty("d", state.get("d").getAsDouble());
        }
        return m;
    }

    // ---- the screen ----

    private static final class PzMenu extends ChestMenu {
        private final int objectId;
        private final List<Segment> segments;
        private final SimpleContainer container;
        private final double openedAt, x, y, z;
        private boolean closed;

        PzMenu(int containerId, Inventory inventory, SimpleContainer container, int rows, int objectId, List<Segment> segments,
               double openedAt, double x, double y, double z) {
            super(menuType(rows), containerId, inventory, container, rows);
            this.objectId = objectId;
            this.segments = segments;
            this.container = container;
            this.openedAt = openedAt;
            this.x = x;
            this.y = y;
            this.z = z;
            // Only PZ items belong in a PZ container: the vanilla grid accepts anything, so swap in slots that do not.
            for (int i = 0; i < container.getContainerSize(); i++) {
                Slot old = this.slots.get(i);
                Slot restricted = new Slot(container, i, old.x, old.y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) { return PzItems.typeOf(stack) != null; }
                };
                restricted.index = old.index;
                this.slots.set(i, restricted);
            }
        }

        @Override
        public boolean stillValid(Player player) {
            return Session.pzConnected && player.distanceToSqr(x, y, z) <= 10 * 10;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (closed) return;
            closed = true;
            try {
                commit(player);
            } catch (RuntimeException e) {
                last = "commit failed: " + e;
                PzCraftClient.LOG.error("container commit failed", e);
            }
        }

        /** What changed in each PZ container since the screen opened, sent to PZ as removed and added stacks. */
        private void commit(Player player) {
            JsonArray deltas = new JsonArray();
            boolean any = false;
            int taken = 0, added = 0;
            for (Segment seg : segments) {
                for (int s = seg.firstSlot(); s < seg.firstSlot() + seg.slots(); s++) {
                    ItemStack stack = container.getItem(s);
                    if (!stack.isEmpty() && PzItems.typeOf(stack) == null) { // not a PZ item: it cannot live in a PZ container
                        container.setItem(s, ItemStack.EMPTY);
                        player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
                    }
                }
                Map<String, JsonObject> now = tally(container, seg.firstSlot(), seg.slots(), openedAt);
                JsonArray removed = new JsonArray(), addedList = new JsonArray();
                for (Map.Entry<String, JsonObject> e : seg.shown().entrySet()) {
                    JsonObject had = e.getValue(), has = now.get(e.getKey());
                    int missing = had.get("n").getAsInt() - (has == null ? 0 : has.get("n").getAsInt());
                    if (missing <= 0) continue;
                    JsonObject entry = had.deepCopy();
                    entry.remove("b"); // PZ finds the item by its number; its saved bytes need not travel back
                    entry.addProperty("n", missing);
                    removed.add(entry);
                    taken += missing;
                }
                for (Map.Entry<String, JsonObject> e : now.entrySet()) {
                    JsonObject had = seg.shown().get(e.getKey());
                    int extra = e.getValue().get("n").getAsInt() - (had == null ? 0 : had.get("n").getAsInt());
                    if (extra <= 0) continue;
                    JsonObject entry = e.getValue().deepCopy();
                    entry.addProperty("n", extra);
                    addedList.add(entry);
                    added += extra;
                }
                JsonObject delta = new JsonObject();
                delta.add("removed", removed);
                delta.add("added", addedList);
                deltas.add(delta);
                any |= removed.size() > 0 || addedList.size() > 0;
            }
            if (!any) { last = "closed without changes"; return; }
            JsonObject message = new JsonObject();
            message.addProperty("id", objectId);
            message.add("containers", deltas);
            Session.send(Wire.MSG_CONTAINER_COMMIT, message.toString().getBytes(StandardCharsets.UTF_8));
            committed++;
            itemsTaken += taken;
            itemsAdded += added;
            last = "committed " + taken + " taken, " + added + " added";
        }
    }

    public static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opened", opened);
        m.put("committed", committed);
        m.put("itemsTaken", itemsTaken);
        m.put("itemsAdded", itemsAdded);
        m.put("hiddenStacks", hiddenStacks);
        m.put("last", last);
        return m;
    }
}

