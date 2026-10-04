package pzcraft.mc.mixin;

import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.ActorProxies;

@Mixin(LivingEntity.class)
public abstract class ProxyDimensionsMixin {
    @Inject(method="getDimensions",at=@At("RETURN"),cancellable=true)
    private void crawler(Pose pose,CallbackInfoReturnable<EntityDimensions> ci){
        var self=(LivingEntity)(Object)this;
        if(ActorProxies.isProxy(self)&&self.getCustomName()!=null) {
            String name=self.getCustomName().getString();
            if(name.endsWith(":crawl"))ci.setReturnValue(EntityDimensions.scalable(1f,.65f).withEyeHeight(.45f));
            else if(name.endsWith(":small"))ci.setReturnValue(EntityDimensions.scalable(.4f,.7f).withEyeHeight(.45f));
            else if(name.endsWith(":animal"))ci.setReturnValue(EntityDimensions.scalable(1f,1.4f).withEyeHeight(1.1f));
        }
    }
}

