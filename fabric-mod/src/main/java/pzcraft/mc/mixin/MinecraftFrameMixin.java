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
    //#if MC_26_1
    //$ // 26.1.2 computes the pause inline in runTick (no Gui.isPausing): add PZ's pause where the field is written
    //$ @org.spongepowered.asm.mixin.Shadow private volatile boolean pause;
    //$ @Redirect(method = "runTick", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;pause:Z", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    //$ private void pzcraft$pauseWithPz(Minecraft self, boolean value) {
    //$     pause = value || pzcraft.mc.Session.pzConnected && pzcraft.mc.Session.released && pzcraft.mc.Session.pzPaused
    //$             && self.hasSingleplayerServer() && !self.getSingleplayerServer().isPublished();
    //$ }
    //#else
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;isPausing()Z"))
    private boolean pzcraft$pauseWithPz(net.minecraft.client.gui.Gui gui) {
        return gui.isPausing() || (pzcraft.mc.Session.pzConnected && pzcraft.mc.Session.released && pzcraft.mc.Session.pzPaused);
    }
    //#endif
    @Inject(method = "runTick", at = @At("HEAD"))
    private void pzcraft$beforeFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameHook.beforeFrame((Minecraft) (Object) this);
    }

    // Queue readbacks before this submit so callbacks can publish on the next frame, rather than two frames later.
    //#if MC_1_20
    //$ // 1.20.1 draws the whole frame in runTick: the frame is finished when the main target is unbound for the blit
    //$ @Inject(method = "runTick", at = @At(value="INVOKE",target="Lcom/mojang/blaze3d/pipeline/RenderTarget;unbindWrite()V",ordinal=0))
    //#elif MC_26_1
    //$ @Inject(method = "renderFrame", at = @At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
    //#else
    @Inject(method = "renderFrame", at = @At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    //#endif
    private void pzcraft$afterFrame(boolean advanceGameTime, CallbackInfo ci) {
        FrameHook.afterFrame((Minecraft) (Object) this);
    }
}
