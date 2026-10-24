package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import zombie.characters.IsoGameCharacter;
/** Viewpoint 0.1.6's free look (Mouse 4) turns the view back when let go; Steve's view is Minecraft's: off while Steve drives. */
@Patch(className = "viewpoint.input.FreeLook", methodName = "update")
public class Patch_SteveFreeLook {
    @OnEnter(skipOn = true) public static boolean enter(@Argument(0) IsoGameCharacter character) { return SteveControl.drives(character); }
}
