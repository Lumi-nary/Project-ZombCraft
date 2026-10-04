package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
@Patch(className = "zombie.ui.MoodlesUI", methodName = "render")
public class Patch_HideMoodlesRender {
    @OnEnter(skipOn = true) public static boolean enter(@This Object ui) { return SteveVitals.hideUI(ui, true); }
}

