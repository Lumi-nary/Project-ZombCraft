package pzcraft.mc.mixin;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.GunFeel;

@Mixin(Gui.class)
public abstract class GunHud120Mixin {
    @Inject(method="renderCrosshair",at=@At("HEAD"),cancellable=true)
    private void gunCrosshair(GuiGraphics graphics,CallbackInfo ci) { if(GunFeel.drawCrosshair(graphics))ci.cancel(); }
    @Inject(method="render",at=@At("TAIL"))
    private void gunAmmo(GuiGraphics graphics,float partial,CallbackInfo ci) { GunFeel.drawAmmo(graphics); }
}
