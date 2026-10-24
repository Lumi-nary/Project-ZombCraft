package pzcraft.mc.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.ModKeys;

/**
 * 1.20.1 screens poll the keyboard of Minecraft's own window (Screen.hasShiftDown/hasControlDown/hasAltDown and mods that
 * call isKeyDown), but PZ's window has the keyboard: a key PZ reports held is down here too. Fixes shift-click, ctrl+Q and
 * shift double-click in every container screen.
 */
@Mixin(InputConstants.class)
public abstract class KeyDown120Mixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void pzcraft$pzKeyboard(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        var mc = Minecraft.getInstance();
        if (mc != null && mc.getWindow() != null && window == mc.getWindow().getWindow() && ModKeys.held(key)) cir.setReturnValue(true);
    }
}
