package pzcraft.pz;

import imgui.ImDrawList;
import imgui.ImGui;
import java.nio.file.Path;
import java.util.List;
import pzcraft.protocol.McStatus;

/**
 * PZ's main menu until Minecraft is there. Waiting: the player's Minecraft instances that have our mod, with Play for the
 * ones PZ can start (PZ never starts Minecraft by itself), "I'll start it myself" and "Play without Minecraft".
 * Loading: once Minecraft is up but still loading, a full-screen Mojang Studios loading screen like Minecraft's own, with
 * its real progress and the logo of the player's own Minecraft. Ready: the screen goes; if Viewpoint's setup is not done
 * yet (first person waits for it), it says so once with a button to open it.
 */
final class StartScreen implements PzUi.Screen {
    static final StartScreen INSTANCE = new StartScreen();

    private enum Mode { LIST, BY_HAND }

    private static final int MOJANG_RED = PzUi.rgba(239, 50, 61, 255), WHITE = PzUi.rgba(255, 255, 255, 255);
    private static final long LAUNCH_WAIT_MS = 180_000, SILENT_MINECRAFT_MS = 8_000;
    private static final Path LOGO = Path.of(System.getProperty("user.home"), ".pzcraft", "ui", "mojangstudios.png");

    private volatile List<McInstances.Instance> instances;
    private volatile boolean scanning, dismissed, setupDismissed;
    private volatile McInstances.Instance launched;
    private volatile long launchedAt, connectedAt;
    private Mode mode = Mode.LIST;
    private final int[] logoSize = new int[2];

    private StartScreen() {}

    // ---- when it shows (any thread; reads volatile state) ----

    @Override
    public boolean shown() {
        if (!onMainMenu() || dismissed || ViewpointBridge.setupShown()) return false;
        if (!LinkService.connected()) { connectedAt = 0; return true; }
        if (connectedAt == 0) connectedAt = System.currentTimeMillis();
        McStatus s = LinkService.minecraftStatus();
        if (s == null) return System.currentTimeMillis() - connectedAt < SILENT_MINECRAFT_MS;  // an older Minecraft never says
        return s.loading() || (!setupDismissed && ViewpointBridge.setupBlocks());
    }

    /** True while the screen covers PZ's main menu (the small start-up box then stays away). */
    boolean covers() { return shown(); }

    private static boolean onMainMenu() {
        Object state = zombie.GameWindow.states == null ? null : zombie.GameWindow.states.current;
        return state instanceof zombie.gameStates.MainScreenState && StartupHint.mainMenuShown() && !Multiplayer.active();
    }

    // ---- drawing (the frame's thread) ----

    @Override
    public void draw(float width, float height) {
        if (instances == null && !scanning) scan();
        if (LinkService.connected()) {
            McStatus s = LinkService.minecraftStatus();
            if (s == null || s.loading()) loading(width, height, s == null ? 0f : s.progress());
            else setup(width, height);
        } else {
            waiting(width, height);
        }
    }

    private void loading(float width, float height, float progress) {
        PzUi.beginFullScreen("pzcraft-loading", MOJANG_RED);
        ImDrawList d = ImGui.getWindowDrawList();
        float cx = width / 2, cy = height / 2;
        float logoHeight = Math.min(width * .75f, height) * .25f, half = logoHeight * 2;   // as Minecraft lays it out
        int tex = PzUi.texture(LOGO, logoSize);
        if (tex != 0) {
            // the texture holds the logo's left half above its right half (120x120 of its own units, a 0.0625 overlap)
            d.addImage(tex, cx - half, cy - logoHeight / 2, cx, cy + logoHeight / 2, -0.0625f / 120f, 0f, (120f - 0.0625f) / 120f, .5f, WHITE);
            d.addImage(tex, cx, cy - logoHeight / 2, cx + half, cy + logoHeight / 2, 0.0625f / 120f, .5f, (120f + 0.0625f) / 120f, 1f, WHITE);
        }
        float barY = height * .8325f, barH = Math.max(10f, height * .016f), edge = Math.max(1f, barH / 8f);
        float x0 = cx - half, x1 = cx + half;
        d.addRect(x0, barY - barH / 2, x1, barY + barH / 2, WHITE, 0, 0, edge);
        float inner = edge * 2;
        d.addRectFilled(x0 + inner, barY - barH / 2 + inner, x0 + inner + (x1 - x0 - inner * 2) * progress, barY + barH / 2 - inner, WHITE);
        ImGui.end();
    }

    private void waiting(float width, float height) {
        PzUi.beginFullScreen("pzcraft-start", PzUi.SHADE);
        float u = PzUi.unit(), pw = Math.min(width * .7f, u * 34), pad = u * 1.2f;
        List<McInstances.Instance> list = instances;
        McInstances.Instance starting = launched;
        boolean launching = starting != null && System.currentTimeMillis() - launchedAt < LAUNCH_WAIT_MS;
        int rows = launching || mode == Mode.BY_HAND ? 2 : list == null ? 1 : Math.max(2, Math.min(list.size(), 5));
        boolean setupLine = ViewpointBridge.setupBlocks();
        float ph = pad * 2 + u * 2.2f + u * 2.6f + rows * u * 2.4f + u * 3.4f + (setupLine ? u * 2.6f : 0);
        float[] at = PzUi.panel(pw, ph);
        float x = at[0] + pad, y = at[1] + pad, inner = pw - pad * 2;
        y += PzUi.text(PzUi.title(), x, y, PzUi.TEXT, "Start Minecraft") + u * .6f;
        y += PzUi.wrapped(PzUi.body(), x, y, inner, PzUi.DIM,
                "Project ZombCraft plays Steve with a real Minecraft beside Project Zomboid. It runs in its own window; its loading shows here.") + u;
        if (launching) {
            long secs = (System.currentTimeMillis() - launchedAt) / 1000;
            y += PzUi.text(PzUi.body(), x, y, PzUi.TEXT, "Starting " + starting.label() + "...  " + secs + " s") + u * .5f;
            y += PzUi.wrapped(PzUi.body(), x, y, inner, PzUi.DIM, "If a launcher window asks something (an update, an account), answer it there.") + u * .6f;
            if (PzUi.button("pzc-other", "Choose another", x, y, u * 9, u * 1.9f, false, true)) launched = null;
            y += u * 2.4f;
        } else if (mode == Mode.BY_HAND) {
            y += PzUi.wrapped(PzUi.body(), x, y, inner, PzUi.TEXT,
                    "Waiting for Minecraft. Start your instance that has the Project ZombCraft mod in your launcher.") + u * .6f;
            if (PzUi.button("pzc-list", "Back to the list", x, y, u * 9, u * 1.9f, false, true)) mode = Mode.LIST;
            y += u * 2.4f;
        } else if (list == null) {
            y += PzUi.text(PzUi.body(), x, y, PzUi.DIM, "Looking for your Minecraft instances...") + u * 1.4f;
        } else if (list.isEmpty()) {
            y += PzUi.wrapped(PzUi.body(), x, y, inner, PzUi.WARN,
                    "No Minecraft instance with the Project ZombCraft mod was found. Make one with Fabric Loader, Fabric API, "
                            + "GeckoLib and the mod (Prism Launcher can start it from here), or start yours by hand.") + u * .6f;
            if (PzUi.button("pzc-rescan", "Look again", x, y, u * 9, u * 1.9f, false, true) && !scanning) instances = null;
            y += u * 2.4f;
        } else {
            int shown = 0;
            for (McInstances.Instance i : list) {
                if (shown++ == 5) break;
                PzUi.text(PzUi.body(), x, y + u * .45f, PzUi.TEXT, i.label());
                if (i.launchable()) {
                    if (PzUi.button("pzc-play-" + shown, "Play", x + inner - u * 6, y, u * 6, u * 1.9f, true, true)) start(i);
                } else {
                    String hint = "start it in " + i.launcher().label;
                    ImGui.pushFont(PzUi.body());
                    float tw = ImGui.calcTextSize(hint).x;
                    ImGui.popFont();
                    PzUi.text(PzUi.body(), x + inner - tw, y + u * .45f, PzUi.DIM, hint);
                }
                y += u * 2.4f;
            }
        }
        if (setupLine) {
            PzUi.text(PzUi.body(), x, y + u * .45f, PzUi.WARN, "Viewpoint's setup isn't finished: first person waits for it.");
            if (PzUi.button("pzc-setup", "Viewpoint setup", x + inner - u * 9, y, u * 9, u * 1.9f, false, true)) ViewpointBridge.openSetup();
            y += u * 2.6f;
        }
        float by = at[1] + ph - pad - u * 1.9f, bw = (inner - u) / 2;
        if (mode == Mode.LIST && !launching
                && PzUi.button("pzc-hand", "I'll start it myself", x, by, bw, u * 1.9f, false, true)) mode = Mode.BY_HAND;
        if (PzUi.button("pzc-skip", "Play without Minecraft", x + inner - bw, by, bw, u * 1.9f, false, true)) {
            dismissed = true;
            Log.info("start screen: playing without Minecraft (only Zomboid survivors can be made)");
        }
        ImGui.end();
    }

    /** Minecraft is ready but Viewpoint's setup is not: first person (and so both kinds of character) waits for it. */
    private void setup(float width, float height) {
        PzUi.beginFullScreen("pzcraft-setup", PzUi.SHADE);
        float u = PzUi.unit(), pw = Math.min(width * .7f, u * 30), pad = u * 1.2f, ph = pad * 2 + u * 9;
        float[] at = PzUi.panel(pw, ph);
        float x = at[0] + pad, y = at[1] + pad, inner = pw - pad * 2;
        y += PzUi.text(PzUi.title(), x, y, PzUi.TEXT, "Minecraft is ready") + u * .6f;
        PzUi.wrapped(PzUi.body(), x, y, inner, PzUi.DIM,
                "Viewpoint's setup isn't finished yet, and first person waits for it. Do it now, before you start a game.");
        float by = at[1] + ph - pad - u * 1.9f, bw = (inner - u) / 2;
        if (PzUi.button("pzc-setup2", "Viewpoint setup", x, by, bw, u * 1.9f, true, true)) ViewpointBridge.openSetup();
        if (PzUi.button("pzc-later", "Later", x + inner - bw, by, bw, u * 1.9f, false, true)) setupDismissed = true;
        ImGui.end();
    }

    // ---- starting Minecraft ----

    private void start(McInstances.Instance i) {
        launched = i;
        launchedAt = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                McInstances.launch(i);
            } catch (Exception e) {
                launched = null;
                Log.error("could not start " + i.label(), e);
            }
        }, "pzcraft-start-minecraft");
        t.setDaemon(true);
        t.start();
    }

    private void scan() {
        scanning = true;
        Thread t = new Thread(() -> {
            try {
                List<McInstances.Instance> found = McInstances.find();
                instances = found;
                Log.info("Minecraft instances with PzCraft: " + McInstances.describe(found));
            } catch (Throwable e) {
                instances = List.of();
                Log.error("could not look for Minecraft instances", e);
            } finally {
                scanning = false;
            }
        }, "pzcraft-find-minecraft");
        t.setDaemon(true);
        t.start();
    }
}
