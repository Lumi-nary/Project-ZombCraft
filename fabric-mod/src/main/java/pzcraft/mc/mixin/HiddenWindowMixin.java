package pzcraft.mc.mixin;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.lwjgl.sdl.SDLVideo;
import pzcraft.mc.WindowMode;
/** Create the GPU window invisibly; keep presenting so asynchronous read-backs still flush promptly. */
@Mixin(Window.class)
public abstract class HiddenWindowMixin {
    @ModifyArg(method="createWindow",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"),index=3)
    private long pzcraft$hidden(long flags) {return WindowMode.CREATE_HIDDEN?flags|SDLVideo.SDL_WINDOW_HIDDEN|SDLVideo.SDL_WINDOW_NOT_FOCUSABLE:flags;}
}

