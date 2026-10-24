package pzcraft.mc;

import com.google.gson.JsonObject;

/**
 * Generic motion for PZ weapon meshes: local PZ hand tracks for reload/load/rack/unload, and original procedural motion
 * for drawing, recoil and inspection. Timing comes from
 * PZ's answers (the reload's real length, a round's loop, the rack stroke), so what is seen matches what PZ is doing. The pose is
 * an offset about the grip (the mesh origin): translation in blocks and rotation in degrees, applied by the mesh renderer.
 * Numbers can be changed live as {@code gunanim.*} in the tuning file.
 */
final class GunMeshAnim {
    private record Clip(String name, long start, double seconds, double per) {}
    private static volatile Clip base, kick, queued;
    private static volatile long queuedAt;
    private static String profile = "boltaction";

    private GunMeshAnim() {}

    static void weapon(String fullType) { profile = PzWeaponMotion.profile(fullType); }

    static float[] hands() {
        Clip action = base;
        if (action == null) return new float[6];
        double t = (System.nanoTime() - action.start) / 1e9;
        double u = action.name.equals("load") ? (t % action.per) / action.per : t / action.seconds;
        float[] pose = u <= 1 ? PzWeaponMotion.hands(profile, action.name, u) : null;
        return pose == null ? new float[6] : pose;
    }

    /** A shot: the kick, and the rack stroke that follows it when PZ says the gun is worked after every shot. */
    static void fired(JsonObject result) {
        long now = System.nanoTime();
        kick = new Clip("kick", now, .32, 0);
        if (result != null && result.has("rackSeconds")) {
            double lead = result.has("rackLead") ? result.get("rackLead").getAsDouble() : .3;
            queued = new Clip("rack", 0, result.get("rackSeconds").getAsDouble(), 0);
            queuedAt = now + (long) (lead * 1e9);
        }
    }

    /** An answer from PZ with a clip name ("reload", "reloadempty", "load", "rack", "inspect", "inspectempty"). */
    static void clip(String name, JsonObject result) {
        // The native completion repeats its clip name for rigged controllers; it is not a second rack.
        if (name.equals("rack") && result.has("status") && result.get("status").getAsString().equals("racked")) return;
        long now = System.nanoTime();
        double seconds = result.has("seconds") ? result.get("seconds").getAsDouble() : 0;
        double per = result.has("per") ? result.get("per").getAsDouble() : 0;
        queued = null;
        switch (name) {
            case "reload", "reloadempty", "unload" -> base = new Clip(name, now, seconds > 0 ? seconds : 1.7, 0);
            case "load" -> base = new Clip("load", now, 0, per > 0 ? per : .9);          // until the loading stops
            case "rack" -> base = new Clip("rack", now, seconds > 0 ? seconds : .85, 0);
            case "inspect", "inspectempty" -> base = new Clip("inspect", now, seconds > 0 ? seconds : 4.0, 0);
            default -> { }
        }
    }

    /** Loading ended, an action was cancelled, or the gun changed hands. */
    static void stop() { base = null; queued = null; kick = null; }

    /** PZ reports its new rack before the preceding load's completion. Keep that new action. */
    static void loaded() {
        Clip current = base;
        if (current == null || !current.name.equals("rack")) stop();
    }

    static void draw() { base = new Clip("draw", System.nanoTime(), .45, 0); kick = null; queued = null; }

    private static double smooth(double x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

    /** 0 -> 1 over [a, b], held to c, back to 0 over [c, d]. */
    private static double bump(double u, double a, double b, double c, double d) {
        return smooth((u - a) / (b - a)) * (1 - smooth((u - c) / (d - c)));
    }

    /** {x, y, z, rx, ry, rz} of the current offset, or null when the gun is at rest. */
    static float[] pose() {
        long now = System.nanoTime();
        Clip q = queued;
        if (q != null && now >= queuedAt) { base = new Clip(q.name, queuedAt, q.seconds, 0); queued = null; }
        float[] p = new float[6];
        boolean any = false;
        double scale = Tuning.get("gunanim.scale", 1.0);
        Clip b = base;
        if (b != null) {
            double t = (now - b.start) / 1e9;
            double progress = b.name.equals("load") ? (t % b.per) / b.per : t / b.seconds;
            float[] nativePose = progress <= 1 ? PzWeaponMotion.sample(profile, b.name, progress) : null;
            if (nativePose != null) {
                for (int i = 0; i < 6; i++) p[i] += nativePose[i];
                any = true;
            } else switch (b.name) {
                case "draw" -> {
                    double d = 1 - smooth(t / b.seconds);
                    if (t < b.seconds) { p[1] += -.35 * d; p[3] += 35 * d; any = true; }
                    else base = null;
                }
                case "reload", "reloadempty", "unload" -> {
                    double u = t / b.seconds;
                    if (u < 1.05) {
                        double down = bump(u, 0, .22, .78, 1.0);
                        double shake = Math.sin(u * Math.PI * 8) * bump(u, .25, .35, .5, .6) * .012;
                        p[1] += -.2 * down + shake; p[2] += .03 * down; p[3] += 14 * down; p[5] += 24 * down;
                        if (b.name.equals("reloadempty")) p[2] += -.05 * bump(u, .75, .82, .9, 1.0);
                        any = true;
                    } else base = null;
                }
                case "load" -> {
                    // each round: down and a turn, up again; goes on until PZ says the loading ended
                    double phase = (t % b.per) / b.per;
                    double dip = Math.sin(Math.PI * phase);
                    p[1] += -.1 * dip; p[3] += 12 * dip; p[5] += 30 * dip;
                    any = true;
                    if (t > 90) base = null;
                }
                case "rack" -> {
                    double u = t / b.seconds;
                    if (u < 1.0) {
                        double stroke = Math.sin(Math.PI * u);
                        p[2] += .07 * stroke; p[5] += -18 * stroke; p[3] += -4 * stroke;
                        any = true;
                    } else base = null;
                }
                case "inspect" -> {
                    double u = t / b.seconds;
                    if (u < 1.0) {
                        double raise = bump(u, 0, .12, .88, 1.0);
                        double turn = bump(u, .12, .32, .5, .66), back = bump(u, .62, .76, .82, .9);
                        p[1] += .05 * raise; p[3] += -12 * raise;
                        p[4] += 55 * turn - 70 * back; p[5] += 65 * turn - 40 * back;
                        any = true;
                    } else base = null;
                }
                default -> base = null;
            }
        }
        Clip k = kick;
        if (k != null) {
            double t = (now - k.start) / 1e9;
            if (t < k.seconds) {
                double kk = t < .05 ? t / .05 : Math.exp(-(t - .05) * 14);
                p[2] += .055 * kk; p[3] += -6 * kk;
                any = true;
            } else kick = null;
        }
        if (GunFeel.sprinting()) { p[1] += -.08; p[3] += 24; any = true; }
        if (!any) return null;
        for (int i = 0; i < 6; i++) p[i] *= scale;
        return p;
    }
}
