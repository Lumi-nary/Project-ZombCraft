package pzcraft.pz;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.lwjgl.glfw.*;
import org.lwjglx.opengl.Display;
import pzcraft.protocol.GuiEvent;
import pzcraft.protocol.Wire;

/** Owns PZ window input while a Minecraft screen is open, including Unicode, repeats and mouse dragging. */
public final class GuiInput {
    private static long window;
    private static GLFWKeyCallback keyCallback, oldKey;
    private static GLFWCharCallback charCallback, oldChar;
    private static GLFWMouseButtonCallback buttonCallback, oldButton;
    private static GLFWScrollCallback scrollCallback, oldScroll;
    private static final double[] cursorX = new double[1], cursorY = new double[1];
    private static final ConcurrentLinkedQueue<GuiEvent> pending = new ConcurrentLinkedQueue<>();
    private static volatile Map<String, Object> remote = Map.of();
    private static volatile int requested;
    private static int sequence;
    private static final boolean[] intercepted = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private static final boolean[] interceptedButtons = new boolean[8];
    private static volatile long requestedAt, stateAt;
    private static int skipCharacter;
    private static double lastX = -1, lastY = -1;
    private GuiInput() {}

    static void onState(byte[] bytes) {
        remote = JsonLite.object(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        stateAt = System.nanoTime();
    }
    public static boolean ownsInput() {
        if (!ViewpointBridge.viewEnabled() || !LinkService.connected()) return false;
        long now = System.nanoTime();
        Number ack = (Number) remote.getOrDefault("handled", 0);
        if (requested != 0 && ack.intValue() < requested && now - requestedAt < 2_000_000_000L) return true;
        return now - stateAt < 1_000_000_000L && !String.valueOf(remote.getOrDefault("screen", "")).isEmpty();
    }
    static Map<String, Object> status() { return remote; }
    static String camera(int mode) {
        if (mode < 0 || mode > 2) return "camera mode must be 0 (first), 1 (back) or 2 (front)";
        emit(GuiEvent.CAMERA_MODE, mode, 0, 0, 0, 0);
        return "Minecraft camera " + mode + " queued";
    }
    static String mcWindow(boolean hidden) {
        emit(GuiEvent.WINDOW_MODE,hidden?1:0,0,0,0,0);return "Minecraft window mode queued";
    }

    /** Render thread: callbacks are chained to PZ's original handlers outside Minecraft screens. */
    public static void frame() {
        long w = Display.getWindow();
        if (w == 0) return;
        if (w != window) install(w);
        GuiEvent e;
        while ((e = pending.peek()) != null && LinkService.send(Wire.MSG_GUI_EVENT, e.encode())) pending.poll();
        if (ownsInput()) {
            GLFW.glfwGetCursorPos(w, cursorX, cursorY);
            double x = cursorX[0] / Math.max(1, Display.getWidth()), y = cursorY[0] / Math.max(1, Display.getHeight());
            if (x != lastX || y != lastY) { emit(GuiEvent.MOVE, 0, 0, 0, x, y); lastX = x; lastY = y; }
        } else { lastX = lastY = -1; }
    }

    private static void install(long w) {
        window = w;
        keyCallback = GLFWKeyCallback.create((win, key, scan, action, mods) -> {
            if (!key(key, action, mods) && oldKey != null) oldKey.invoke(win, key, scan, action, mods);
        });
        oldKey = GLFW.glfwSetKeyCallback(w, keyCallback);
        charCallback = GLFWCharCallback.create((win, codepoint) -> {
            if (!character(codepoint) && oldChar != null) oldChar.invoke(win, codepoint);
        });
        oldChar = GLFW.glfwSetCharCallback(w, charCallback);
        buttonCallback = GLFWMouseButtonCallback.create((win, button, action, mods) -> {
            GLFW.glfwGetCursorPos(win, cursorX, cursorY);
            if (!button(button, action, mods, cursorX[0] / Math.max(1, Display.getWidth()), cursorY[0] / Math.max(1, Display.getHeight()))
                    && oldButton != null) oldButton.invoke(win, button, action, mods);
        });
        oldButton = GLFW.glfwSetMouseButtonCallback(w, buttonCallback);
        scrollCallback = GLFWScrollCallback.create((win, x, y) -> {
            if (ownsInput()) emit(GuiEvent.SCROLL, 0, 0, 0, x, y);
            else if (oldScroll != null) oldScroll.invoke(win, x, y);
        });
        oldScroll = GLFW.glfwSetScrollCallback(w, scrollCallback);
        Log.info("Minecraft screen input attached to the PZ window");
    }

    /** Same route for real window events and explicit local playtests. GLFW keys/actions/modifiers. */
    public static synchronized boolean button(int button,int action,int mods,double x,double y) {
        if(button<0||button>=interceptedButtons.length)return false;
        if(action==GLFW.GLFW_RELEASE&&interceptedButtons[button]) {
            interceptedButtons[button]=false;emit(GuiEvent.BUTTON,button,action,mods,x,y);return true;
        }
        if(!ownsInput())return false;
        interceptedButtons[button]=action!=GLFW.GLFW_RELEASE;emit(GuiEvent.BUTTON,button,action,mods,x,y);return true;
    }
    /** Same route for real window events and explicit local playtests. GLFW keys/actions/modifiers. */
    public static synchronized boolean key(int key, int action, int mods) {
        if (key >= 0 && key < intercepted.length && action == GLFW.GLFW_RELEASE && intercepted[key]) {
            intercepted[key] = false;
            if (ownsInput()) emit(GuiEvent.KEY, key, action, mods, 0, 0);
            return true;
        }
        if (ownsInput()) {
            if (key >= 0 && key < intercepted.length) intercepted[key] = true;
            emit(GuiEvent.KEY, key, action, mods, 0, 0);
            return true;
        }
        if (!LinkService.minecraftReady() || !ViewpointBridge.viewEnabled()) return false;
        // Camera changes remain available to the developer automation without stealing desktop focus.
        int perspectiveKey = ((Number) remote.getOrDefault("perspectiveKey", GLFW.GLFW_KEY_F5)).intValue();
        if (key >= 0 && key < intercepted.length && key == perspectiveKey) {
            intercepted[key] = true;
            emit(GuiEvent.KEY, key, action, mods, 0, 0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_R && gunHeld() && !zombie.GameTime.isGamePaused()) {
            intercepted[key] = action != GLFW.GLFW_RELEASE;
            emit(GuiEvent.KEY, key, action, mods, 0, 0);
            return true;
        }
        if (!ViewpointBridge.captured() || zombie.GameTime.isGamePaused()) return false;
        if (key == GLFW.GLFW_KEY_E || key == GLFW.GLFW_KEY_T || key == GLFW.GLFW_KEY_SLASH) {
            intercepted[key] = true;
            if (action == GLFW.GLFW_PRESS) {
                requested = emit(GuiEvent.KEY, key, action, mods, 0, 0);
                requestedAt = System.nanoTime();
                skipCharacter = key; // The opener's text callback follows; its initial text is already in the screen.
            }
            return true;
        }
        return false;
    }
    static synchronized boolean character(int codepoint) {
        if (!ownsInput()) return false;
        if (skipCharacter != 0) { skipCharacter = 0; return true; }
        emit(GuiEvent.CHARACTER, codepoint, 0, 0, 0, 0);
        return true;
    }
    static synchronized int emit(int type, int value, int action, int mods, double x, double y) {
        if (pending.size() >= 4096) throw new IllegalStateException("Minecraft GUI input queue full");
        GuiEvent e = new GuiEvent(++sequence, type, value, action, mods, x, y);
        pending.add(e);
        return e.sequence();
    }
    /** Reserve openers in PZ's polling keyboard too: E must not also start a door animation. */
    public static boolean mutesRawKey(int lwjglKey) {
        int glfw = org.lwjglx.input.KeyCodes.toGlfwKey(lwjglKey);
        return (glfw >= 0 && glfw < intercepted.length && intercepted[glfw]) || ownsInput()
                || (SteveControl.drives(zombie.characters.IsoPlayer.getInstance())
                && (lwjglKey == 18 || lwjglKey == 20 || lwjglKey == 53 || lwjglKey == 63 || lwjglKey == 19 && gunHeld()));
    }
    private static boolean gunHeld() {
        Object value = remote.get("held");
        if (!(value instanceof Map<?,?> held)) return false;
        String item = String.valueOf(held.get("item"));
        return item.equals("1 pz:Base.Pistol") || item.startsWith("1 pz:Base.Pistol ")
                || item.equals("1 pz:Base.9mmClip") || item.startsWith("1 pz:Base.9mmClip ");   // R also puts a round into a held magazine
    }
}

