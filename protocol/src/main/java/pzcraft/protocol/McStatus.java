package pzcraft.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Where Minecraft is in its start (Wire.MSG_MC_STATUS, MC -> PZ): still loading its resources (with the progress its own
 * loading screen shows), at its title screen, or with a world open; and the player's Minecraft name. PZ's menu shows a
 * loading screen from it and names a Steve character after the player. Sent as UTF-8 "phase=...;progress=...;user=...".
 */
public record McStatus(String phase, float progress, String user) {
    public static final String LOADING = "loading", TITLE = "title", WORLD = "world";

    public boolean loading() { return LOADING.equals(phase); }

    public byte[] encode() {
        String name = user == null ? "" : user.replaceAll("[;=]", "");
        return String.format(Locale.ROOT, "phase=%s;progress=%.3f;user=%s", phase, Math.max(0f, Math.min(1f, progress)), name)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Never throws: an unknown or broken message is a status of "" (nothing known). */
    public static McStatus decode(byte[] bytes) {
        String phase = "", user = "";
        float progress = 0f;
        for (String part : new String(bytes, StandardCharsets.UTF_8).split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq), value = part.substring(eq + 1);
            switch (key) {
                case "phase" -> phase = value;
                case "user" -> user = value;
                case "progress" -> {
                    try { progress = Math.max(0f, Math.min(1f, Float.parseFloat(value))); } catch (NumberFormatException e) { progress = 0f; }
                }
                default -> { }
            }
        }
        return new McStatus(phase, progress, user);
    }
}
