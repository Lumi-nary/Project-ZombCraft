package pzcraft.mc.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/**
 * Minecraft only lets you hold the attack button to mine while it thinks the mouse is grabbed. PZ owns the real mouse,
 * so while PZ is driving and no Minecraft screen is open, report it as grabbed.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Redirect(method = {"onMove", "handleAccumulatedMovement"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
    private boolean pzcraft$screenFocus(Minecraft mc) {
        return (Session.pzConnected && mc.gui.screen() != null) || mc.isWindowActive();
    }
    @Inject(method = "isMouseGrabbed", at = @At("HEAD"), cancellable = true)
    private void pzcraft$grabbedWhilePzDrives(CallbackInfoReturnable<Boolean> cir) {
        if (Session.pzConnected && Minecraft.getInstance().gui.screen() == null) cir.setReturnValue(true);
    }
}

