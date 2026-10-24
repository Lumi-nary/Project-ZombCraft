package pzcraft.mc.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.InteractionBridge;
import pzcraft.mc.PzGuns;

@Mixin(Minecraft.class)
public abstract class UseMixin {
    @Shadow private int rightClickDelay;
    @Inject(method="startUseItem",at=@At("HEAD"),cancellable=true)
    private void pzObject(CallbackInfo ci) {if(PzGuns.use((Minecraft)(Object)this)||pzcraft.mc.PzMining.placeTile((Minecraft)(Object)this)||pzcraft.mc.PzMining.useCar((Minecraft)(Object)this)||pzcraft.mc.PzMining.rest((Minecraft)(Object)this)||pzcraft.mc.PzSpawners.use((Minecraft)(Object)this)||pzcraft.mc.PzFurniture.use((Minecraft)(Object)this)||InteractionBridge.use((Minecraft)(Object)this)){rightClickDelay=4;ci.cancel();}}
}
