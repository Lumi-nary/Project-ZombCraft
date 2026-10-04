package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
@Patch(className = "viewpoint.SceneDrawer", methodName = "lookAlong")
public class Patch_SteveCameraDraw {
    @OnEnter public static void enter(@This Object drawer) { SteveCamera.draw(drawer); }
}

