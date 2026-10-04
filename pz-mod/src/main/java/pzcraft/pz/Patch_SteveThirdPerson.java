package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
@Patch(className = "viewpoint.input.ThirdPerson", methodName = "poll")
public class Patch_SteveThirdPerson {
    @OnEnter(skipOn = true) public static boolean enter() { return SteveControl.drives(zombie.characters.IsoPlayer.getInstance()); }
}

