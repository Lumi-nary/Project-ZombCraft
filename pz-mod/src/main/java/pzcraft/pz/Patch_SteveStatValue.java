package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.Return;
import zombie.characters.Stats;
import zombie.characters.CharacterStat;
@Patch(className = "zombie.characters.Stats", methodName = "get")
public class Patch_SteveStatValue {
    @OnExit public static void exit(@This Stats stats, @Argument(0) CharacterStat stat, @Return(readOnly = false) float value) {
        if (SteveVitals.owns(stats)) value = stat.getDefaultValue();
    }
}

