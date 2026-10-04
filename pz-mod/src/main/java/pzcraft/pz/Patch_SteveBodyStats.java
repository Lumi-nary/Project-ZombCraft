package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.BodyDamage.BodyDamage;
@Patch(className = "zombie.characters.BodyDamage.BodyDamage", methodName = "Update")
public class Patch_SteveBodyStats {
    @OnEnter public static void enter(@This BodyDamage body) { SteveVitals.neutral(body.getParentChar()); }
    @OnExit public static void exit(@This BodyDamage body) { SteveVitals.neutral(body.getParentChar()); }
}

