package pzcraft.mc;
import net.minecraft.client.Minecraft;
//#if MC_26_1
//$ import org.lwjgl.glfw.GLFW;
//#else
import org.lwjgl.sdl.SDLVideo;
//#endif
public final class WindowMode {
    /** Minimized has the same measured latency with less CPU on the test PC; fully hidden remains opt-in. */
    public static final boolean DEFAULT_HIDDEN=Boolean.getBoolean("pzcraft.hiddenWindow");
    public static final boolean CREATE_HIDDEN=!Boolean.getBoolean("pzcraft.visibleWindow");
    public static boolean hidden=CREATE_HIDDEN;
    private static boolean initialized=!CREATE_HIDDEN;
    public static void initialize(Minecraft mc) {
        if(!initialized && Session.pzConnected && Session.released)set(mc,DEFAULT_HIDDEN);
    }
    public static void set(Minecraft mc,boolean hide) {
        long window=mc.getWindow().handle();
        //#if MC_26_1
        //$ GLFW.glfwSetWindowAttrib(window,GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);
        //$ if(hide) {GLFW.glfwRestoreWindow(window);GLFW.glfwHideWindow(window);}
        //$ else if(org.lwjgl.system.Platform.get()==org.lwjgl.system.Platform.WINDOWS) minimizeOffScreen(window);
        //$ else {GLFW.glfwShowWindow(window);GLFW.glfwIconifyWindow(window);}
        //#else
        SDLVideo.SDL_SetWindowFocusable(window,false);
        if(hide) {SDLVideo.SDL_RestoreWindow(window);SDLVideo.SDL_HideWindow(window);}
        else {SDLVideo.SDL_ShowWindow(window);SDLVideo.SDL_MinimizeWindow(window);}
        //#endif
        hidden=hide;
        initialized=true;
    }
    //#if MC_26_1
    //$ /**
    //$  * Windows: show the GLFW window straight as minimized, not activated, and with its minimized position off screen. The
    //$  * window has no taskbar button (HiddenWindowMixin), and Windows parks such a minimized window as a small caption at the
    //$  * bottom-left of the desktop unless told otherwise; show + iconify also flashed it at full size for a frame.
    //$  */
    //$ private static void minimizeOffScreen(long window) {
    //$     long hwnd=org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
    //$     try(org.lwjgl.system.MemoryStack stack=org.lwjgl.system.MemoryStack.stackPush()) {
    //$         org.lwjgl.system.windows.WINDOWPLACEMENT p=org.lwjgl.system.windows.WINDOWPLACEMENT.calloc(stack).length(org.lwjgl.system.windows.WINDOWPLACEMENT.SIZEOF);
    //$         if(!org.lwjgl.system.windows.User32.GetWindowPlacement(null,hwnd,p)) {GLFW.glfwShowWindow(window);GLFW.glfwIconifyWindow(window);return;}
    //$         p.length(org.lwjgl.system.windows.WINDOWPLACEMENT.SIZEOF).flags(org.lwjgl.system.windows.User32.WPF_SETMINPOSITION)
    //$                 .showCmd(org.lwjgl.system.windows.User32.SW_SHOWMINNOACTIVE);
    //$         p.ptMinPosition().x(-32000).y(-32000);
    //$         org.lwjgl.system.windows.User32.SetWindowPlacement(null,hwnd,p);
    //$     }
    //$ }
    //#endif
    private WindowMode() {}
}
