package pzcraft.pz;

/**
 * While PZ is paused (its pause key or its in-game menu), what Minecraft shows holds still with it: the overlay keeps the
 * last picture it took and the streamed entities and hands keep their last frame, instead of taking new ones. Minecraft
 * itself pauses too, but it keeps rendering and some of its first-person motion (the gun's sway and breathing, flying
 * shells) runs on the wall clock. Not while a Minecraft screen owns the input: that has to keep answering the mouse.
 */
final class PauseFreeze {
    private PauseFreeze() {}

    static boolean active() {
        try {
            return pzPaused() && !GuiInput.ownsInput();
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile boolean paused;

    /** Game thread, once a frame before input is forwarded (Lua's tables are only read on the thread Lua runs on). */
    static void update() {
        paused = !Multiplayer.client() && (zombie.GameTime.isGamePaused() || ingameMenuOpen());   // a server never pauses
    }

    /** PZ is paused: its game speed is 0, or its in-game menu (Esc) is open. Minecraft is told so it pauses with it. */
    static boolean pzPaused() {
        return paused;
    }

    /** PZ's Esc menu is up in game: the Lua MainScreen's panel is visible while the game state is IngameState. */
    static boolean ingameMenuOpen() {
        try {
            if (!(zombie.GameWindow.states != null && zombie.GameWindow.states.current instanceof zombie.gameStates.IngameState)) return false;
            se.krka.kahlua.vm.KahluaTable env = zombie.Lua.LuaManager.env;
            Object screen = env == null ? null : env.rawget("MainScreen");
            Object instance = screen instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("instance") : null;
            Object panel = instance instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("javaObject") : null;
            return panel instanceof zombie.ui.UIElement e && Boolean.TRUE.equals(e.isVisible());
        } catch (Throwable t) {
            return false;
        }
    }
}
