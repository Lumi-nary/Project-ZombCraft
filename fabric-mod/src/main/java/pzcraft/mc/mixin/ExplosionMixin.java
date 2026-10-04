package pzcraft.mc.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.ExplosionBridge;

/** Every explosion (TNT, creepers, beds, anything) is also traced through PZ's walls, doors, windows and furniture. */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private float radius;
    @Shadow @Final private boolean fire;

    @Inject(method = "explode", at = @At("HEAD"))
    private void pzcraft$tracePz(CallbackInfoReturnable<Integer> cir) {
        pzcraft.mc.GroundBridge.beforeExplosion(this.level, this.center, this.radius);
        ExplosionBridge.before(this.level, this.center, this.radius, this.fire);
    }
}

