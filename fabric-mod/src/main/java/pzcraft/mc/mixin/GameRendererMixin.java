package pzcraft.mc.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.FrameHook;
import pzcraft.mc.Session;

/** The level is finished and the hand is next: the moment to split the world layer off from the hand and HUD. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void pzcraft$nativeHands(CallbackInfo ci) { if(Session.nativeWorld)ci.cancel(); }
    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void pzcraft$splitWorldLayer(CallbackInfo ci) {
        FrameHook.afterWorld(Minecraft.getInstance());
    }
}

