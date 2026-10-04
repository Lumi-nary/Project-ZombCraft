package pzcraft.mc;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
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
        SDLVideo.SDL_SetWindowFocusable(window,false);
        if(hide) {SDLVideo.SDL_RestoreWindow(window);SDLVideo.SDL_HideWindow(window);}
        else {SDLVideo.SDL_ShowWindow(window);SDLVideo.SDL_MinimizeWindow(window);}
        hidden=hide;
        initialized=true;
    }
    private WindowMode() {}
}

