package pzcraft.mc.mixin;

import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.TimeBridge;

@Mixin(ServerClockManager.class)
public abstract class ClockMixin {
    @Shadow private MinecraftServer server;
    @Unique private long pzcraft$before;
    @Inject(method = "modifyClock", at = @At("HEAD"))
    private void before(Holder<WorldClock> clock, Consumer<? super ServerClockManager.ServerClockInstance> action, CallbackInfo ci) {
        pzcraft$before = ((ServerClockManager) (Object) this).getInstance(clock).totalTicks();
    }
    @Inject(method = "modifyClock", at = @At("RETURN"))
    private void after(Holder<WorldClock> clock, Consumer<? super ServerClockManager.ServerClockInstance> action, CallbackInfo ci) {
        TimeBridge.changed(server, clock, pzcraft$before, ((ServerClockManager) (Object) this).getInstance(clock).totalTicks());
    }
}

