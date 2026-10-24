package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
/**
 * Viewpoint's view keys (third person, shoulder swap, camera behind) do nothing while Steve drives (Minecraft's F5 does)
 * or for a character of a kind (CharacterType: first person only). Skipping its poll also keeps Viewpoint from setting
 * third person back from its own setting.
 */
@Patch(className = "viewpoint.camera.ThirdPerson", methodName = "poll")
public class Patch_SteveThirdPerson {
    @OnEnter(skipOn = true) public static boolean enter() {
        return SteveControl.drives(zombie.characters.IsoPlayer.getInstance()) || CharacterType.locksView();
    }
}
