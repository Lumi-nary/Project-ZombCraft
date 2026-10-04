package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/** While Minecraft owns the player's life, only Steve's death can kill them: PZ never sees its player as dead. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "isDead")
public class Patch_IsDead {
    @OnExit
    public static void exit(@This IsoGameCharacter self, @Return(readOnly = false) boolean dead) {
        if (dead && self == HealthLink.protectedPlayer) dead = false;
    }
}

