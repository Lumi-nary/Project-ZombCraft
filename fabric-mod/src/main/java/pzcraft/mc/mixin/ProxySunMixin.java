package pzcraft.mc.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.ActorProxies;

/** 26.3 moved actual sunlight ignition to Mob, independently of Zombie.isSunSensitive. */
@Mixin(Mob.class)
public abstract class ProxySunMixin {
    @Inject(method="isSunBurnTick",at=@At("HEAD"),cancellable=true)
    private void sun(CallbackInfoReturnable<Boolean> ci){if(ActorProxies.isProxy((Mob)(Object)this))ci.setReturnValue(false);}
}

