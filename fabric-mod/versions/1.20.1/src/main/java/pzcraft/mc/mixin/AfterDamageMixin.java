package pzcraft.mc.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.ActorProxies;

/**
 * 1.20.1 only: Fabric API 0.92 has no ServerLivingEntityEvents.AFTER_DAMAGE. The same moment is the end of
 * LivingEntity.actuallyHurt (armour, effects and absorption applied); what it took is the health and absorption lost.
 */
@Mixin(LivingEntity.class)
public abstract class AfterDamageMixin {
    @Unique private float pzcraft$before;

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void pzcraft$before(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        pzcraft$before = self.getHealth() + self.getAbsorptionAmount();
    }

    @Inject(method = "actuallyHurt", at = @At("TAIL"))
    private void pzcraft$after(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float taken = pzcraft$before - (self.getHealth() + self.getAbsorptionAmount());
        if (taken > 0f) ActorProxies.afterDamageHook(self, source, amount, taken);
    }
}
