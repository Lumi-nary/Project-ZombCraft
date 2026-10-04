package pzcraft.mc.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;

/** Vanilla's heightmap cannot see PZ floors. Use the saved world spawn if the bed is missing/unusable. */
@Mixin(ServerPlayer.class)
public abstract class RespawnMixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void fallback(boolean consume, TeleportTransition.PostTeleportTransition post, CallbackInfoReturnable<TeleportTransition> ci) {
        if (!Session.pzConnected) return;
        var player = (ServerPlayer) (Object) this;
        var result = ci.getReturnValue();
        if (player.getRespawnConfig() == null || result.missingRespawnBlock()) {
            var pos=Vec3.atBottomCenterOf(result.newLevel().getRespawnData().pos()).add(0,.02,0);
            ci.setReturnValue(result.withPosition(new Vec3(pos.x,pzcraft.mc.CollisionField.spawnSurface(pos.x,pos.y,pos.z),pos.z)));
        }
    }
}

