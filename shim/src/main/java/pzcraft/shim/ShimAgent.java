package pzcraft.shim;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stop-gap for ZombieBuddy 2.3.x on Project Zomboid 42.21, where no Java mod loads at all because
 * ZomboidFileSystem.loadMods(ArrayList) became loadMods(List) (ZombieBuddy issues #53/#59, PR #56).
 *
 * This is a second, tiny agent that rides on ZombieBuddy's own patch engine: it registers one advice on the new
 * signature that calls ZombieBuddy's own Loader.loadMods, exactly what upstream's fix does. It changes no files, and
 * ZombieBuddy still shows its normal approval dialog for every Java mod jar (policy is ZombieBuddy's, not ours).
 * It switches itself off if the installed ZombieBuddy already handles loadMods(List).
 *
 * Must be listed AFTER ZombieBuddy's own agent on the command line, so ZombieBuddy is initialised first.
 */
public final class ShimAgent {
    static boolean active;

    private ShimAgent() {}

    public static void premain(String args, Instrumentation inst) {
        try {
            if (upstreamAlreadyFixed()) {
                System.out.println("[PzCraftShim] ZombieBuddy already handles loadMods(List); shim not needed");
                return;
            }
            Path self = Paths.get(ShimAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader");
            Class<?> phase = Class.forName("me.zed_0xff.zombie_buddy.Loader$Phase");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object premain = Enum.valueOf((Class<Enum>) phase.asSubclass(Enum.class), "PREMAIN");
            Method loadJar = loader.getDeclaredMethod("loadJar", Path.class, String.class, String.class, phase);
            loadJar.setAccessible(true);
            Object ok = loadJar.invoke(null, self, "pzcraft.shim", null, premain);
            active = Boolean.TRUE.equals(ok);
            System.out.println("[PzCraftShim] registered loadMods(List) fix for ZombieBuddy on PZ 42.21: " + ok);
        } catch (Throwable t) {
            System.out.println("[PzCraftShim] could not install: " + t);
            t.printStackTrace(System.out);
        }
    }

    /** True if ZombieBuddy's own loadMods advice already takes a List (a fixed release). */
    private static boolean upstreamAlreadyFixed() {
        try {
            Class<?> c = Class.forName("me.zed_0xff.zombie_buddy.patches.Patch_ZomboidFileSystem$Patch_loadMods2");
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("enter") && m.getParameterCount() > 0) {
                    return m.getParameterTypes()[0] == java.util.List.class;
                }
            }
        } catch (Throwable t) {
            // class missing or renamed: assume not fixed
        }
        return false;
    }
}
