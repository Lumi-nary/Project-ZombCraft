package pzcraft.pz;

import org.lwjgl.glfw.GLFW;
import org.lwjglx.opengl.Display;
import pzcraft.protocol.Coords;
import pzcraft.protocol.InputState;

/**
 * Reads the raw keyboard and Viewpoint's look angles once per logic frame and hands them to Minecraft, which turns
 * them into Steve's movement. Keys are read straight from GLFW (Viewpoint does the same), so PZ's own key bindings do
 * not matter. Input is only live while Viewpoint has the mouse captured and the window is focused.
 */
final class InputForwarder {
    // Minecraft's default bindings.
    private static final int[][] KEYMAP = {
            {GLFW.GLFW_KEY_W, InputState.FWD},
            {GLFW.GLFW_KEY_S, InputState.BACK},
            {GLFW.GLFW_KEY_A, InputState.LEFT},
            {GLFW.GLFW_KEY_D, InputState.RIGHT},
            {GLFW.GLFW_KEY_SPACE, InputState.JUMP},
            {GLFW.GLFW_KEY_LEFT_SHIFT, InputState.SNEAK},
            {GLFW.GLFW_KEY_LEFT_CONTROL, InputState.SPRINT},
            {GLFW.GLFW_KEY_Q, InputState.DROP},
    };
    // Mouse: left = attack/destroy, right = use/place.
    private static final int[][] MOUSEMAP = {
            {GLFW.GLFW_MOUSE_BUTTON_LEFT, InputState.ATTACK},
            {GLFW.GLFW_MOUSE_BUTTON_RIGHT, InputState.USE},
    };
    private static int wheelTotal;
    /** Latched hotbar choice and press counters (see InputState): taps between Minecraft ticks are never lost. */
    private static int hotbarChoice = -1, hotbarSeq, attackPresses, usePresses, dropPresses, jumpPresses;
    private static int prevButtons, prevDigit = -1;

    private static final InputState state = new InputState();
    private static long frame;
    // What the last frame saw, for the periodic log line.
    private static volatile boolean dbgView, dbgCaptured, dbgFocused;
    private static volatile int dbgKeys;
    private static volatile float dbgYaw;

    static String statusText() {
        return "3D=" + dbgView + " captured=" + dbgCaptured + " live=" + dbgFocused + " keys=0x" + Integer.toHexString(dbgKeys)
                + String.format(" yaw=%.1f", dbgYaw);
    }

    static java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("view3d", dbgView); m.put("captured", dbgCaptured); m.put("live", dbgFocused);
        m.put("keys", dbgKeys); m.put("yawDeg", dbgYaw);
        return m;
    }

    private InputForwarder() {}

    static void forward() {
        if (!ViewpointBridge.available()) return;
        long window = Display.getWindow();
        boolean view = ViewpointBridge.viewEnabled();
        boolean captured = ViewpointBridge.captured();
        // PZ's pause freezes its player update (which is where the puppet moves), so Steve must stand still with it.
        // Viewpoint's `captured` already means "the window is focused and the mouse is grabbed", checked on the window's
        // own thread. glfwGetWindowAttrib(FOCUSED) must NOT be used here: Win32 tracks the active window per thread, so
        // from PZ's logic thread it always reads false.
        long now = System.currentTimeMillis();
        boolean live = window != 0 && view && captured && !GuiInput.ownsInput() && !zombie.GameTime.isGamePaused();

        int buttons = 0;
        int digit = -1;
        int wheel = zombie.input.Mouse.wheelDelta;
        if (live) {
            for (int[] k : KEYMAP) {
                if (GLFW.glfwGetKey(window, k[0]) == GLFW.GLFW_PRESS) buttons |= k[1];
            }
            for (int[] m : MOUSEMAP) {
                if (GLFW.glfwGetMouseButton(window, m[0]) == GLFW.GLFW_PRESS) buttons |= m[1];
            }
            for (int d = 0; d < 9; d++) {
                if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_1 + d) == GLFW.GLFW_PRESS) { digit = d; break; }
            }
            if (wheel != 0) wheelTotal += Integer.signum(wheel);
            if (digit >= 0 && digit != prevDigit) { hotbarChoice = digit; hotbarSeq++; }
            int rising = buttons & ~prevButtons;
            if ((rising & InputState.ATTACK) != 0) attackPresses++;
            if ((rising & InputState.USE) != 0) usePresses++;
            if ((rising & InputState.DROP) != 0) dropPresses++;
            if ((rising & InputState.JUMP) != 0) jumpPresses++;
        }
        prevButtons = buttons;
        prevDigit = digit;
        dbgView = view; dbgCaptured = captured; dbgFocused = live; dbgKeys = buttons;
        dbgYaw = Coords.pzRadToMcYawDeg(ViewpointBridge.yaw());
        state.frame = ++frame;
        state.active = live;
        state.buttons = buttons | (zombie.GameTime.isGamePaused() ? InputState.PAUSED : 0)
                | (SteveControl.ownsSurvival(zombie.characters.IsoPlayer.getInstance()) ? InputState.STEVE_DRIVES : 0);
        state.hotbar = hotbarChoice;
        state.hotbarSeq = hotbarSeq;
        state.attackPresses = attackPresses;
        state.usePresses = usePresses;
        state.dropPresses = dropPresses;
        state.jumpPresses = jumpPresses;
        float[] fr = ViewpointBridge.latestFrame();
        state.eyeHeight = fr != null ? fr[4] : 0f;
        state.fovDeg = ViewpointBridge.fovDegrees();
        int vwid = Display.getWidth(), vhei = Display.getHeight();
        state.aspect = vhei > 0 ? (float) vwid / vhei : 0f;
        state.wheel = wheelTotal;
        state.yawDeg = Coords.pzRadToMcYawDeg(ViewpointBridge.yaw());
        state.pitchDeg = Coords.pzPitchRadToMcDeg(ViewpointBridge.pitch());
        LinkService.sendInput(state);
    }
}



