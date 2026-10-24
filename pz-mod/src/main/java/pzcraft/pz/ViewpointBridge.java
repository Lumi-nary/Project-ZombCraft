package pzcraft.pz;

import java.lang.reflect.Field;

/**
 * Reads and writes Viewpoint's look state by reflection. Viewpoint is a closed-source mod that changes often, so
 * everything here fails soft: if a field is missing we log once and report "not available" instead of crashing PZ.
 *
 * What we rely on (viewpoint.input.Look): public static volatile float yaw/pitch (radians; forward is (cos yaw, sin yaw)
 * in PZ world axes, positive pitch looks up) and boolean captured (mouse is captured, i.e. gameplay input).
 */
public final class ViewpointBridge {
    private static boolean resolved;
    private static Field yaw, pitch, captured, wantCapture, viewEnabled;

    private ViewpointBridge() {}

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> look = Class.forName("viewpoint.input.Look");
            yaw = look.getField("yaw");
            pitch = look.getField("pitch");
            captured = look.getField("captured");
            wantCapture = look.getField("wantCapture");
        } catch (Throwable t) {
            Log.info("Viewpoint's Look class/fields not found (" + t + "); look and input forwarding disabled");
            yaw = pitch = captured = wantCapture = null;
        }
        try {
            viewEnabled = Class.forName("viewpoint.core.View").getField("enabled");
        } catch (Throwable t) {
            viewEnabled = null;
        }
    }

    static boolean available() {
        resolve();
        return yaw != null;
    }

    /** First-person 3D view is switched on (Viewpoint's O key). */
    static boolean viewEnabled() {
        resolve();
        try {
            return viewEnabled != null && viewEnabled.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The mouse is captured by Viewpoint, i.e. the player is in gameplay and not in a menu. */
    static boolean captured() {
        resolve();
        try {
            return captured != null && captured.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    static float yaw() {
        resolve();
        try {
            return yaw != null ? yaw.getFloat(null) : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    static float pitch() {
        resolve();
        try {
            return pitch != null ? pitch.getFloat(null) : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static java.lang.reflect.Method fovMethod;
    /** Gate Viewpoint's capture request on its window thread while a Minecraft screen owns the cursor. */
    public static void releaseForGui() {
        resolve();
        try { if (wantCapture != null) wantCapture.setBoolean(null, false); } catch (Throwable ignored) {}
    }
    private static boolean fovResolved;

    private static boolean lootResolved, lootOwned, lootSaved = true;
    private static java.lang.reflect.Method lootSet;
    private static java.lang.reflect.Field lootEnabled;
    private static int lootFrames;

    /**
     * Viewpoint's loot/interact panel (it opens by itself on what the view aims at) stays off while Steve plays: Minecraft's
     * keys and our G menu do that job. Switched through Viewpoint's own public switch ({@code Viewpoint.Loot.setEnabled},
     * the one behind its Mod Options toggle); what Viewpoint had is given back when Steve stops. Game thread, once per frame.
     */
    static void lootPanel(boolean steve) {
        if (!lootResolved) {
            lootResolved = true;
            try {
                lootSet = Class.forName("viewpoint.LootLua").getMethod("setEnabled", boolean.class);
                lootEnabled = Class.forName("viewpoint.interact.LootMenu").getDeclaredField("enabled");
                lootEnabled.setAccessible(true);
            } catch (Throwable t) {
                Log.info("Viewpoint's loot panel switch not found (" + t + "); it stays as Viewpoint has it");
                lootSet = null;
            }
        }
        if (lootSet == null) return;
        try {
            if (steve) {
                if (lootOwned && ++lootFrames < 30) return;   // its Mod Options can switch it back on: look again twice a second
                lootFrames = 0;
                boolean on = lootEnabled.getBoolean(null);
                if (!lootOwned) lootSaved = on;
                if (on) { if (lootOwned) lootSaved = true; lootSet.invoke(null, false); }
                lootOwned = true;
            } else if (lootOwned) {
                lootOwned = false;
                lootSet.invoke(null, lootSaved);
            }
        } catch (Throwable t) {
            lootSet = null;
            Log.info("Viewpoint's loot panel switch failed (" + t + ")");
        }
    }

    private static boolean pickResolved;
    private static java.lang.reflect.Method pickOn;

    /** Viewpoint picks what lies under the free cursor in 3D (PZ's right-click then sees it); null when this Viewpoint cannot say. */
    static Boolean mousePickOn() {
        if (!pickResolved) {
            pickResolved = true;
            try {
                pickOn = Class.forName("viewpoint.interact.MouseTargets").getMethod("on");
            } catch (Throwable t) {
                pickOn = null;
            }
        }
        try {
            return pickOn == null ? null : (Boolean) pickOn.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Viewpoint's vertical field of view in degrees for the first-person view, or 0 if unavailable. */
    static float fovDegrees() {
        if (!fovResolved) {
            fovResolved = true;
            try {
                fovMethod = Class.forName("viewpoint.core.View").getMethod("fovY", boolean.class);
            } catch (Throwable t) {
                fovMethod = null;
            }
        }
        try {
            return fovMethod != null ? (float) Math.toDegrees((Float) fovMethod.invoke(null, false)) : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    // ---- camera frame (Viewpoint's own record of where the eye is) ----

    private static boolean frameResolved;
    private static java.lang.reflect.Field framesField;
    private static java.lang.reflect.Field fNumber, fCamX, fCamY, fCamZ, fEyeX, fEyeY, fEyeZ, fYaw, fPitch;

    private static void resolveFrames() {
        if (frameResolved) return;
        frameResolved = true;
        try {
            Class<?> fp = Class.forName("viewpoint.FP");
            framesField = fp.getDeclaredField("frames");
            framesField.setAccessible(true);
            Class<?> frame = Class.forName("viewpoint.core.Frame");
            fNumber = frame.getField("number");
            fCamX = frame.getField("camX"); fCamY = frame.getField("camY"); fCamZ = frame.getField("camZ");
            fEyeX = frame.getField("eyeX"); fEyeY = frame.getField("eyeY"); fEyeZ = frame.getField("eyeZ");
            fYaw = frame.getField("viewYaw"); fPitch = frame.getField("viewPitch");
        } catch (Throwable t) {
            Log.info("Viewpoint camera frames not readable (" + t + "); world layer alignment unavailable");
            framesField = null;
        }
    }

    /**
     * Viewpoint's newest camera frame as {camX, camY, camZ, eyeX, eyeY, eyeZ, yaw, pitch} (its own units: cam* are PZ
     * world coordinates, eye* are scene offsets from the camera), or null.
     */
    static float[] latestFrame() {
        resolveFrames();
        if (framesField == null) return null;
        try {
            Object[] frames = (Object[]) framesField.get(null);
            Object best = null;
            long bestN = Long.MIN_VALUE;
            for (Object f : frames) {
                long n = fNumber.getLong(f);
                if (n > bestN) { bestN = n; best = f; }
            }
            if (best == null) return null;
            return new float[] {fCamX.getFloat(best), fCamY.getFloat(best), fCamZ.getFloat(best),
                    fEyeX.getFloat(best), fEyeY.getFloat(best), fEyeZ.getFloat(best), fYaw.getFloat(best), fPitch.getFloat(best)};
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field thirdPersonField;
    private static boolean thirdPersonResolved;

    /** Viewpoint's third-person camera (Shift+O) is on. */
    static boolean thirdPerson() {
        resolveThirdPerson();
        try {
            return thirdPersonField != null && thirdPersonField.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Switch Viewpoint's 3D view on the way its O key does (FP.toggle), for the control API: launching and testing then
     * need no key press, so nothing has to bring PZ's window to the front. Returns false if Viewpoint changed shape.
     */
    static boolean enableView() { return enableView("through the control API"); }

    static boolean enableView(String why) {
        try {
            Class<?> view = Class.forName("viewpoint.core.View");
            if (view.getField("enabled").getBoolean(null)) return true;
            zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
            if (p == null) return false;
            Class<?> fp = Class.forName("viewpoint.FP");
            view.getField("enabled").setBoolean(null, true);
            java.lang.reflect.Field cursor = fp.getDeclaredField("cursorMode");
            cursor.setAccessible(true);
            cursor.setBoolean(null, false);
            java.lang.reflect.Method reset = fp.getDeclaredMethod("resetCaches");
            reset.setAccessible(true);
            reset.invoke(null);
            Class<?> look = Class.forName("viewpoint.input.Look");
            look.getField("yaw").setFloat(null, p.getDirectionAngleRadians());
            look.getField("pitch").setFloat(null, 0f);
            Class.forName("viewpoint.Compat").getMethod("check").invoke(null);
            java.lang.reflect.Field at = fp.getDeclaredField("enabledAt");
            at.setAccessible(true);
            at.setLong(null, System.nanoTime());
            Log.info("Viewpoint 3D switched on " + why);
            return true;
        } catch (Throwable t) {
            Log.error("could not switch Viewpoint's 3D view on", t);
            return false;
        }
    }

    private static volatile java.util.Set<Object> viewKeys;

    /**
     * Viewpoint's first-person and third-person keys, for a character of a kind (CharacterType: first person only). Public:
     * ZombieBuddy inlines the advice that calls this into Viewpoint's KeyBind.pressed.
     */
    public static boolean lockedViewKey(Object bind) {
        if (!CharacterType.locksView()) return false;
        java.util.Set<Object> keys = viewKeys;
        if (keys == null) {
            keys = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            try {
                Class<?> k = Class.forName("viewpoint.platform.Keys");
                keys.add(k.getField("FIRST_PERSON").get(null));
                keys.add(k.getField("THIRD_PERSON").get(null));
            } catch (Throwable t) {
                Log.error("Viewpoint's view keys not found: first person cannot be locked", t);
            }
            viewKeys = keys;
        }
        return keys.contains(bind);
    }

    // ---- Viewpoint's setup wizard (0.1.6+): first person waits until it is finished ----

    private static java.lang.reflect.Method setupBlocks, setupShow, setupShown;
    private static boolean setupResolved;

    private static void resolveSetup() {
        if (setupResolved) return;
        setupResolved = true;
        try {
            Class<?> onboarding = Class.forName("viewpoint.platform.Onboarding");
            setupBlocks = onboarding.getMethod("blocks");
            setupShow = onboarding.getMethod("show");
            setupShown = onboarding.getMethod("shown");
        } catch (Throwable t) {
            setupBlocks = setupShow = setupShown = null;     // an older Viewpoint has no setup wizard
        }
    }

    /** First person would not start yet: Viewpoint's setup is not finished (or it waits for a restart). */
    static boolean setupBlocks() {
        resolveSetup();
        try { return setupBlocks != null && Boolean.TRUE.equals(setupBlocks.invoke(null)); } catch (Throwable t) { return false; }
    }

    /** Viewpoint's setup window is open (PzCraft's screens stay away meanwhile). */
    static boolean setupShown() {
        resolveSetup();
        try { return setupShown != null && Boolean.TRUE.equals(setupShown.invoke(null)); } catch (Throwable t) { return false; }
    }

    /** Opens Viewpoint's setup window, as its first-person key does when the setup is not finished. */
    static void openSetup() {
        resolveSetup();
        try { if (setupShow != null) setupShow.invoke(null); } catch (Throwable t) { Log.error("could not open Viewpoint's setup", t); }
    }

    static boolean disableView() {
        resolve();
        try { if (viewEnabled == null) return false; viewEnabled.setBoolean(null, false); return true; }
        catch (IllegalAccessException e) { return false; }
    }

    /** Switch Viewpoint's third-person camera as its key would (tests: the control API cannot press keys in PZ). Outside
     * Steve mode Viewpoint sets it back from its own setting on the next frame (ThirdPerson.poll); while Steve drives that is skipped. */
    static boolean setThirdPerson(boolean on) {
        resolveThirdPerson();
        try {
            if (thirdPersonField == null) return false;
            thirdPersonField.setBoolean(null, on);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resolveThirdPerson() {
        if (thirdPersonResolved) return;
        thirdPersonResolved = true;
        try {
            thirdPersonField = Class.forName("viewpoint.core.View").getField("thirdPerson");    // Viewpoint 0.1.6+ (was input.ThirdPerson.active)
        } catch (Throwable t) {
            thirdPersonField = null;
        }
    }

    static void setLook(float yawRad, float pitchRad) {
        resolve();
        try {
            if (yaw != null) {
                yaw.setFloat(null, yawRad);
                pitch.setFloat(null, pitchRad);
            }
        } catch (Throwable t) {
            // ignore: best effort
        }
    }
}
