package pzcraft.mc.mixin;

import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.FlywheelCompat120;

/** Flywheel (optional, inside Create 6): no GPU visuals while PZ draws the world. See FlywheelCompat120. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class FlywheelVisuals120Mixin {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, require = 0)
    private static void pzcraft$nativeWorld(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (FlywheelCompat120.suppressed()) cir.setReturnValue(false);
    }
}
