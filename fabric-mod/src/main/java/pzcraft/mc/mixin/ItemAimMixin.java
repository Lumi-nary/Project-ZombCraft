package pzcraft.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;
import pzcraft.mc.Session;

/**
 * Items that aim for themselves (boats, buckets, lily pads, glass bottles...) ray-cast Minecraft's blocks only, so PZ's
 * floors and walls were empty air to them and a boat could not be put down on PZ ground. A nearer PZ surface now counts
 * as a hit, on both the client and the integrated server. The reported block is the solid side of the surface, so code
 * that places on the far side of the hit (boat on top, water against the wall) lands in the air right next to it.
 */
@Mixin(Item.class)
public abstract class ItemAimMixin {
    @Inject(method = "getPlayerPOVHitResult", at = @At("RETURN"), cancellable = true)
    private static void pzcraft$pzSurfaces(Level level, Player player, ClipContext.Fluid fluid, CallbackInfoReturnable<BlockHitResult> cir) {
        if (!Session.pzConnected) return;
        Vec3 from = player.getEyePosition();
        Vec3 dir = Player.calculateViewVector(player.getXRot(), player.getYRot());
        BlockHitResult current = cir.getReturnValue();
        double range = player.blockInteractionRange();
        double limit = current.getType() == HitResult.Type.MISS ? range : Math.min(range, from.distanceTo(current.getLocation()));
        CollisionField.Hit hit = CollisionField.raycast(from, dir, limit);
        if (hit == null) return;
        BlockPos solid = hit.air().relative(hit.face().getOpposite());
        cir.setReturnValue(new BlockHitResult(hit.point(), hit.face(), solid, false));
    }
}

