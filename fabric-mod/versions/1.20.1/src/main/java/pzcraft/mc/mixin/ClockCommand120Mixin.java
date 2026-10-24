package pzcraft.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.commands.TimeCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import pzcraft.mc.TimeBridge;

/** 1.20.1 version of ClockMixin, part 2: /time set and /time add (they set every level; the overworld is reported). */
@Mixin(TimeCommand.class)
public abstract class ClockCommand120Mixin {
    @WrapOperation(method = {"setTime", "addTime"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private static void pzcraft$command(ServerLevel level, long time, Operation<Void> original) {
        long before = level.getDayTime();
        original.call(level, time);
        if (level.dimension() == Level.OVERWORLD) TimeBridge.changed(level.getServer(), before, level.getDayTime());
    }
}
