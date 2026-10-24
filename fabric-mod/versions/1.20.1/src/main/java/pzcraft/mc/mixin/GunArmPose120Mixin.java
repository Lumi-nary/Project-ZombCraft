package pzcraft.mc.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.PzGuns;
import pzcraft.mc.Session;

@Mixin(PlayerRenderer.class)
public abstract class GunArmPose120Mixin {
    @Inject(method="getArmPose",at=@At("HEAD"),cancellable=true)
    private static void aim(AbstractClientPlayer player,InteractionHand hand,CallbackInfoReturnable<HumanoidModel.ArmPose> ci) {
        if(Session.pzConnected && hand==InteractionHand.MAIN_HAND && PzGuns.held(player.getMainHandItem()))ci.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
    }
}
