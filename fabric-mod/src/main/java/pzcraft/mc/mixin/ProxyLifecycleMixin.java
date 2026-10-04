package pzcraft.mc.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import pzcraft.mc.ActorProxies;

/** PZ zombies do not burn in sunlight or convert to drowned. Crawling bounds must also refresh on the client. */
@Mixin(Zombie.class)
public abstract class ProxyLifecycleMixin {
    @Inject(method="isSunSensitive",at=@At("HEAD"),cancellable=true)
    private void sun(CallbackInfoReturnable<Boolean> ci){if(ActorProxies.isProxy((Zombie)(Object)this))ci.setReturnValue(false);}
    @Inject(method="convertsInWater",at=@At("HEAD"),cancellable=true)
    private void conversion(CallbackInfoReturnable<Boolean> ci){if(ActorProxies.isProxy((Zombie)(Object)this))ci.setReturnValue(false);}
    @Inject(method="onSyncedDataUpdated",at=@At("RETURN"))
    private void dimensions(EntityDataAccessor<?> data,CallbackInfo ci){
        var self=(Zombie)(Object)this;
        if(self.level().isClientSide()&&ActorProxies.isProxy(self)&&self.getCustomName()!=null) {
            String name=self.getCustomName().getString();
            float expected=name.endsWith(":crawl")?.65f:name.endsWith(":small")?.7f:name.endsWith(":animal")?1.4f:1.95f;
            if(Math.abs(self.getBbHeight()-expected)>.01f)self.refreshDimensions();
        }
    }
}

