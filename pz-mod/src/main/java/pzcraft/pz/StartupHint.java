package pzcraft.pz;

import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.core.Core;
import zombie.gameStates.IngameState;
import zombie.gameStates.MainScreenState;
import zombie.ui.TextManager;
import zombie.ui.UIFont;
import zombie.ui.UIManager;

/**
 * A small box with what is missing, on PZ's own screens, top centre, in PZ's own fonts. On the main menu it shows only when
 * PzCraft's start screen (StartScreen) is not covering it: Minecraft connected, or playing without Minecraft. In game it
 * says what is missing (Viewpoint, PzCraft's items in this save, Minecraft, this save's Minecraft world still opening); a
 * character made before 0.16.0 (no CharacterType) is also told to press O until the 3D view has been on once, and a Zomboid
 * survivor hears nothing about Minecraft. On a multiplayer server a short notice that multiplayer is a test comes first.
 * The main-menu box also names the project and where the original lives.
 */
public final class StartupHint {
    private static final float[] WAIT = {1f, .78f, .25f}, GO = {.45f, .95f, .45f};
    private static final double BACKDROP = .65;
    private static final long MP_NOTICE_MS = 20_000;
    private static final String CREDIT = "Project-ZombCraft by Lumi-nary  -  github.com/Lumi-nary/Project-ZombCraft";
    private static boolean viewSeen;
    private static long mpNoticeAt;
    private static boolean failed;

    private StartupHint() {}

    /** PZ's render thread, after UIManager.render(). Never throws into PZ. */
    public static void render() {
        if (failed) return;
        try {
            Object state = GameWindow.states == null ? null : GameWindow.states.current;
            boolean minecraft = LinkService.connected();
            if (state instanceof MainScreenState) {
                if (!mainMenuShown() || StartScreen.INSTANCE.covers()) return;   // a screen opened from it, or PzCraft's own
                if (minecraft) drawCredited(GO, CREDIT, "Project-ZombCraft: Minecraft is connected", "Now create or load a world.");
                else drawCredited(WAIT, CREDIT, "Project-ZombCraft: playing without Minecraft",
                        "Only a Zomboid survivor can be made. To play Steve, start Minecraft with the Project-ZombCraft mod.");
            } else if (state instanceof IngameState) {
                zombie.characters.IsoPlayer player = zombie.characters.IsoPlayer.getInstance();
                String type = CharacterType.of(player);
                if (ViewpointBridge.viewEnabled()) viewSeen = true;
                // multiplayer (step M1: Steve moves with the others; the rest comes in later steps): a short notice, then the same
                // hints as single player, so a player whose Minecraft never joins the server's is told so
                boolean mp = Multiplayer.client();
                if (mp && mpNoticeAt == 0) mpNoticeAt = System.currentTimeMillis();
                if (mp && System.currentTimeMillis() - mpNoticeAt < MP_NOTICE_MS)
                    draw(WAIT, "Project-ZombCraft multiplayer is a test", "Playing as Steve together works. Zombies, digging and items come in later steps.");
                else if (!ViewpointBridge.available()) draw(WAIT, "Project-ZombCraft needs the Viewpoint mod", "Enable Viewpoint in the Mods menu and load the world again.");
                else if (ChoiceScreen.SURVIVOR.equals(type)) {
                    // a Zomboid survivor: Minecraft has nothing to do with it
                } else if (!MirrorBridge.scriptLoaded()) {
                    if (mp) draw(WAIT, "Project-ZombCraft is not enabled on this server", "Its items are not loaded here. The server's host has to add Project-ZombCraft to its mods.");
                    else draw(WAIT, "Project-ZombCraft is not enabled for this save", "Its items are not loaded, so Minecraft's inventory is not mirrored here.",
                            "Quit to the menu, add Project-ZombCraft to this save's mods, and load it again.");
                } else if (!minecraft) draw(WAIT, "Project-ZombCraft: Minecraft is not running",
                        type != null ? "Start Minecraft with the Project-ZombCraft mod: Steve comes back when it is ready." : "Start Minecraft with the Project-ZombCraft mod, then press O.");
                else if (!WorldIdentity.minecraftHasThisSave())
                    draw(WAIT, mp ? "Project-ZombCraft: Minecraft is joining this server's Minecraft" : "Project-ZombCraft: Minecraft is opening this save's world",
                            type != null ? "Steve comes in a moment." : "Wait a moment, then press O.");
                else if (!viewSeen && type == null) draw(GO, "Project-ZombCraft: press O to play as Steve");
            }
        } catch (Throwable t) {
            failed = true;
            Log.error("startup hint failed; hidden for the rest of this session", t);
        }
    }

    /**
     * PZ's main menu itself is showing (its list of buttons), not a screen opened from it. Read from the Lua MainScreen's
     * bottom panel; when that cannot be found (another PZ version), the box shows as it always did.
     */
    static boolean mainMenuShown() {
        Object screen = zombie.Lua.LuaManager.env == null ? null : zombie.Lua.LuaManager.env.rawget("MainScreen");
        Object instance = screen instanceof KahluaTable t ? t.rawget("instance") : null;
        Object panel = instance instanceof KahluaTable t ? t.rawget("bottomPanel") : null;
        Object element = panel instanceof KahluaTable t ? t.rawget("javaObject") : null;
        return !(element instanceof zombie.ui.UIElement e) || Boolean.TRUE.equals(e.isVisible());
    }

    /** A title in the given colour and grey lines under it, on a dark box. */
    private static void draw(float[] colour, String title, String... lines) {
        drawCredited(colour, null, title, lines);
    }

    /** The same, with a small dim footer line (or none when null). */
    private static void drawCredited(float[] colour, String footer, String title, String... lines) {
        TextManager text = TextManager.instance;
        int titleHeight = text.getFontHeight(UIFont.Large), lineHeight = text.getFontHeight(UIFont.Medium), gap = 4, pad = 12;
        int footerHeight = footer == null ? 0 : text.getFontHeight(UIFont.Small) + gap * 2;
        int width = text.MeasureStringX(UIFont.Large, title);
        for (String line : lines) width = Math.max(width, text.MeasureStringX(UIFont.Medium, line));
        if (footer != null) width = Math.max(width, text.MeasureStringX(UIFont.Small, footer));
        int height = pad * 2 + titleHeight + lines.length * (lineHeight + gap) + footerHeight;
        double centre = Core.getInstance().getScreenWidth() / 2.0, top = 24;
        UIManager.DrawTexture(UIManager.getBlack(), centre - width / 2.0 - pad, top, width + pad * 2, height, BACKDROP);
        double y = top + pad;
        text.DrawStringCentre(UIFont.Large, centre, y, title, colour[0], colour[1], colour[2], 1);
        y += titleHeight + gap;
        for (String line : lines) {
            text.DrawStringCentre(UIFont.Medium, centre, y, line, .9, .9, .9, 1);
            y += lineHeight + gap;
        }
        if (footer != null) text.DrawStringCentre(UIFont.Small, centre, y + gap, footer, .6, .6, .6, 1);
    }
}
