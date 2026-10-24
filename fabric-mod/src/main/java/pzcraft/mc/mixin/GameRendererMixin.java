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
    //#if MC_1_20
    //$ // 1.20.1 exports no native hand yet (EntitySceneExporter): Minecraft keeps drawing it into the HUD layer
    //$ private void pzcraft$nativeHands(CallbackInfo ci) {}
    //#else
    private void pzcraft$nativeHands(CallbackInfo ci) { if(Session.nativeWorld)ci.cancel(); }
    //#endif
    //#if MC_26_1
    //$ // 26.1.2 has no render3dHud: the same moment is renderLevel's "hand" profiler section, right after the level
    //$ @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V", args = "ldc=hand"))
    //#else
    @Inject(method = "render3dHud", at = @At("HEAD"))
    //#endif
    private void pzcraft$splitWorldLayer(CallbackInfo ci) {
        FrameHook.afterWorld(Minecraft.getInstance());
    }
}
