package pzcraft.pz;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldTargets;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.ui.ISUIWrapper.LuaHelpers;

/**
 * PZ furniture in Steve's hands (W2c, W3), through PZ's own moveables code in PzCraft_Moveables.lua: mining a piece picks
 * it up as the item PZ's "Pick up" gives (into the hidden PZ inventory, which the mirror hands to Steve); right-clicking
 * a floor with one places it with PZ's own rules, facing Steve. Game thread, except {@link #receive}.
 */
final class FurnitureBridge {
    private static final ConcurrentLinkedQueue<WorldTargets.Place> requests = new ConcurrentLinkedQueue<>();
    private static volatile int pickedUp, pickFailed, placed, placeRefused, failures;
    private static volatile String last = "";

    private FurnitureBridge() {}

    /** Link thread. */
    static void receive(byte[] payload) {
        if (requests.size() < 16) requests.add(WorldTargets.Place.decode(payload));
    }

    /** PZ's pick-up of this object for the player; false when PZ cannot move it (nothing changed then). */
    static boolean pickUp(IsoPlayer player, IsoGridSquare sq, IsoObject object) {
        Object made = call("pickUp", player, sq, object);
        boolean gone = !sq.getObjects().contains(object);
        if (made instanceof Double n && n >= 0 && gone) {
            pickedUp++;
            last = "picked up " + spriteName(object) + " (" + n.intValue() + " item" + (n.intValue() == 1 ? "" : "s") + ")";
            return true;
        }
        pickFailed++;
        last = "could not pick up " + spriteName(object) + " (" + made + ")";
        Log.info("furniture: " + last);
        return false;
    }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null || !LinkService.connected()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        WorldTargets.Place p;
        while ((p = requests.poll()) != null) {
            boolean ok = false;
            try {
                ok = place(cell, player, p);
            } catch (RuntimeException e) {
                failures++;
                if (failures <= 3) Log.error("placing " + p + " failed", e);
            }
            if (ok) placed++; else placeRefused++;
            LinkService.send(Wire.MSG_FURNITURE_PLACED, new WorldTargets.Placed(p.seq(), ok).encode());
        }
    }

    private static boolean place(IsoCell cell, IsoPlayer player, WorldTargets.Place p) {
        if (Math.hypot(p.x() + .5 - player.getX(), p.y() + .5 - player.getY()) > 7 || Math.abs(p.level() - player.getZ()) > 2) return false;
        IsoGridSquare sq = cell.getGridSquare(p.x(), p.y(), p.level());
        InventoryItem item = player.getInventory().getItemWithID(p.itemId());
        if (sq == null || item == null) {
            last = "nothing to place: " + (sq == null ? "no square" : "item " + p.itemId() + " not in the inventory");
            return false;
        }
        boolean ok = Boolean.TRUE.equals(call("place", player, sq, item, WorldTargets.faceName(p.face())));
        last = (ok ? "placed " : "PZ refused to place ") + item.getDisplayName() + " at " + p.x() + "," + p.y() + "," + p.level()
                + " facing " + WorldTargets.faceName(p.face());
        if (ok) WorldExporter.invalidate(p.x(), p.y());
        return ok;
    }

    static Object call(String function, Object... args) {
        try {
            return LuaHelpers.callLuaClass("PzCraftMoveables", function, null, args);
        } catch (RuntimeException e) {
            failures++;
            if (failures <= 3) Log.error("PzCraftMoveables." + function + " failed", e);
            return null;
        }
    }

    private static String spriteName(IsoObject o) {
        return o.getSprite() == null ? "?" : String.valueOf(o.getSprite().getName());
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pickedUp", pickedUp);
        m.put("pickFailed", pickFailed);
        m.put("placed", placed);
        m.put("placeRefused", placeRefused);
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}
