package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.Argument;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.IsoZombie;

/**
 * Every way a PZ zombie hurts the player goes through BodyDamage.AddRandomDamageFromZombie (bites, scratches, the hit
 * reaction that stops you, being dragged down). While Minecraft owns Steve's life the attack is sent to Minecraft
 * instead and PZ's version is skipped.
 */
@Patch(className = "zombie.characters.BodyDamage.BodyDamage", methodName = "AddRandomDamageFromZombie")
public class Patch_ZombieAttack {
    @OnEnter(skipOn = true)
    public static boolean enter(@This BodyDamage body, @Argument(0) IsoZombie zombie) {
        return HealthLink.zombieAttack(body, zombie);
    }
}

