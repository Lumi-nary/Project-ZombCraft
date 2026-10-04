package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "die")
public class Patch_NoPzCorpse {
    @OnEnter(skipOn = true)
    public static boolean enter(@This IsoGameCharacter self) { return self == HealthLink.protectedPlayer; }
}

