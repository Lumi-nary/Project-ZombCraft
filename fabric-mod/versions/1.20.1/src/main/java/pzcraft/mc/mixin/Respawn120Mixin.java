package pzcraft.mc.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.CollisionField;
import pzcraft.mc.Session;

/**
 * 1.20.1 version of RespawnMixin: without a bed (or with a missing one) a player starts at the world spawn, which 1.20.1
 * picks in fudgeSpawnLocation (a new ServerPlayer; PlayerList.respawn moves him to his bed afterwards if he has one).
 * In this void world that is not on PZ's ground: stand him on PZ's surface at the spawn point instead, as on 26.x.
 */
@Mixin(ServerPlayer.class)
public abstract class Respawn120Mixin {
    @Inject(method = "fudgeSpawnLocation", at = @At("TAIL"))
    private void pzcraft$pzSurface(ServerLevel level, CallbackInfo ci) {
        if (!Session.pzConnected) return;
        var self = (ServerPlayer) (Object) this;
        var pos = Vec3.atBottomCenterOf(level.getSharedSpawnPos()).add(0, .02, 0);
        self.moveTo(pos.x, CollisionField.spawnSurface(pos.x, pos.y, pos.z), pos.z, self.getYRot(), self.getXRot());
    }
}
