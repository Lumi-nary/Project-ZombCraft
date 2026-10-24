package pzcraft.mc.mixin;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//#if MC_26_1
//$ import org.lwjgl.glfw.GLFW;
//$ import org.lwjgl.system.windows.User32;
//$ import org.spongepowered.asm.mixin.injection.Inject;
//$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#else
import org.lwjgl.sdl.SDLVideo;
//#endif
import pzcraft.mc.WindowMode;
/** Create the GPU window invisibly; keep presenting so asynchronous read-backs still flush promptly. */
@Mixin(Window.class)
public abstract class HiddenWindowMixin {
    //#if MC_1_20
    //$ // 1.20.1 creates its GLFW window in the constructor: hint it hidden (and not focused when shown) right before
    //$ @Inject(method="<init>",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J",remap=false))
    //$ private void pzcraft$hidden(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
    //$     if(WindowMode.CREATE_HIDDEN) {GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);}
    //$ }
    //$ // no taskbar button, no Alt+Tab entry, never activated (as 26.1.2 below)
    //$ @Inject(method="<init>",at=@At("RETURN"))
    //$ private void pzcraft$noTaskbar(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
    //$     long window=((Window)(Object)this).getWindow();
    //$     if(!WindowMode.CREATE_HIDDEN || window==0L || org.lwjgl.system.Platform.get()!=org.lwjgl.system.Platform.WINDOWS) return;
    //$     long hwnd=org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
    //$     long ex=User32.GetWindowLongPtr(hwnd,User32.GWL_EXSTYLE);
    //$     User32.SetWindowLongPtr(hwnd,User32.GWL_EXSTYLE,(ex&~User32.WS_EX_APPWINDOW)|User32.WS_EX_TOOLWINDOW|User32.WS_EX_NOACTIVATE);
    //$     User32.SetWindowPos(hwnd,0L,0,0,0,0,User32.SWP_NOMOVE|User32.SWP_NOSIZE|User32.SWP_NOZORDER|User32.SWP_NOACTIVATE|User32.SWP_FRAMECHANGED);
    //$ }
    //$ // a minimized window reports 0x0: keep the last real size (MouseHandler divides PZ's clicks by it)
    //$ @Inject(method="onResize",at=@At("HEAD"),cancellable=true)
    //$ private void pzcraft$keepSizeWhenMinimized(long window,int width,int height,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
    //$     if(WindowMode.CREATE_HIDDEN && (width<=0 || height<=0)) ci.cancel();
    //$ }
    //#elif MC_26_1
    //$ // 26.1.2 creates a GLFW window: hint it hidden (and not focused when shown) right before it is made
    //$ @Inject(method="createGlfwWindow",at=@At(value="INVOKE",target="Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    //$ private static void pzcraft$hidden(int width,int height,String title,long monitor,com.mojang.blaze3d.systems.GpuBackend backend,CallbackInfoReturnable<Long> cir) {
    //$     if(WindowMode.CREATE_HIDDEN) {GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);}
    //$ }
    //$ // ... and, on Windows, like 26.3's SDL "not focusable" window: no taskbar button, no Alt+Tab entry, never activated. Without
    //$ // this the minimized GLFW window sat in the taskbar and Alt+Tab, and clicking it brought Minecraft up over PZ.
    //$ @Inject(method="createGlfwWindow",at=@At("RETURN"))
    //$ private static void pzcraft$noTaskbar(int width,int height,String title,long monitor,com.mojang.blaze3d.systems.GpuBackend backend,CallbackInfoReturnable<Long> cir) {
    //$     long window=cir.getReturnValueJ();
    //$     if(!WindowMode.CREATE_HIDDEN || window==0L || org.lwjgl.system.Platform.get()!=org.lwjgl.system.Platform.WINDOWS) return;
    //$     long hwnd=org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
    //$     long ex=User32.GetWindowLongPtr(hwnd,User32.GWL_EXSTYLE);
    //$     User32.SetWindowLongPtr(null,hwnd,User32.GWL_EXSTYLE,(ex&~User32.WS_EX_APPWINDOW)|User32.WS_EX_TOOLWINDOW|User32.WS_EX_NOACTIVATE);
    //$     User32.SetWindowPos(null,hwnd,0L,0,0,0,0,User32.SWP_NOMOVE|User32.SWP_NOSIZE|User32.SWP_NOZORDER|User32.SWP_NOACTIVATE|User32.SWP_FRAMECHANGED);
    //$ }
    //$ // GLFW reports a minimized window (WindowMode parks it minimized off screen) as 0x0. Keep the last real size: screens
    //$ // place PZ's clicks by it (MouseHandler divides by it), so with 0x0 every click landed at the top-left corner.
    //$ @Inject(method="onResize",at=@At("HEAD"),cancellable=true)
    //$ private void pzcraft$keepSizeWhenMinimized(long window,int width,int height,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
    //$     if(WindowMode.CREATE_HIDDEN && (width<=0 || height<=0)) ci.cancel();
    //$ }
    //#else
    @ModifyArg(method="createWindow",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),index=3)
    private long pzcraft$hidden(long flags) {return WindowMode.CREATE_HIDDEN?flags|SDLVideo.SDL_WINDOW_HIDDEN|SDLVideo.SDL_WINDOW_NOT_FOCUSABLE:flags;}
    //#endif
}
