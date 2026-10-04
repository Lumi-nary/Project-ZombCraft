package pzcraft.mc.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzGuns;
import pzcraft.mc.Session;

/** Hold the native pistol toward the camera's aim rather than down beside Steve's leg. */
@Mixin(AvatarRenderer.class)
public abstract class GunArmPoseMixin {
    @Inject(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
            at = @At("HEAD"), cancellable = true)
    private static void pzcraft$holdGun(Avatar avatar, ItemStack stack, InteractionHand hand,
                                      CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
        if (Session.pzConnected && hand == InteractionHand.MAIN_HAND && PzGuns.held(stack))
            cir.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
    }
}

