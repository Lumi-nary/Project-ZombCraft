package pzcraft.mc.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/**
 * Minecraft mobs (our invisible zombie proxies and anything else) must never be drawn over PZ's own zombies. While PZ
 * draws the world natively, dropped items are drawn there too (with PZ's light and shadows), not in the video layer.
 */
@Mixin(LevelExtractor.class)
public abstract class EntityVisibilityMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void pzcraft$hideMobs(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks,
                                  long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir) {
        if (Session.pzConnected && pzcraft.mc.ActorProxies.isProxy(entity)) cir.setReturnValue(false);
        else if (Session.nativeWorld) cir.setReturnValue(false);
    }
}

