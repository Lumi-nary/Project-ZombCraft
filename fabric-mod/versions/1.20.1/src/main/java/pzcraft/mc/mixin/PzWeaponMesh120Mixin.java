package pzcraft.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.PzWeaponModels;

/**
 * 1.20.1 stand-in for 26.x's {@code pzcraft:pzmesh} special item model (and WeaponModelContextMixin): a held or worn PZ
 * weapon without an own Geo model is drawn as PZ's converted mesh instead of its sprite.
 */
@Mixin(ItemRenderer.class)
public abstract class PzWeaponMesh120Mixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void pzcraft$pzWeaponMesh(ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose, MultiBufferSource source,
                                      int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (!stack.isEmpty() && PzWeaponModels.render(stack, context, leftHand, pose, source, light, overlay)) ci.cancel();
    }
}
