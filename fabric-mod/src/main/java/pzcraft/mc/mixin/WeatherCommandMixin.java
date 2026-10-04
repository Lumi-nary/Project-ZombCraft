package pzcraft.mc.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.commands.WeatherCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.Session;
import pzcraft.mc.WeatherBridge;
import pzcraft.protocol.Weather;

/** Vanilla {@code /weather clear|rain|thunder} also sets PZ's weather (PZ draws the sky); see WeatherBridge. */
@Mixin(WeatherCommand.class)
public abstract class WeatherCommandMixin {
    @Inject(method = "setClear", at = @At("RETURN"))
    private static void pzcraft$clear(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> cir) {
        if (Session.pzConnected) WeatherBridge.vanilla(source.getServer(), Weather.CLEAR);
    }

    @Inject(method = "setRain", at = @At("RETURN"))
    private static void pzcraft$rain(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> cir) {
        if (Session.pzConnected) WeatherBridge.vanilla(source.getServer(), Weather.RAIN);
    }

    @Inject(method = "setThunder", at = @At("RETURN"))
    private static void pzcraft$thunder(CommandSourceStack source, int duration, CallbackInfoReturnable<Integer> cir) {
        if (Session.pzConnected) WeatherBridge.vanilla(source.getServer(), Weather.THUNDER);
    }
}

