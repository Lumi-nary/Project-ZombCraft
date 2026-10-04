package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/** The puppet's vertical movement is synthetic. Minecraft already calculates the real landing damage. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "DoLand")
public class Patch_NoPzFallDamage {
    @OnEnter(skipOn = true)
    public static boolean enter(@This IsoGameCharacter self) {
        if (self != HealthLink.protectedPlayer && !SteveControl.ownsSurvival(self)) return false;
        self.clearFallDamage();
        return true;
    }
}

