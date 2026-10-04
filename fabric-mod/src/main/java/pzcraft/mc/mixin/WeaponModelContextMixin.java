package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pzcraft.mc.PzWeaponModels;

@Mixin(SpecialModelWrapper.class)
public class WeaponModelContextMixin {
    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/special/SpecialModelRenderer;extractArgument(Lnet/minecraft/world/item/ItemStack;)Ljava/lang/Object;"))
    private Object pzcraft$weaponContext(SpecialModelRenderer<?> renderer, ItemStack stack, Operation<Object> original,
                                         @Local(argsOnly = true) ItemDisplayContext context) {
        Object placement = PzWeaponModels.handContext(renderer, context);
        return placement != null ? placement : original.call(renderer, stack);
    }
}

