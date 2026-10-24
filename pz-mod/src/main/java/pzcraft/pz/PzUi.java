package pzcraft.pz;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.internal.ImGuiContext;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;

/**
 * PzCraft's own screens (the start screen, the Mojang-style loading screen, the Zomboid-or-Steve choice), drawn with
 * Dear ImGui (PZ's imgui-java) in a context of their own at every frame swap (Display.update, on the render thread that
 * owns the GL context), on top of PZ's UI. Viewpoint draws
 * its windows the same way, and these follow its look for now: dark rounded panels, text sized by the screen height.
 * PZ's own context is put back after every frame. While a screen is up, PZ's menu does not see the mouse
 * ({@link #ownsMouse}, read by Patch_GuiMouse). Anything that goes wrong turns the screens off for the session.
 */
public final class PzUi {
    /** One of the screens. Called on the frame's thread, between ImGui's newFrame and render. */
    interface Screen {
        boolean shown();
        void draw(float width, float height);
    }

    // our palette (not Viewpoint's): near-black panels, warm white text, one green accent
    static final int PANEL = rgba(22, 24, 29, 235), PANEL_EDGE = rgba(70, 76, 88, 255), TEXT = rgba(236, 233, 226, 255),
            DIM = rgba(150, 152, 160, 255), ACCENT = rgba(96, 186, 92, 255), ACCENT_HOVER = rgba(120, 210, 112, 255),
            BUTTON = rgba(48, 52, 62, 255), BUTTON_HOVER = rgba(66, 72, 86, 255), WARN = rgba(232, 176, 64, 255),
            SHADE = rgba(0, 0, 0, 170);

    private static final Path[] FONT_FILES = {Path.of("C:/Windows/Fonts/segoeui.ttf"), Path.of("C:/Windows/Fonts/arial.ttf"),
            Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")};
    private static final Path[] BOLD_FILES = {Path.of("C:/Windows/Fonts/segoeuib.ttf"), Path.of("C:/Windows/Fonts/arialbd.ttf"),
            Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")};

    private static Screen[] screens = new Screen[0];
    private static ImGuiContext context;
    private static ImGuiImplGl3 backend;
    private static ImFont body, title, big;
    private static int fontHeight = -1;
    private static double lastTime;
    private static boolean failed;
    private static volatile boolean ownsMouse;
    private static volatile long drawnAt;
    private static final int[] w = new int[1], h = new int[1], fw = new int[1], fh = new int[1];
    private static final double[] cx = new double[1], cy = new double[1];

    private PzUi() {}

    static void register(Screen... list) { screens = list; }

    /**
     * True while one of the screens is up: PZ's menu must not take the clicks meant for it. Only as long as screens are
     * being drawn: if the frame hook ever stopped running, PZ's mouse is not kept from it.
     */
    public static boolean ownsMouse() { return ownsMouse && System.nanoTime() - drawnAt < 250_000_000L; }

    /** Before each buffer swap, on the render thread (Patch_PzUiFrame). Never throws into PZ. */
    public static void frameEnd() {
        Screen screen = null;
        try {
            if (failed || zombie.core.Core.isImGui()) { ownsMouse = false; return; }   // PZ's debug ImGui has the frame
            for (Screen s : screens) if (s.shown()) { screen = s; break; }
        } catch (Throwable t) {
            fail(t);
            return;
        }
        if (screen == null) { ownsMouse = false; return; }
        if (!glReady()) { ownsMouse = false; return; }   // PZ's intro and any frame without a GL context: draw nothing
        ImGuiContext previous = ImGui.getCurrentContext();
        try {
            if (context == null) create(); else ImGui.setCurrentContext(context);
            input();
            ImGui.newFrame();
            screen.draw(ImGui.getIO().getDisplaySizeX(), ImGui.getIO().getDisplaySizeY());
            ImGui.render();
            int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            backend.renderDrawData(ImGui.getDrawData());
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bound);
            ownsMouse = true;
            drawnAt = System.nanoTime();
        } catch (Throwable t) {
            fail(t);
        } finally {
            if (previous != null && previous.ptr != 0) ImGui.setCurrentContext(previous);
        }
    }

    private static void fail(Throwable t) {
        failed = true;
        ownsMouse = false;
        Log.error("PzCraft's screens are off for this session after an error", t);
    }

    /**
     * True when this thread has a current OpenGL context with its functions loaded. Without one, LWJGL does not throw: it
     * aborts the whole JVM ("No context is current ... The JVM will abort execution", seen on PZ's intro, which runs before
     * the menu, when the screens were drawn with no context). So nothing touches the GL backend until this is true.
     */
    private static boolean glReady() {
        if (GLFW.glfwGetCurrentContext() == 0) return false;
        try {
            GLCapabilities caps = GL.getCapabilities();
            return caps != null && caps.glCreateShader != 0 && caps.glDrawElements != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void create() {
        context = ImGui.createContext();
        ImGui.setCurrentContext(context);
        ImGui.getIO().setIniFilename(null);
        style();
        GLFW.glfwGetWindowSize(Display.getWindow(), w, h);
        fonts(h[0]);                                    // before the backend: init() makes the font texture
        backend = new ImGuiImplGl3();
        backend.init("#version 130");
        lastTime = GLFW.glfwGetTime();
        Log.info("Project-ZombCraft screens: ImGui ready");
    }

    /** Text sized by the window's height: body text a fortieth of it (27 px at 1080). Rebuilt when the height changes. */
    private static void fonts(int height) {
        int size = Math.max(14, Math.round(height / 40f));
        if (size == fontHeight) return;
        fontHeight = size;
        var atlas = ImGui.getIO().getFonts();
        atlas.clear();
        Path regular = first(FONT_FILES), bold = first(BOLD_FILES);
        ImFontConfig config = new ImFontConfig();
        config.setOversampleH(2);
        body = regular == null ? atlas.addFontDefault() : atlas.addFontFromFileTTF(regular.toString(), size, config);
        title = bold == null ? body : atlas.addFontFromFileTTF(bold.toString(), size * 1.6f, config);
        big = bold == null ? body : atlas.addFontFromFileTTF(bold.toString(), size * 2.4f, config);
        config.destroy();
        atlas.build();
        ImGui.getIO().setFontDefault(body);
        if (backend != null && context != null) backend.updateFontsTexture();
    }

    private static Path first(Path[] files) {
        for (Path p : files) if (Files.isRegularFile(p)) return p;
        return null;
    }

    private static void style() {
        ImGuiStyle s = ImGui.getStyle();
        s.setWindowRounding(12);
        s.setFrameRounding(8);
        s.setWindowBorderSize(1);
        s.setWindowPadding(26, 22);
        s.setFramePadding(18, 10);
        s.setItemSpacing(12, 12);
        s.setColor(ImGuiCol.WindowBg, 22 / 255f, 24 / 255f, 29 / 255f, .94f);
        s.setColor(ImGuiCol.Border, 70 / 255f, 76 / 255f, 88 / 255f, 1f);
        s.setColor(ImGuiCol.Text, 236 / 255f, 233 / 255f, 226 / 255f, 1f);
        s.setColor(ImGuiCol.TextDisabled, 150 / 255f, 152 / 255f, 160 / 255f, 1f);
        s.setColor(ImGuiCol.Button, 48 / 255f, 52 / 255f, 62 / 255f, 1f);
        s.setColor(ImGuiCol.ButtonHovered, 66 / 255f, 72 / 255f, 86 / 255f, 1f);
        s.setColor(ImGuiCol.ButtonActive, 96 / 255f, 186 / 255f, 92 / 255f, 1f);
    }

    private static void input() {
        long window = Display.getWindow();
        ImGuiIO io = ImGui.getIO();
        GLFW.glfwGetWindowSize(window, w, h);
        GLFW.glfwGetFramebufferSize(window, fw, fh);
        io.setDisplaySize(w[0], h[0]);
        if (w[0] > 0 && h[0] > 0) io.setDisplayFramebufferScale((float) fw[0] / w[0], (float) fh[0] / h[0]);
        fonts(h[0]);
        double now = GLFW.glfwGetTime();
        io.setDeltaTime((float) Math.max(1e-4, now - lastTime));
        lastTime = now;
        boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        GLFW.glfwGetCursorPos(window, cx, cy);
        io.setMousePos(focused ? (float) cx[0] : -Float.MAX_VALUE, focused ? (float) cy[0] : -Float.MAX_VALUE);
        for (int b = 0; b < 3; b++) io.setMouseDown(b, focused && GLFW.glfwGetMouseButton(window, b) == GLFW.GLFW_PRESS);
    }

    // ---- helpers for the screens ----

    static ImFont body() { return body; }
    static ImFont title() { return title; }
    static ImFont big() { return big; }
    static float unit() { return fontHeight; }

    static int rgba(int r, int g, int b, int a) { return (a << 24) | (b << 16) | (g << 8) | r; }   // ImGui's ABGR order

    /** A borderless full-screen window to draw into, with nothing of ImGui's own decoration. */
    static void beginFullScreen(String id, int background) {
        ImGui.setNextWindowPos(0, 0);
        ImGui.setNextWindowSize(ImGui.getIO().getDisplaySizeX(), ImGui.getIO().getDisplaySizeY());
        ImGui.pushStyleColor(ImGuiCol.WindowBg, background);
        ImGui.begin(id, ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoScrollbar);
        ImGui.popStyleColor();
    }

    /** A centred panel of the given size (pixels) inside the full-screen window. Returns its top-left corner. */
    static float[] panel(float width, float height) {
        float x = (ImGui.getIO().getDisplaySizeX() - width) / 2, y = (ImGui.getIO().getDisplaySizeY() - height) / 2;
        ImDrawList d = ImGui.getWindowDrawList();
        d.addRectFilled(x, y, x + width, y + height, PANEL, 14);
        d.addRect(x, y, x + width, y + height, PANEL_EDGE, 14, 0, 1.5f);
        return new float[] {x, y};
    }

    /** Text at a point, in a font and colour; returns its height. */
    static float text(ImFont font, float x, float y, int colour, String s) {
        ImGui.getWindowDrawList().addText(font, font.getFontSize(), x, y, colour, s);
        return font.getFontSize();
    }

    /** Text wrapped to a width; returns the height it took. */
    static float wrapped(ImFont font, float x, float y, float width, int colour, String s) {
        ImGui.getWindowDrawList().addText(font, font.getFontSize(), x, y, colour, s, width);
        ImGui.pushFont(font);
        float height = ImGui.calcTextSize(s, false, width).y;
        ImGui.popFont();
        return height;
    }

    /** A button drawn by hand (so it can be any size and colour); true on the frame it is clicked. */
    static boolean button(String id, String label, float x, float y, float width, float height, boolean primary, boolean enabled) {
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton(id, width, height) && enabled;
        boolean hover = enabled && ImGui.isItemHovered();
        int fill = !enabled ? rgba(36, 38, 44, 255) : primary ? (hover ? ACCENT_HOVER : ACCENT) : (hover ? BUTTON_HOVER : BUTTON);
        ImDrawList d = ImGui.getWindowDrawList();
        d.addRectFilled(x, y, x + width, y + height, fill, 9);
        ImGui.pushFont(body);
        float tw = ImGui.calcTextSize(label).x;
        ImGui.popFont();
        int colour = !enabled ? DIM : primary ? rgba(14, 22, 14, 255) : TEXT;
        d.addText(body, body.getFontSize(), x + (width - tw) / 2, y + (height - body.getFontSize()) / 2, colour, label);
        return clicked;
    }

    /** An image file as a GL texture (made on first use, on this frame's thread); 0 if the file is missing or unreadable. */
    static int texture(Path file, int[] size) {
        Integer known = TEXTURES.get(file);
        if (known != null) { int[] s = SIZES.get(file); size[0] = s[0]; size[1] = s[1]; return known; }
        if (!Files.isRegularFile(file)) return 0;
        try {
            BufferedImage img = javax.imageio.ImageIO.read(file.toFile());
            if (img == null) return 0;
            int iw = img.getWidth(), ih = img.getHeight();
            ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(iw * ih * 4);
            for (int y = 0; y < ih; y++) for (int x = 0; x < iw; x++) {
                int argb = img.getRGB(x, y);
                pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
            pixels.flip();
            int id = GL11.glGenTextures();
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);    // GL_CLAMP_TO_EDGE
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, iw, ih, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            TEXTURES.put(file, id);
            SIZES.put(file, new int[] {iw, ih});
            size[0] = iw;
            size[1] = ih;
            return id;
        } catch (Exception e) {
            Log.error("could not load " + file, e);
            TEXTURES.put(file, 0);
            SIZES.put(file, new int[] {0, 0});
            return 0;
        }
    }

    private static final java.util.Map<Path, Integer> TEXTURES = new java.util.HashMap<>();
    private static final java.util.Map<Path, int[]> SIZES = new java.util.HashMap<>();
}
