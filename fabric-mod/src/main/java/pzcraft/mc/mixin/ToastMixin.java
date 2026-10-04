package pzcraft.mc.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.Session;

/** Advancement and recipe toasts ("Isn't It Iron Pick?") would pop up over PZ's screen every time the hidden world starts. */
@Mixin(ToastManager.class)
public abstract class ToastMixin {
    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void pzcraft$noToasts(Toast toast, CallbackInfo ci) {
        if (Session.pzConnected) ci.cancel();
    }
}

