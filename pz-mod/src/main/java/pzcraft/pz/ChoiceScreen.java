package pzcraft.pz;

import imgui.ImGui;
import pzcraft.protocol.McStatus;

/**
 * Before a new character is made (single player): a Zomboid survivor (PZ's own character creation, played in first person,
 * never Steve) or Steve (no character creation, Minecraft's inventory and rules). PZ's menu asks for it through
 * PzCraft_Start.lua ({@link StartLua}); the answer is taken back there on PZ's next front-end tick.
 */
final class ChoiceScreen implements PzUi.Screen {
    static final ChoiceScreen INSTANCE = new ChoiceScreen();
    static final String SURVIVOR = "pz", STEVE = "mc", BACK = "back";

    private volatile boolean asked;
    private volatile String pick;

    private ChoiceScreen() {}

    void ask() { pick = null; asked = true; }

    /** The player's answer, once; null while there is none. */
    String take() {
        String p = pick;
        if (p != null) pick = null;
        return p;
    }

    /** Steve needs Minecraft at its title screen (or in a world): the player's character is made there. */
    static boolean minecraftReady() {
        McStatus s = LinkService.minecraftStatus();
        return LinkService.connected() && (s == null || !s.loading());
    }

    @Override
    public boolean shown() {
        Object state = zombie.GameWindow.states == null ? null : zombie.GameWindow.states.current;
        return asked && state instanceof zombie.gameStates.MainScreenState;
    }

    @Override
    public void draw(float width, float height) {
        PzUi.beginFullScreen("pzcraft-choice", PzUi.SHADE);
        float u = PzUi.unit(), pad = u * 1.2f, gap = u;
        float cardW = Math.min(width * .34f, u * 19), cardH = u * 13;
        float pw = cardW * 2 + gap + pad * 2, ph = pad * 2 + u * 3.2f + cardH + u * 3;
        float[] at = PzUi.panel(pw, ph);
        float x = at[0] + pad, y = at[1] + pad;
        PzUi.text(PzUi.title(), x, y, PzUi.TEXT, "Who do you play?");
        y += u * 3.2f;
        boolean steveOk = minecraftReady();
        if (card("pzc-survivor", x, y, cardW, cardH, "Zomboid survivor", new String[] {
                "Project Zomboid's own character: profession, traits and looks.",
                "Played in first person, by Project Zomboid's rules.",
                "Never Steve. To play Steve, start a new character."}, "Survivor", true, null)) choose(SURVIVOR);
        if (card("pzc-steve", x + cardW + gap, y, cardW, cardH, "Steve", new String[] {
                "Minecraft's character: Minecraft's inventory, blocks and rules.",
                "No character creation: straight into the world.",
                "Starts with Minecraft's starter kit, nothing of Project Zomboid's."}, "Steve", steveOk,
                steveOk ? null : LinkService.connected() ? "Minecraft is still loading." : "Start Minecraft first.")) choose(STEVE);
        float by = at[1] + ph - pad - u * 1.9f;
        if (PzUi.button("pzc-back", "Back", x, by, u * 7, u * 1.9f, false, true)) choose(BACK);
        ImGui.end();
    }

    private boolean card(String id, float x, float y, float w, float h, String title, String[] lines, String action, boolean enabled,
                         String why) {
        float u = PzUi.unit(), pad = u * .9f;
        ImGui.getWindowDrawList().addRectFilled(x, y, x + w, y + h, PzUi.rgba(32, 35, 42, 255), 10);
        float ty = y + pad;
        ty += PzUi.text(PzUi.title(), x + pad, ty, enabled ? PzUi.TEXT : PzUi.DIM, title) + u * .5f;
        for (String line : lines) ty += PzUi.wrapped(PzUi.body(), x + pad, ty, w - pad * 2, PzUi.DIM, line) + u * .35f;
        if (why != null) PzUi.text(PzUi.body(), x + pad, y + h - pad - u * 4.1f, PzUi.WARN, why);
        return PzUi.button(id, action, x + pad, y + h - pad - u * 1.9f, w - pad * 2, u * 1.9f, true, enabled);
    }

    private void choose(String p) {
        asked = false;
        pick = p;
        if (!BACK.equals(p)) CharacterType.choose(p);
        Log.info("character choice: " + p);
    }
}
