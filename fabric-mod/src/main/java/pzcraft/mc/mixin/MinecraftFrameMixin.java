package pzcraft.mc.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.FrameHook;

/** Runs once per rendered frame (not per 20 Hz tick) so PZ gets a smooth, interpolated pose. */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;isPausing()Z"))
    private boolean pzcraft$pauseWithPz(net.minecraft.client.gui.Gui gui) {
        return gui.isPausing() || (pzcraft.mc.Session.pzConnected && pzcraft.mc.Session.released && pzcraft.mc.Session.pzPaused);
    }
    @Inject(method = "runTick", at = @At("HEAD"))
    private void pzcraft$beforeFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameHook.beforeFrame((Minecraft) (Object) this);
    }

    // Queue readbacks before this submit so callbacks can publish on the next frame, rather than two frames later.
    @Inject(method = "renderFrame", at = @At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void pzcraft$afterFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameHook.afterFrame((Minecraft) (Object) this);
    }
}

