package pzcraft.pz;

import java.util.LinkedHashMap;
import java.util.Map;
import zombie.characters.IsoPlayer;

/**
 * Minecraft owns Steve's inventory, so while Steve drives PZ's own inventory and loot windows and its sidebar of buttons
 * are hidden (they would show the hidden PZ character's empty pockets next to Minecraft's screen). What was visible comes
 * back when Steve hands the character back.
 */
final class HudBridge {
    private static final String HIDE = """
            local hidden = PzCraftHud or {}
            local inv = getPlayerInventory and getPlayerInventory(0)
            if inv and inv:getIsVisible() then inv:setVisible(false); hidden.inv = true end
            local loot = getPlayerLoot and getPlayerLoot(0)
            if loot and loot:getIsVisible() then loot:setVisible(false); hidden.loot = true end
            local side = ISEquippedItem and ISEquippedItem.instance
            if side and side:getIsVisible() then side:setVisible(false); hidden.side = true end
            PzCraftHud = hidden
            """;
    private static final String SHOW = """
            local hidden = PzCraftHud
            if hidden then
                if hidden.inv and getPlayerInventory and getPlayerInventory(0) then getPlayerInventory(0):setVisible(true) end
                if hidden.loot and getPlayerLoot and getPlayerLoot(0) then getPlayerLoot(0):setVisible(true) end
                if hidden.side and ISEquippedItem and ISEquippedItem.instance then ISEquippedItem.instance:setVisible(true) end
                PzCraftHud = nil
            end
            """;
    private static Object hideChunk, showChunk;
    private static boolean hiding;
    private static int frames, failures;
    private static volatile String status = "idle";

    private HudBridge() {}

    /** Game thread, every frame; the windows are only checked a few times a second. */
    static void tick(IsoPlayer player) {
        if (player == null || failures > 5) return;
        boolean steve = SteveControl.drives(player);
        if (!steve && !hiding) return;
        if (steve && ++frames % 10 != 0) return;
        try {
            if (steve) {
                run(HIDE, true);
                hiding = true;
                status = "hidden";
            } else {
                run(SHOW, false);
                hiding = false;
                frames = 0;
                status = "shown";
            }
        } catch (Exception e) {
            failures++;
            status = "failed: " + e;
            Log.error("HUD bridge failed", e);
        }
    }

    private static void run(String code, boolean hide) throws Exception {
        Object chunk = hide ? hideChunk : showChunk;
        if (chunk == null) {
            chunk = se.krka.kahlua.luaj.compiler.LuaCompiler.loadstring(code, "pzcraft-hud", zombie.Lua.LuaManager.env);
            if (hide) hideChunk = chunk; else showChunk = chunk;
        }
        Object[] result = zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, chunk);
        if (result == null || result.length == 0 || !Boolean.TRUE.equals(result[0])) {
            throw new IllegalStateException("HUD script failed: " + (result != null && result.length > 1 ? result[1] : "no result"));
        }
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hiding", hiding);
        m.put("status", status);
        m.put("failures", failures);
        return m;
    }
}

