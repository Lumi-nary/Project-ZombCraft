package pzcraft.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import pzcraft.protocol.Weather;
import pzcraft.protocol.Wire;

/**
 * PZ draws the sky, rain, snow and fog, so its weather is the weather. Minecraft's {@code /weather clear|rain|thunder}
 * is passed on to PZ (WeatherCommandMixin), and {@code /weather} gains PZ's variations:
 * {@code drizzle, showers, heavy, storm, tropical, blizzard, snow [duration]} and {@code fog [density 0..1]}.
 * The weather PZ actually shows comes back about once a second and is mirrored into Minecraft's world (rain and thunder),
 * so Minecraft's rain rules (fires going out, crops, mobs, fishing, lightning) agree with PZ's sky. Minecraft's own
 * weather cycle is off; its rain is never drawn (LevelWeatherMixin), PZ draws the weather.
 */
public final class WeatherBridge {
    private static final int[] VARIATIONS = {Weather.DRIZZLE, Weather.SHOWERS, Weather.HEAVY, Weather.STORM, Weather.TROPICAL,
            Weather.BLIZZARD, Weather.SNOW};
    /** PZ's weather, from the link thread. */
    static volatile Weather.State pz;
    private static Boolean appliedRain, appliedThunder;
    /** After a command, PZ needs a moment to start the weather: do not let its old report undo the command. */
    private static long graceUntil;

    private WeatherBridge() {}

    static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(WeatherBridge::tick);
    }

    /** Merged into vanilla's /weather node (Brigadier adds children to an existing literal of the same name). */
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var weather = Commands.literal("weather").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (int kind : VARIATIONS) {
            weather.then(Commands.literal(Weather.name(kind))
                    .executes(c -> variation(c.getSource(), kind, -1))
                    .then(Commands.argument("duration", TimeArgument.time(1))
                            .executes(c -> variation(c.getSource(), kind, IntegerArgumentType.getInteger(c, "duration")))));
        }
        weather.then(Commands.literal("fog")
                .executes(c -> fog(c.getSource(), 0.6f))
                .then(Commands.argument("density", FloatArgumentType.floatArg(0f, 1f))
                        .executes(c -> fog(c.getSource(), FloatArgumentType.getFloat(c, "density")))));
        dispatcher.register(weather);
    }

    private static int variation(CommandSourceStack source, int kind, int duration) {
        MinecraftServer server = source.getServer();
        boolean thunder = kind == Weather.STORM || kind == Weather.TROPICAL;
        int ticks = duration > 0 ? duration
                : (thunder ? ServerLevel.THUNDER_DURATION : ServerLevel.RAIN_DURATION).sample(source.getLevel().getRandom());
        server.setWeatherParameters(0, ticks, true, thunder);
        toPz(kind, -1f, ticks);
        source.sendSuccess(() -> Component.literal("Set the weather to " + Weather.name(kind) + " (Project Zomboid)"), true);
        return ticks;
    }

    private static int fog(CommandSourceStack source, float density) {
        toPz(Weather.FOG, density, 0);
        source.sendSuccess(() -> Component.literal(density <= 0.01f ? "Cleared the fog" : "Set the fog to " + density), true);
        return Math.round(density * 100);
    }

    /** A vanilla /weather clear|rain|thunder has just run; its duration is in the server's weather data. */
    public static void vanilla(MinecraftServer server, int kind) {
        var data = server.getWeatherData();
        int ticks = kind == Weather.CLEAR ? data.getClearWeatherTime() : data.getRainTime();
        toPz(kind, -1f, kind == Weather.CLEAR ? 0 : ticks);
    }

    /** One game hour is 1000 ticks: Minecraft's clock follows PZ's. */
    private static void toPz(int kind, float intensity, int ticks) {
        graceUntil = System.currentTimeMillis() + 3000;
        appliedRain = appliedThunder = null;
        Session.send(Wire.MSG_WEATHER_COMMAND, new Weather.Command(kind, intensity, ticks / 1000f).encode());
        PzCraftClient.LOG.info("weather {} for {} ticks -> PZ", Weather.name(kind), ticks);
    }

    /** Server thread: mirror PZ's rain and thunder into Minecraft's world when they change. */
    private static void tick(MinecraftServer server) {
        Weather.State w = pz;
        if (w == null || !Session.pzConnected || System.currentTimeMillis() < graceUntil) return;
        boolean rain = w.raining(), thunder = rain && w.thunder();
        if (Boolean.valueOf(rain).equals(appliedRain) && Boolean.valueOf(thunder).equals(appliedThunder)) return;
        appliedRain = rain;
        appliedThunder = thunder;
        server.setWeatherParameters(rain ? 0 : 12000, rain ? 12000 : 0, rain, thunder);
        PzCraftClient.LOG.info("PZ weather: precipitation {} snow {} thunder {} -> Minecraft rain={} thunder={}",
                w.precipitation(), w.snow(), w.thunder(), rain, thunder);
    }
}

