package pzcraft.pz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Wire;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.IsoGameCharacter;
import zombie.characters.Stats;
import zombie.characters.CharacterStat;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/**
 * PZ's half of combat and survival:
 * <ul>
 *   <li>streams nearby zombies to Minecraft, where they exist as invisible, hittable entities;</li>
 *   <li>applies Minecraft's hits to those zombies (PZ owns their health);</li>
 *   <li>publishes PZ's hunger and clock (Minecraft owns health: see {@link HealthLink}).</li>
 * </ul>
 */
final class Gameplay {
    private static final double RANGE = 24.0;      // tiles; matches the collision export radius
    private static final double HIT_SCALE = 0.3;   // Minecraft hit points -> PZ zombie health (zombies have ~1.8-2.1)
    private static final double HURT_SCALE = 5.0;  // Minecraft hit points -> PZ body health (20 hp = 100)
    private static final int MAX_ACTORS = 120;

    private static final ConcurrentLinkedQueue<Wire.Hit> hits = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Float> meals = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> weaponUses = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Wire.MeleeHit> meleeHits = new ConcurrentLinkedQueue<>();
    static volatile int weaponWearRolls, weaponWorn;
    private static int meleeApplied, meleeRangeRejected, meleeMissing, meleeCritical;
    private static volatile Map<String, Object> lastMelee = Map.of();
    /** id -> zombie from the last export, so a hit can find its target. Game thread only. */
    private static final Map<Integer, IsoGameCharacter> exported = new HashMap<>();

    private static long lastActors, lastVitals, lastTime;
    /** How far a Minecraft hit shoves a zombie (tiles), on top of PZ's own stagger animation. */
    private static final double KNOCKBACK_TILES = 0.35;
    static volatile int lastActorCount;
    private static int seenEpoch = -1;

    private Gameplay() {}

    // Called by the link thread.
    static void onHit(Wire.Hit h) { if (hits.size() < 512) hits.add(h); }
    static void onAte(float foodLevels) { if (meals.size() < 64) meals.add(foodLevels); }
    static void onWeaponUsed(String typeAndId) { if (weaponUses.size() < 256) weaponUses.add(typeAndId); }
    static void onMeleeHit(Wire.MeleeHit hit) { if (meleeHits.size() < 256) meleeHits.add(hit); }

    static void tick(IsoPlayer player) {
        if (player == null || !LinkService.connected()) return;
        long now = System.currentTimeMillis();
        if (seenEpoch != LinkService.epoch()) {
            seenEpoch = LinkService.epoch();
            exported.clear();
        }
        applyHits(player);
        applyMeleeHits(player);
        GunBridge.tick(player);
        applyWeaponUses(player);
        applyMeals(player);
        HealthLink.tick(player); // Minecraft owns health; regeneration is Minecraft's own (well fed -> heals)
        BlockBridge.tick();
        VehicleBridge.tick(player);
        if (now - lastActors >= 50) { lastActors = now; sendActors(player); }
        if (now - lastVitals >= 100) { lastVitals = now; sendVitals(player); }
        if (now - lastTime >= 1000) { lastTime = now; sendTime(); }
    }

    // ---- zombies out ----

    private static void sendActors(IsoPlayer player) {
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        List<Wire.Actor> out = new ArrayList<>();
        Map<Integer, IsoGameCharacter> seen = new HashMap<>();
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        for (IsoZombie z : cell.getZombieList()) {
            if (z == null || z.isDead() || z.isFakeDead()) continue;
            double dx = z.getX() - px, dy = z.getY() - py;
            if (dx * dx + dy * dy > RANGE * RANGE || Math.abs(ActorTerrain.mcY(z)-Coords.mcY(pz)) > RANGE) continue;
            int id = z.getID();
            float health01 = (float) Math.max(0.0, Math.min(1.0, z.getHealth() / 1.8));
            float yawDeg = Coords.pzRadToMcYawDeg(z.getDirectionAngleRadians());
            out.add(new Wire.Actor(id, Coords.mcX(z.getX()), ActorTerrain.mcY(z), Coords.mcZ(z.getY()), yawDeg, health01,
                    (z.isCrawling() ? Wire.Actor.FLAG_CRAWLING : 0) | (ActorTerrain.ground(z)?Wire.Actor.FLAG_GROUND_PHYSICS:0)));
            seen.put(id, z);
            if (out.size() >= MAX_ACTORS) break;
        }
        for(var animal:cell.getAnimals()) {
            if(out.size()>=MAX_ACTORS)break;
            double dx=animal.getX()-px,dy=animal.getY()-py;
            if(animal.isDead()||animal.isOnHook()||dx*dx+dy*dy>RANGE*RANGE||Math.abs(animal.getZ()-pz)>2.5)continue;
            String type=animal.getAnimalType();
            int flags=Wire.Actor.FLAG_ANIMAL;
            if(type!=null&&(type.contains("chicken")||type.contains("rabbit")))flags|=Wire.Actor.FLAG_SMALL_ANIMAL;
            out.add(new Wire.Actor(animal.getID(),Coords.mcX(animal.getX()),Coords.mcY(animal.getZ())+.01,
                    Coords.mcZ(animal.getY()),Coords.pzRadToMcYawDeg(animal.getDirectionAngleRadians()),animal.getHealth(),flags));
            seen.put(animal.getID(),animal);
        }
        lastActorCount = out.size();
        exported.clear();
        exported.putAll(seen);
        LinkService.send(Wire.MSG_ACTORS, Wire.encodeActors(out));
    }

    // ---- hits in ----

    /** PZ melee contacts carry identity, not client damage. Native Hit applies armour, skills, crits and hit reactions. */
    private static void applyMeleeHits(IsoPlayer player) {
        if (!SteveControl.ownsSurvival(player)) { meleeHits.clear(); return; }
        Wire.MeleeHit hit;
        while ((hit = meleeHits.poll()) != null) {
            IsoGameCharacter target = exported.get(hit.actorId());
            zombie.inventory.InventoryItem item = findWeapon(player, hit.weaponType(), hit.itemId());
            if (target == null || target.isDead() || !(item instanceof zombie.inventory.types.HandWeapon weapon)
                    || weapon.isRanged() || weapon.getCondition() <= 0 || hit.targetIndex() > Math.max(1, weapon.getMaxHitCount())) {
                meleeMissing++; continue;
            }
            double distance = Math.hypot(target.getX() - player.getX(), target.getY() - player.getY());
            // PZ's reach in tiles, scaled to Minecraft's blocks (pzcraft.protocol.MeleeReach): a katana reaches about three blocks
            float range = (float) (weapon.getMaxRange(player) * weapon.getRangeMod(player) * pzcraft.protocol.MeleeReach.SCALE);
            if (distance > range + 0.1 || Math.abs(ActorTerrain.mcY(target)-Coords.mcY(player.getZ())) > 0.5*Coords.BLOCKS_PER_LEVEL) {
                meleeRangeRejected++; continue;
            }
            zombie.inventory.InventoryItem primary = player.getPrimaryHandItem(), secondary = player.getSecondaryHandItem();
            boolean critical = player.isCriticalHit(), shove = player.isDoShove(), floor = player.isAimAtFloor();
            float charge = player.chargeTime;
            try {
                player.setPrimaryHandItem(weapon);
                player.setSecondaryHandItem(weapon.isTwoHandWeapon() ? weapon : null);
                player.setDoShove(false); player.setAimAtFloor(false);
                player.chargeTime = 2; // Minecraft's completed melee swing has passed its wind-up.
                int chance = player.calculateCritChance(target);
                boolean crit = zombie.core.random.Rand.Next(100) < chance;
                player.setCriticalHit(crit);
                float rolled = zombie.core.random.Rand.Next(weapon.getMinDamage(), weapon.getMaxDamage());
                float modifier = weapon.getDamageMod(player) * player.getHittingMod()
                        * player.getCharacterTraits().getTraitDamageDealtReductionModifier();
                float split = rolled * modifier / (hit.targetIndex() * 0.5f);
                float rangeDelta = (float) (distance / pzcraft.protocol.MeleeReach.SCALE / Math.max(0.01, weapon.getMaxRange(player)) * 2);   // PZ's own tile distance
                if (rangeDelta < 0.3f) rangeDelta = 1;
                float before = target.getHealth();
                target.Hit(weapon, player, split, false, rangeDelta, false);
                rollWear(player, weapon);
                String sound = weapon.getZombieHitSound();
                if (sound != null && !sound.isBlank()) {
                    player.setMeleeHitSurface(zombie.audio.parameters.ParameterMeleeHitSurface.Material.Body);
                    player.getEmitter().playSoundImpl(sound, null);
                }
                meleeApplied++;
                if (crit) meleeCritical++;
                lastMelee = Map.of("weapon", hit.weaponType(), "itemId", hit.itemId(), "targetIndex", hit.targetIndex(),
                        "distance", distance, "range", range, "rolled", rolled, "modifier", modifier,
                        "critical", crit, "critChance", chance, "healthLost", before - target.getHealth());
            } finally {
                player.setPrimaryHandItem(primary); player.setSecondaryHandItem(secondary);
                player.setCriticalHit(critical); player.setDoShove(shove); player.setAimAtFloor(floor); player.chargeTime = charge;
            }
        }
    }

    static zombie.inventory.InventoryItem findWeapon(IsoPlayer player, String type, int id) {
        zombie.inventory.InventoryItem byType = null;
        for (zombie.inventory.InventoryItem item : new ArrayList<>(player.getInventory().getItems())) {
            if (item == null || !item.getFullType().equals(type)) continue;
            if (id >= 0 && item.getID() == id) return item;
            if (byType == null) byType = item;
        }
        return id < 0 ? byType : null;
    }

    static void rollWear(IsoPlayer player, zombie.inventory.InventoryItem weapon) {
        if (weapon.getConditionMax() <= 0) return;
        int before = weapon.getCondition();
        weaponWearRolls++;
        weapon.damageCheck(0, 1.0f, true, true, player);
        if (weapon.getCondition() != before) {
            weaponWorn++;
            Log.info("weapon wear: " + weapon.getFullType() + " #" + weapon.getID() + " condition " + before + " -> " + weapon.getCondition());
        }
    }
    static IsoGameCharacter actor(int id) { return exported.get(id); }

    static Map<String, Object> weaponStats() {
        return Map.of("wearRolls", weaponWearRolls, "worn", weaponWorn, "meleeApplied", meleeApplied,
                "meleeRangeRejected", meleeRangeRejected, "meleeMissing", meleeMissing, "meleeCritical", meleeCritical, "lastMelee", lastMelee);
    }

    private static void applyHits(IsoPlayer player) {
        Wire.Hit h;
        while ((h = hits.poll()) != null) {
            IsoGameCharacter z = exported.get(h.actorId());
            if (z == null || z.isDead()) continue;
            float dmg = (float) Math.max(0.05, h.damage() * HIT_SCALE);
            z.setHealth(z.getHealth() - dmg);
            if (z.getHealth() <= 0f) {
                z.Kill(player, true);
                Log.info("zombie " + h.actorId() + " killed by Steve (hit " + h.damage() + ")");
            } else {
                z.setHitFromBehind(false);
                if(z instanceof IsoZombie zombie)zombie.setStaggerBack(true);
                shove(z, h.knockX(), h.knockZ());
            }
        }
    }

    /**
     * A hit with the weapon Steve holds in Minecraft: PZ's own wear roll for it. CombatManager.processMaintenanceCheck calls
     * {@code weapon.damageCheck(0, 1)} for every character the weapon hits (the item's own condition-lower chance, sharpness and
     * head condition, with the character's Maintenance level); the item is found in the hidden inventory by its type and its id.
     * A changed condition changes the item's key, so the inventory mirror swaps the Minecraft stack in place.
     */
    private static void applyWeaponUses(IsoPlayer player) {
        String use;
        while ((use = weaponUses.poll()) != null) {
            int bar = use.lastIndexOf('|');
            if (bar < 0) continue;
            String type = use.substring(0, bar);
            int id = Integer.parseInt(use.substring(bar + 1));
            zombie.inventory.InventoryItem weapon = findWeapon(player, type, id);
            if (weapon == null || !(weapon instanceof zombie.inventory.types.HandWeapon) || weapon.getConditionMax() <= 0) continue;
            rollWear(player, weapon);
        }
    }

    /** Minecraft knockback: push the zombie away from Steve, unless a wall or obstacle is in the way. */
    private static void shove(IsoGameCharacter z, float kx, float kz) {
        if (kx == 0f && kz == 0f) return;
        float nx = (float) (z.getX() + kx * KNOCKBACK_TILES), ny = (float) (z.getY() + kz * KNOCKBACK_TILES);
        IsoCell cell = IsoWorld.instance.getCell();
        zombie.iso.IsoGridSquare from = z.getCurrentSquare();
        zombie.iso.IsoGridSquare to = cell == null ? null : cell.getGridSquare((int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(z.getZ()));
        if (from == null || to == null || to.isSolid() || to.isSolidTrans() || (from != to && from.isBlockedTo(to))) return;
        z.setX(nx);
        z.setY(ny);
    }

    private static void applyMeals(IsoPlayer player) {
        Float levels;
        while ((levels = meals.poll()) != null) {
            if (SteveControl.ownsSurvival(player)) continue; // vanilla food and saturation belong to Minecraft
            Stats stats = player.getStats();
            if (stats == null) continue;
            float hunger = stats.get(CharacterStat.HUNGER);
            float now = Math.max(0f, hunger - levels / 20f);
            stats.set(CharacterStat.HUNGER, now);
            Log.info(String.format("Steve ate (+%.1f food): PZ hunger %.2f -> %.2f", levels, hunger, now));
        }
    }

    // ---- vitals + clock out ----

    private static void sendVitals(IsoPlayer player) {
        if (SteveControl.ownsSurvival(player)) return;
        float health01 = 1f; // unused: Minecraft owns health
        Stats stats = player.getStats();
        float hunger = stats != null ? stats.get(CharacterStat.HUNGER) : 0f;
        float food01 = Math.max(0f, Math.min(1f, 1f - hunger));
        LinkService.send(Wire.MSG_VITALS, Wire.encodeVitals(new Wire.Vitals(health01, food01)));
    }

    private static void sendTime() {
        LinkService.send(Wire.MSG_TIME, Wire.encodeTime(zombie.GameTime.getInstance().getTimeOfDay()));
    }
}

