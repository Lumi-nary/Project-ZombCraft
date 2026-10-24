package pzcraft.mc.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;
import pzcraft.mc.ServerPlacement;
import pzcraft.mc.Session;

/**
 * A Minecraft server kicks a player who stays in the air with no block around them ("Flying is not enabled"). Steve
 * walks on PZ's floors, which are no blocks: those count as ground here, and so does the air a player who just joined or
 * respawned is held in until they are put at their PZ character (ServerPlacement). Single player never checks this.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerFloatingMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "noBlocksAround", at = @At("RETURN"), cancellable = true)
    private void pzcraft$pzFloors(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() || !Session.pzConnected) return;
        if (entity == player && ServerPlacement.awaiting(player)) {
            cir.setReturnValue(false);
            return;
        }
        // the area vanilla looks at for blocks
        AABB area = entity.getBoundingBox().inflate(0.0625).expandTowards(0.0, -0.55, 0.0);
        if (CollisionField.touches(area)) cir.setReturnValue(false);
    }
}
