package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.BodyDamage.BodyDamage;

/** The advice only calls {@link BoredomGuard}: ZombieBuddy copies it into PZ, so it may touch nothing but public code. */
@Patch(className = "zombie.characters.BodyDamage.BodyDamage", methodName = "UpdateBoredom")
public class Patch_BoredomSquare {
    @OnEnter(skipOn = true)
    public static boolean enter(@This BodyDamage body) {
        return BoredomGuard.skipTick(body);
    }
}
