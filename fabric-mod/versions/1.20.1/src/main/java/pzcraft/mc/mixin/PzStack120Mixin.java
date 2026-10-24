package pzcraft.mc.mixin;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzItems;
@Mixin(ItemStack.class)
public abstract class PzStack120Mixin {
    @Inject(method="getMaxStackSize",at=@At("HEAD"),cancellable=true)
    private void pzcraft$stackLimit(CallbackInfoReturnable<Integer> ci) {
        ItemStack stack=(ItemStack)(Object)this;
        if(stack.getItem()==PzItems.PZ_ITEM && stack.hasTag() && stack.getTag().contains("pzMaxStack"))ci.setReturnValue(Math.max(1,Math.min(64,stack.getTag().getInt("pzMaxStack"))));
    }
}
