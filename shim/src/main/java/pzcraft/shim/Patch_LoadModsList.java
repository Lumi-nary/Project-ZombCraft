package pzcraft.shim;

import java.util.ArrayList;
import java.util.List;
import me.zed_0xff.zombie_buddy.Loader;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;

/** The missing hook: ZomboidFileSystem.loadMods(List) on PZ 42.21 (it was loadMods(ArrayList) before). */
@Patch(className = "zombie.ZomboidFileSystem", methodName = "loadMods")
public class Patch_LoadModsList {
    @OnEnter
    public static void enter(List<String> toLoad) {
        System.out.println("[PzCraftShim] ZomboidFileSystem.loadMods(" + toLoad.size() + " mods)");
        // Every caller on 42.21 passes a real ArrayList; copy defensively otherwise.
        ArrayList<String> mods = toLoad instanceof ArrayList<String> al ? al : new ArrayList<>(toLoad);
        Loader.loadMods(mods);
    }
}
