package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pzcraft.mc.TimeBridge;

/**
 * 1.20.1 version of ClockMixin, part 1: sleeping through the night sets the day time in ServerLevel.tick (ordinary time
 * goes through tickTime and is not reported). PZ's clock follows, with acknowledgement (TimeBridge).
 */
@Mixin(ServerLevel.class)
public abstract class ClockSleep120Mixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private void pzcraft$sleep(ServerLevel level, long time, Operation<Void> original) {
        long before = level.getDayTime();
        original.call(level, time);
        if (level.dimension() == Level.OVERWORLD && !TimeBridge.syncing()) TimeBridge.changed(level.getServer(), before, level.getDayTime());
    }
}
