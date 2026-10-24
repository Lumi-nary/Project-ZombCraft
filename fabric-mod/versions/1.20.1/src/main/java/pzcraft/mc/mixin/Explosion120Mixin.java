package pzcraft.mc.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.ExplosionBridge;
import pzcraft.mc.GroundBridge;

/** 1.20.1 version of ExplosionMixin (1.20.1 has no ServerExplosion): PZ's ground and objects see the blast first. */
@Mixin(Explosion.class)
public abstract class Explosion120Mixin {
    @Shadow @Final private Level level;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final private float radius;
    @Shadow @Final private boolean fire;

    @Inject(method = "explode", at = @At("HEAD"))
    private void pzcraft$tracePz(CallbackInfo ci) {
        if (!(level instanceof ServerLevel server)) return;
        Vec3 center = new Vec3(x, y, z);
        GroundBridge.beforeExplosion(server, center, radius);
        ExplosionBridge.before(server, center, radius, fire);
    }
}
