package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.IsoGameCharacter;

/**
 * Minecraft owns Steve's falls. Standing on a Minecraft block (or mid-jump) puts the puppet above PZ's floor, which PZ
 * would treat as a fall that never lands: falling animation, then a landing stagger or knock-down, all moving the head
 * that the camera and Steve's eye follow. With this, PZ's own fall step keeps the player "on the ground".
 */
@Patch(className = "zombie.characters.IsoGameCharacter", methodName = "shouldBeFalling")
public class Patch_NoPzFalling {
    @OnExit
    public static void exit(@This IsoGameCharacter self, @Return(readOnly = false) boolean falling) {
        if (falling && SteveControl.ownsSurvival(self)) falling = false;
    }
}

