package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Weather between the two games. PZ draws the sky and the weather, so a Minecraft {@code /weather ...} command becomes a
 * {@link Command} that PZ carries out with its own weather system (stages, snow, fog). PZ reports what it is actually
 * doing as a {@link State}, which Minecraft mirrors into its world so rain-dependent Minecraft rules agree with the sky.
 */
public final class Weather {
    private Weather() {}

    /** Command kinds: Minecraft's own three, then PZ's weather variations. */
    public static final int CLEAR = 0, RAIN = 1, THUNDER = 2, DRIZZLE = 3, SHOWERS = 4, HEAVY = 5, STORM = 6, TROPICAL = 7,
            BLIZZARD = 8, SNOW = 9, FOG = 10;
    /** Command names, indexed by kind (also the /weather sub-command words). */
    public static final List<String> NAMES = List.of("clear", "rain", "thunder", "drizzle", "showers", "heavy", "storm",
            "tropical", "blizzard", "snow", "fog");

    public static String name(int kind) { return kind >= 0 && kind < NAMES.size() ? NAMES.get(kind) : "?"; }

    /**
     * MC -> PZ: set the weather. {@code intensity} 0..1 overrides the variation's usual strength (fog density for FOG),
     * negative = the variation's default. {@code hours} is in game hours; 0 or less = the variation's usual length.
     */
    public record Command(int kind, float intensity, float hours) {
        public byte[] encode() {
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(kind).putFloat(intensity).putFloat(hours).array();
        }

        public static Command decode(byte[] payload) {
            ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            return new Command(b.getInt(), b.getFloat(), b.getFloat());
        }
    }

    /** PZ -> MC: the weather PZ is showing. Precipitation, fog, wind and clouds 0..1. */
    public record State(float precipitation, boolean snow, boolean thunder, float fog, float wind, float clouds) {
        public boolean raining() { return precipitation > 0.05f; }

        public byte[] encode() {
            return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putFloat(precipitation).putInt(snow ? 1 : 0)
                    .putInt(thunder ? 1 : 0).putFloat(fog).putFloat(wind).putFloat(clouds).array();
        }

        public static State decode(byte[] payload) {
            ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            return new State(b.getFloat(), b.getInt() != 0, b.getInt() != 0, b.getFloat(), b.getFloat(), b.getFloat());
        }
    }
}
