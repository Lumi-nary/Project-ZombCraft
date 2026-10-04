package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.Moodles.Moodle;
@Patch(className = "zombie.characters.Moodles.Moodle", methodName = "Update")
public class Patch_SteveMoodle {
    @OnEnter(skipOn = true) public static boolean enter(@This Moodle moodle) { return SteveVitals.suppressMoodle(moodle); }
}

