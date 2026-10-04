package pzcraft.mc.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
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
 * Lets Steve aim at PZ's floors and walls as if they were blocks. A ray that would otherwise hit nothing (or something
 * farther away) is tested against the PZ collision boxes and reported as a block hit on the air voxel in front of the
 * face, so using a block item there places it right on the floor or against the wall.
 */
@Mixin(Entity.class)
public abstract class EntityPickMixin {
    @Inject(method = "pick(DFZ)Lnet/minecraft/world/phys/HitResult;", at = @At("RETURN"), cancellable = true)
    private void pzcraft$pickPzGeometry(double range, float partialTicks, boolean withLiquids, CallbackInfoReturnable<HitResult> cir) {
        if (!Session.pzConnected || !((Object) this instanceof Player)) return;
        Entity self = (Entity) (Object) this;
        Vec3 from = self.getEyePosition(partialTicks);
        Vec3 dir = self.getViewVector(partialTicks);
        HitResult current = cir.getReturnValue();
        double limit = current.getType() == HitResult.Type.MISS ? range : Math.min(range, from.distanceTo(current.getLocation()));
        CollisionField.Hit hit = CollisionField.raycast(from, dir, limit);
        if (hit != null) {
            // Aiming at PZ's floor over real ground: the target is the ground block under it, so it can be dug.
            net.minecraft.core.BlockPos air = hit.air();
            if (hit.face() == net.minecraft.core.Direction.UP && air.getY() == 0 && pzcraft.mc.GroundBridge.isGroundColumn(air.getX(), air.getZ())
                    && !self.level().getBlockState(air.below()).isAir()) {
                cir.setReturnValue(new BlockHitResult(hit.point(), hit.face(), air.below(), false));
                return;
            }
            cir.setReturnValue(new BlockHitResult(hit.point(), hit.face(), hit.air(), false));
        }
    }
}

