package pzcraft.mc.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/**
 * Steve's eye is Minecraft's own (1.62 blocks standing, lower sneaking or swimming): PZ's camera follows it (FrameHook
 * publishes it, PZ's SteveEye places Viewpoint's eye there). The reverse, Minecraft adopting Viewpoint's lower PZ-head
 * eye, is kept only as an opt-in comparison ({@code -Dpzcraft.pzEye=true}); then both overrides must apply together,
 * or the cached vanilla eye and the overridden one disagree (the deep-pool drowning bug).
 */
@Mixin(Entity.class)
public abstract class EntityEyeMixin {
    private static final boolean PZ_EYE = Boolean.getBoolean("pzcraft.pzEye");

    @Inject(method = "getEyeY()D", at = @At("HEAD"), cancellable = true)
    private void pzcraft$fluidEye(CallbackInfoReturnable<Double> cir) {
        if(PZ_EYE&&Session.pzConnected&&(Object)this instanceof Player player&&Session.eyeHeight>.2f&&Session.eyeHeight<3f)
            cir.setReturnValue(player.getY()+Session.eyeHeight);
    }
    @Inject(method = "getEyeHeight()F", at = @At("HEAD"), cancellable = true)
    private void pzcraft$viewpointEyeHeight(CallbackInfoReturnable<Float> cir) {
        if (!PZ_EYE || !Session.pzConnected || !((Object) this instanceof Player)) return;
        float h = Session.eyeHeight;
        if (h > 0.2f && h < 3f) cir.setReturnValue(h);
    }
}

