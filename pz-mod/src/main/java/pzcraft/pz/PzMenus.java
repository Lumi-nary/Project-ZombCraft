package pzcraft.pz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import zombie.characters.HaloTextHelper;
import zombie.characters.IsoPlayer;
import zombie.input.Mouse;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoObjectPicker;
import zombie.ui.ISUIWrapper.LuaHelpers;
import zombie.ui.UIElementInterface;
import zombie.ui.UIManager;

/**
 * PZ's own right-click menus for Steve, through PzCraft_Menus.lua. G frees the cursor where the crosshair was (Viewpoint
 * puts it in the middle of the screen when it lets go) and, once Viewpoint has picked what lies under it in 3D, opens the
 * menu PZ opens for a right-click there; on nothing PZ knows, or with Shift, the menu of the PZ item in Steve's hand. The
 * cursor stays free while the menu is open, and while a window the chosen entry opened is up (G gives the mouse back to
 * the view at any time); then Viewpoint takes the mouse again. Game thread, except {@link #request}, {@link #requestClose}
 * and {@link #holdsCursor} (window thread).
 */
public final class PzMenus {
    private enum Phase { IDLE, PICKING, OPEN, WINDOW }
    private static final int PICK_FRAMES = 12, GIVE_UP_FRAMES = 60, WINDOW_WIDTH = 150, WINDOW_HEIGHT = 80;

    private static volatile int wanted;               // 0 nothing, 1 the world, 2 the item in hand, 3 a car (vehicleWanted)
    private static volatile zombie.vehicles.BaseVehicle vehicleWanted;
    private static volatile boolean closeWanted, holding;
    private static Phase phase = Phase.IDLE;
    private static boolean itemFirst;
    private static zombie.vehicles.BaseVehicle vehicleFirst;
    private static int frames, freeFrames;
    private static Set<UIElementInterface> before = Set.of();
    private static UIElementInterface window;
    private static volatile int worldMenus, itemMenus, nothing, windows, failures;
    private static volatile String last = "";

    private PzMenus() {}

    /** Window thread: G (Shift+G: the item in hand) while Steve plays and the view has the mouse. */
    static void request(boolean item) {
        wanted = item ? 2 : 1;
        holding = true;   // Viewpoint lets the cursor go on its next mouse update (Patch_GuiCapture)
    }

    /** Game thread: Shift+right-click on a car in Minecraft: PZ's own menu for it, where the cursor is freed (as G does). */
    static void requestVehicle(zombie.vehicles.BaseVehicle vehicle) {
        vehicleWanted = vehicle;
        wanted = 3;
        holding = true;
    }

    /** Window thread: G while the menu or its window has the cursor: back to the view. */
    static void requestClose() { closeWanted = true; }

    /** Public: ZombieBuddy inlines the advice that calls this into Viewpoint's mouse update. */
    public static boolean holdsCursor() { return holding; }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null || !SteveControl.drives(player)) {
            if (phase != Phase.IDLE || holding) finish();
            wanted = 0;
            closeWanted = false;
            return;
        }
        if (closeWanted) {
            closeWanted = false;
            if (phase == Phase.OPEN) call("close");
            finish();
            return;
        }
        int w = wanted;
        if (w != 0 && phase == Phase.IDLE) {
            wanted = 0;
            phase = Phase.PICKING;
            itemFirst = w == 2;
            vehicleFirst = w == 3 ? vehicleWanted : null;
            frames = freeFrames = 0;
            holding = true;
        }
        switch (phase) {
            case PICKING -> pick(player);
            case OPEN -> { if (!Boolean.TRUE.equals(call("isOpen"))) afterMenu(); }
            case WINDOW -> { if (window == null || !Boolean.TRUE.equals(window.isVisible()) || !UIManager.getUI().contains(window)) finish(); }
            default -> {}
        }
    }

    private static void pick(IsoPlayer player) {
        frames++;
        if (ViewpointBridge.captured()) {
            if (frames > GIVE_UP_FRAMES) { last = "Viewpoint kept the mouse"; finish(); }
            return;
        }
        freeFrames++;
        Boolean pickOn = ViewpointBridge.mousePickOn();
        if (pickOn != null && !pickOn && freeFrames < GIVE_UP_FRAMES) return;   // its 3D pick starts a frame after the cursor is free
        if (pickOn == null && freeFrames < 3) return;
        double x = Mouse.getXA(), y = Mouse.getYA();
        if (vehicleFirst != null) {
            zombie.vehicles.BaseVehicle v = vehicleFirst;
            vehicleFirst = null;
            if (open("openVehicle", v, x, y)) {
                worldMenus++;
                last = "car menu for " + (v.getScript() != null ? v.getScript().getName() : "a car");
                return;
            }
            nothing++;
            last = "nothing to do with that car";
            HaloTextHelper.addBadText(player, "Nothing to do there");
            finish();
            return;
        }
        if (!itemFirst) {
            IsoObjectPicker.ClickObject picked = UIManager.getPicked();
            if ((picked == null || picked.tile == null) && freeFrames < PICK_FRAMES) return;   // the pick lands a frame or two later
            if (picked != null && picked.tile != null && open("openWorld", picked.tile, x, y)) {
                worldMenus++;
                last = "world menu for " + picked.tile.getClass().getSimpleName() + " at " + picked.tile.getX() + "," + picked.tile.getY();
                return;
            }
        }
        InventoryItem held = heldItem(player);
        if (held != null && open("openItem", held, x, y)) {
            itemMenus++;
            last = "item menu for " + held.getFullType();
            return;
        }
        nothing++;
        last = itemFirst ? "no PZ item in hand" : "nothing PZ knows there";
        HaloTextHelper.addBadText(player, itemFirst ? "Hold a Zomboid item" : "Nothing to use there");
        finish();
    }

    private static boolean open(String function, Object target, double x, double y) {
        Set<UIElementInterface> ui = Collections.newSetFromMap(new IdentityHashMap<>());
        ui.addAll(UIManager.getUI());
        if (!Boolean.TRUE.equals(call(function, target, x, y))) return false;
        before = ui;
        phase = Phase.OPEN;
        return true;
    }

    /** The menu closed: if its entry opened a window (a map, a radio, a dialog), the cursor stays free for it. */
    private static void afterMenu() {
        for (UIElementInterface e : new ArrayList<>(UIManager.getUI())) {
            if (before.contains(e) || !Boolean.TRUE.equals(e.isVisible())) continue;
            Double width = e.getWidth(), height = e.getHeight();
            if (width != null && height != null && width >= WINDOW_WIDTH && height >= WINDOW_HEIGHT) {
                window = e;
                phase = Phase.WINDOW;
                windows++;
                return;
            }
        }
        finish();
    }

    private static void finish() {
        phase = Phase.IDLE;
        holding = false;
        window = null;
        before = Set.of();
    }

    /** The PZ item Steve holds in Minecraft's main hand: by its PZ id when it has one, else the first of its type. */
    private static InventoryItem heldItem(IsoPlayer player) {
        GuiInput.Held held = GuiInput.held();
        if (held == null) return null;
        InventoryItem byType = null;
        for (InventoryItem item : new ArrayList<>(player.getInventory().getItems())) {
            if (item == null || !held.type().equals(item.getFullType())) continue;
            if (held.id() >= 0 && item.getID() == held.id()) return item;
            if (byType == null) byType = item;
        }
        return byType;
    }

    /** PzCraft_Vehicles.lua (game thread). */
    static Object callVehicles(String function, Object... args) {
        try {
            return LuaHelpers.callLuaClass("PzCraftVehicles", function, null, args);
        } catch (RuntimeException e) {
            failures++;
            if (failures <= 3) Log.error("PzCraftVehicles." + function + " failed", e);
            return null;
        }
    }

    private static Object call(String function, Object... args) {
        try {
            return LuaHelpers.callLuaClass("PzCraftMenus", function, null, args);
        } catch (RuntimeException e) {
            failures++;
            if (failures <= 3) Log.error("PzCraftMenus." + function + " failed", e);
            return null;
        }
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase", phase.name());
        m.put("holding", holding);
        m.put("worldMenus", worldMenus);
        m.put("itemMenus", itemMenus);
        m.put("nothing", nothing);
        m.put("windows", windows);
        m.put("failures", failures);
        m.put("last", last);
        return m;
    }
}
