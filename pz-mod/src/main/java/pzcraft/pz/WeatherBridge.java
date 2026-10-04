package pzcraft.pz;

import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Weather;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.WeatherPeriod;

/**
 * PZ draws the sky, so PZ's weather is the weather. Minecraft's {@code /weather} commands arrive here and are carried
 * out with PZ's own weather system: a weather period of the requested kind (drizzle, showers, rain, heavy rain, a
 * thunderstorm, a tropical storm, a blizzard) for the requested time, with PZ's clouds, wind, darkening and thunder.
 * The precipitation (and snow, for snow and blizzards) is set at once for that time, as Minecraft's weather starts at
 * once; fog has its own density until cleared. Once a second the weather PZ shows goes back to Minecraft.
 *
 * <p>Those settings are PZ "admin" overrides, which PZ saves with the world, so when they run out is saved with the
 * world too (GameTime mod data) and honoured after a reload.
 */
final class WeatherBridge {
    private static final String PRECIP_UNTIL = "PzCraftWeatherPrecipUntil", SNOW_UNTIL = "PzCraftWeatherSnowUntil",
            FOG_SET = "PzCraftWeatherFog";
    private static final ConcurrentLinkedQueue<Weather.Command> commands = new ConcurrentLinkedQueue<>();
    private static long lastState;
    private static volatile String last = "none";

    private WeatherBridge() {}

    /** Link thread. */
    static void receive(Weather.Command c) {
        if (commands.size() < 32) commands.add(c);
    }

    static String lastCommand() { return last; }

    /** Game thread, every frame. */
    static void tick() {
        if (zombie.characters.IsoPlayer.getInstance() == null) return; // menus: no world, no weather
        ClimateManager cm = ClimateManager.getInstance();
        GameTime gt = GameTime.getInstance();
        if (cm == null || gt == null) return;
        Weather.Command c;
        while ((c = commands.poll()) != null) {
            try {
                apply(cm, gt, c);
            } catch (RuntimeException e) {
                Log.error("weather command " + Weather.name(c.kind()) + " failed", e);
            }
        }
        expire(cm, gt);
        long now = System.currentTimeMillis();
        if (now - lastState >= 1000 && LinkService.connected()) {
            lastState = now;
            LinkService.send(pzcraft.protocol.Wire.MSG_WEATHER_STATE, state(cm).encode());
        }
    }

    static Weather.State state(ClimateManager cm) {
        boolean thunder = cm.getIsThunderStorming() || cm.getThunderStorm().HasActiveThunderClouds();
        return new Weather.State(cm.getPrecipitationIntensity(), cm.getPrecipitationIsSnow(), thunder,
                cm.getFogIntensity(), cm.getWindIntensity(), cm.getCloudIntensity());
    }

    private static void apply(ClimateManager cm, GameTime gt, Weather.Command c) {
        int kind = c.kind();
        KahluaTable data = gt.getModData();
        last = Weather.name(kind) + (c.intensity() >= 0 ? " " + c.intensity() : "") + (c.hours() > 0 ? " for " + c.hours() + " h" : "");
        Log.info("weather from Minecraft: " + last);
        if (kind == Weather.CLEAR) {
            cm.stopWeatherAndThunder();
            precipitation(cm).setEnableAdmin(false);
            snow(cm).setEnableAdmin(false);
            fog(cm).setEnableAdmin(false);
            data.rawset(PRECIP_UNTIL, null);
            data.rawset(SNOW_UNTIL, null);
            data.rawset(FOG_SET, null);
            return;
        }
        if (kind == Weather.FOG) {
            float density = c.intensity() >= 0 ? Math.min(1f, c.intensity()) : 0.6f;
            if (density <= 0.01f) {
                fog(cm).setEnableAdmin(false);
                data.rawset(FOG_SET, null);
            } else {
                fog(cm).setAdminValue(density);
                fog(cm).setEnableAdmin(true);
                data.rawset(FOG_SET, Double.valueOf(density));
            }
            return;
        }
        int stage;
        float precipitation;
        switch (kind) {
            case Weather.DRIZZLE -> { stage = WeatherPeriod.STAGE_DRIZZLE; precipitation = 0.15f; }
            case Weather.SHOWERS -> { stage = WeatherPeriod.STAGE_SHOWERS; precipitation = 0.3f; }
            case Weather.HEAVY -> { stage = WeatherPeriod.STAGE_HEAVY_PRECIP; precipitation = 0.8f; }
            case Weather.THUNDER, Weather.STORM -> { stage = WeatherPeriod.STAGE_STORM; precipitation = 0.75f; }
            case Weather.TROPICAL -> { stage = WeatherPeriod.STAGE_TROPICAL_STORM; precipitation = 1f; }
            case Weather.BLIZZARD -> { stage = WeatherPeriod.STAGE_BLIZZARD; precipitation = 0.9f; }
            case Weather.SNOW -> { stage = WeatherPeriod.STAGE_MODERATE; precipitation = 0.45f; }
            default -> { stage = WeatherPeriod.STAGE_MODERATE; precipitation = 0.5f; } // rain
        }
        if (c.intensity() >= 0) precipitation = Math.min(1f, c.intensity());
        float hours = c.hours() > 0 ? c.hours() : (kind == Weather.THUNDER || kind == Weather.STORM || kind == Weather.TROPICAL ? 6f : 12f);
        double until = gt.getWorldAgeHours() + hours;
        // PZ starts a new weather period only when none is running, so the current one makes way.
        cm.stopWeatherAndThunder();
        cm.triggerCustomWeatherStage(stage, hours);
        precipitation(cm).setAdminValue(precipitation);
        precipitation(cm).setEnableAdmin(true);
        data.rawset(PRECIP_UNTIL, Double.valueOf(until));
        if (kind == Weather.THUNDER || kind == Weather.STORM || kind == Weather.TROPICAL) {
            // The storm stage starts its thunder cloud only after the period's hour of build-up; Minecraft's thunder
            // is immediate, so a cloud over the player starts now (PZ's own storm values).
            boolean tropical = kind == Weather.TROPICAL;
            cm.getThunderStorm().startThunderCloud(tropical ? 1f : 0.95f, (float) (Math.random() * 360.0), tropical ? 15000f : 7600f,
                    0.95f, tropical ? 0.8f : 0.57f, hours, true, 0f);
        }
        boolean snowing = kind == Weather.SNOW || kind == Weather.BLIZZARD;
        if (snowing) {
            snow(cm).setAdminValue(true);
            snow(cm).setEnableAdmin(true);
            data.rawset(SNOW_UNTIL, Double.valueOf(until));
        } else if (data.rawget(SNOW_UNTIL) != null) {
            snow(cm).setEnableAdmin(false); // a previous snow command; this one falls as PZ's temperature decides
            data.rawset(SNOW_UNTIL, null);
        }
    }

    /** Releases the settings a command made once its time is up (also after a save and reload). */
    private static void expire(ClimateManager cm, GameTime gt) {
        KahluaTable data = gt.getModData();
        if (data == null) return;
        double age = gt.getWorldAgeHours();
        if (data.rawget(PRECIP_UNTIL) instanceof Double until && age >= until) {
            precipitation(cm).setEnableAdmin(false);
            data.rawset(PRECIP_UNTIL, null);
            Log.info("weather command ran out: PZ's own weather again");
        }
        if (data.rawget(SNOW_UNTIL) instanceof Double until && age >= until) {
            snow(cm).setEnableAdmin(false);
            data.rawset(SNOW_UNTIL, null);
        }
    }

    private static ClimateManager.ClimateFloat precipitation(ClimateManager cm) {
        return cm.getClimateFloat(ClimateManager.FLOAT_PRECIPITATION_INTENSITY);
    }

    private static ClimateManager.ClimateFloat fog(ClimateManager cm) {
        return cm.getClimateFloat(ClimateManager.FLOAT_FOG_INTENSITY);
    }

    private static ClimateManager.ClimateBool snow(ClimateManager cm) {
        return cm.getClimateBool(ClimateManager.BOOL_IS_SNOW);
    }
}

