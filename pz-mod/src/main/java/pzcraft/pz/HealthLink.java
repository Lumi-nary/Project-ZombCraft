package pzcraft.pz;

import java.util.ArrayList;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Wire;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.BodyDamage.BodyPart;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;

/**
 * Minecraft owns the player's life while Steve is in control:
 * <ul>
 *   <li>a PZ zombie's attack becomes that zombie's Minecraft melee attack on Steve (damage, knockback, red flash,
 *       invulnerability ticks) instead of PZ's bites, scratches, hit reactions or being dragged down;</li>
 *   <li>PZ physical injuries (fire, bleeding, vehicle impacts...) are forwarded to Minecraft as damage;
 *       PZ's survival stats pause separately in {@link SteveVitals};</li>
 *   <li>PZ's health is then set to Steve's every tick, and PZ may not declare the player dead while Steve lives.
 *       When Steve dies, the PZ camera puppet stays alive while Minecraft shows its death screen.</li>
 * </ul>
 */
public final class HealthLink {
    /** The PZ player whose life Minecraft owns right now (null: PZ decides alone). Read by {@link Patch_IsDead}. */
    public static volatile IsoGameCharacter protectedPlayer;

    private static volatile float steveHealth = -1f, steveMax = 20f;
    private static volatile boolean steveDied;
    private static IsoPlayer lastPlayer;
    private static float[] partsAfter;
    private static float pendingDamage;
    private static long lastDamageSent;
    static volatile int zombieAttacks, damageEvents;

    private HealthLink() {}

    // ---- link thread ----

    static void onSteveHealth(float health, float max) {
        steveHealth = health;
        steveMax = max > 0f ? max : 20f;
    }

    static void onSteveDied() { steveDied = true; }

    // ---- patches (game thread) ----

    /** BodyDamage.AddRandomDamageFromZombie: true = skip PZ's own version of the attack. */
    public static boolean zombieAttack(BodyDamage body, IsoZombie zombie) {
        IsoGameCharacter pl = protectedPlayer;
        if (pl == null || zombie == null || body == null || body.getParentChar() != pl) return false;
        zombieAttacks++;
        LinkService.send(Wire.MSG_ZOMBIE_ATTACK, Wire.encodeZombieAttack(new Wire.ZombieAttack(zombie.getID(),
                Coords.mcX(zombie.getX()), ActorTerrain.mcY(zombie), Coords.mcZ(zombie.getY()))));
        return true;
    }

    // ---- game thread, once per frame ----

    static void tick(IsoPlayer player) {
        boolean control = player != null && player == IsoPlayer.getInstance()
                && LinkService.connected() && steveHealth >= 0f && ViewpointBridge.viewEnabled();
        if (player != lastPlayer) {
            lastPlayer = player;
            partsAfter = null;
            pendingDamage = 0f;
        }
        if (steveDied) {
            steveDied = false;
            partsAfter = null;
            pendingDamage = 0f;
            Log.info("Steve died: PZ camera puppet remains protected while Minecraft shows Respawn");
        }
        if (!control) {
            protectedPlayer = null;
            partsAfter = null;
            pendingDamage = 0f;
            return;
        }
        protectedPlayer = player;
        player.setDeathDragDown(false);
        if (player.getHealth() <= 0f) {
            // PZ zeroed the character outright (freezing, a vehicle...): that is Steve's death too.
            player.setHealth(1f);
            pendingDamage += steveMax;
        }

        BodyDamage body = player.getBodyDamage();
        if (body == null) return;
        ArrayList<BodyPart> parts = body.getBodyParts();
        int n = Math.min(parts.size(), BodyPartType.ToIndex(BodyPartType.MAX));

        // Whatever PZ took off the body parts since we last set them is damage Steve must take.
        if (steveHealth > 0 && partsAfter != null && partsAfter.length == n) {
            float lostPz = 0f;
            for (int i = 0; i < n; i++) {
                float h = parts.get(i).getHealth();
                if (h < partsAfter[i]) lostPz += (partsAfter[i] - h) * BodyPartType.getDamageModifyer(i);
            }
            if (lostPz > 0f) pendingDamage += lostPz / 100f * steveMax;
        }
        long now = System.currentTimeMillis();
        if (steveHealth <= 0) pendingDamage = 0;
        if (steveHealth > 0 && pendingDamage >= 0.25f && now - lastDamageSent >= 250) {
            damageEvents++;
            LinkService.send(Wire.MSG_PZ_DAMAGE, Wire.encodeFloats(pendingDamage));
            pendingDamage = 0f;
            lastDamageSent = now;
        }

        // PZ's health shows Steve's.
        float hp = steveHealth;
        if (hp < 0f) return; // not heard from Minecraft yet: leave PZ's numbers alone
        float target = hp <= 0 ? 100f : Math.max(1f, Math.min(100f, hp / steveMax * 100f));
        float modSum = 0f;
        for (int i = 0; i < n; i++) modSum += BodyPartType.getDamageModifyer(i);
        float each = modSum > 0f ? Math.max(0f, Math.min(100f, 100f - (100f - target) / modSum)) : target;
        if (partsAfter == null || partsAfter.length != n) partsAfter = new float[n];
        for (int i = 0; i < n; i++) {
            BodyPart part = parts.get(i);
            part.SetHealth(each);
            partsAfter[i] = part.getHealth();
        }
        body.calculateOverallHealth();
    }

    static java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("minecraftOwnsHealth", protectedPlayer != null);
        m.put("steveHealth", steveHealth);
        m.put("steveMax", steveMax);
        m.put("zombieAttacksForwarded", zombieAttacks);
        m.put("pzDamageEvents", damageEvents);
        return m;
    }
}

