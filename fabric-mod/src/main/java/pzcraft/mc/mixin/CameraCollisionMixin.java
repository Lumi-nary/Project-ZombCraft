package pzcraft.mc.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;
import pzcraft.mc.Session;

/** Vanilla's eight boom rays also see native PZ collision, including vehicles. */
@Mixin(Camera.class)
public abstract class CameraCollisionMixin {
    @Shadow private Vec3 position;
    @Shadow private Vector3f forwards;

    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
    private void pzcraft$clipBoom(float requested, CallbackInfoReturnable<Float> cir) {
        if (!Session.pzConnected || !Session.steveDrives) return;
        float distance = cir.getReturnValue();
        Vec3 direction = new Vec3(-forwards.x, -forwards.y, -forwards.z);
        for (int i = 0; i < 8; i++) {
            Vec3 from = position.add(((i & 1) * 2 - 1) * .1, ((i >> 1 & 1) * 2 - 1) * .1, ((i >> 2 & 1) * 2 - 1) * .1);
            var hit = CollisionField.raycast(from, direction, distance);
            if (hit != null) distance = Math.min(distance, (float) hit.point().distanceTo(position));
        }
        cir.setReturnValue(distance);
    }
}

