package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/** PZ must not run its irreversible death side effects before Minecraft decides the player's death. */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "Kill")
public class Patch_NoPzKill {
    @OnEnter(skipOn = true)
    public static boolean enter(@This IsoGameCharacter self) { return self == HealthLink.protectedPlayer; }
}

