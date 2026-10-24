package pzcraft.mc.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.ActorProxies;

/**
 * Proxy actors belong to one PZ session (their ids mean nothing after a restart), so they are never written to the world. Saved
 * proxies used to pile up in the entity storage and were only discarded when their chunk was loaded again.
 */
@Mixin(value = Entity.class, priority = 1100)
public abstract class ProxyNoSaveMixin {
    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void pzcraft$proxiesAreNotSaved(CallbackInfoReturnable<Boolean> cir) {
        if (ActorProxies.isProxy((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
