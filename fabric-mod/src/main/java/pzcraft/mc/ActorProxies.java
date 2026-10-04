package pzcraft.mc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Wire;

/**
 * Server-side half of combat and vitals.
 *
 * <p>Every PZ zombie near Steve exists in Minecraft as an invisible, silent, AI-less Minecraft zombie at the same spot,
 * so vanilla swords, sweeps, crits and knockback work on a real zombie. It takes Steve's hits for real (so Minecraft's
 * hit effects and invulnerability ticks apply), each hit is forwarded to PZ, which owns the zombie's health, and the
 * proxy is healed straight back. When a PZ zombie lands an attack on the player, its proxy performs Minecraft's zombie
 * melee attack on Steve.
 *
 * <p>Minecraft owns Steve's health: damage is Minecraft's, regeneration is Minecraft's (well fed heals), and PZ mirrors
 * the number. PZ physical injuries (fire, bleeding, vehicle impacts) arrive as damage to apply. Minecraft owns hunger
 * while Steve drives; PZ hunger is mirrored only outside Steve mode.
 */
public final class ActorProxies {
    /** Proxies never die in Minecraft: PZ decides when the zombie dies, and then the proxy is simply removed. */
    private static final double PROXY_HEALTH = 1024.0;

    /** PZ actor id -> proxy entity. Server thread only. */
    private static final Map<Integer, UUID> byPzId = new HashMap<>();
    private static final Map<UUID, Integer> byUuid = new HashMap<>();
    /** Filled by the link thread, drained on the server thread. */
    static final ConcurrentLinkedQueue<Wire.ZombieAttack> zombieAttacks = new ConcurrentLinkedQueue<>();
    static final ConcurrentLinkedQueue<Float> pzDamage = new ConcurrentLinkedQueue<>();

    private static boolean kitGiven;
    private static int logTicks;
    private static boolean blocksScanned;
    private static int lastFoodSet = -1;
    private static int failures;
    private static float lastHealthSent = -1f;
    private static int healthTicks;
    private static float pendingPzDamage;
    private static boolean deathReported;
    /** PZ zombie attacks that Minecraft's reach or line of sight turned into misses (logged with the proxy stats). */
    private static int missedAttacks;
    private static volatile int attacksApplied, attacksRange, attacksWall, attacksHeight, swingCapped;
    public static Map<String,Object> combatStats(){return Map.of("applied",attacksApplied,"rangeRejected",attacksRange,"wallRejected",attacksWall,"heightRejected",attacksHeight,"swingCapped",swingCapped);}
    public static volatile Map<String,Object> worldStats=Map.of();
    private static final Set<Integer> riding=new HashSet<>();
    private static final Map<Integer,Integer> dismountTicks=new HashMap<>();

    private ActorProxies() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ActorProxies::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(ActorProxies::allowDamage);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(ActorProxies::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer && Session.pzConnected && !deathReported) {
                deathReported = true;
                kitGiven = false; // a new life in PZ gets a fresh starter kit
                Session.send(Wire.MSG_STEVE_DIED, new byte[0]);
                PzCraftClient.LOG.info("Steve died ({}): telling PZ", source.getMsgId());
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(ActorProxies::configureWorld);
    }

    /** Minecraft's rules for a zombie world that PZ populates: real mob damage, but no Minecraft mobs of its own. */
    private static void configureWorld(MinecraftServer server) {
        if (server instanceof net.minecraft.client.server.IntegratedServer integrated) integrated.setWorldAllowCommands(true);
        server.setDifficulty(Difficulty.NORMAL, true);
        GameRules rules = server.getGameRules();
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.SPAWN_MONSTERS, false, server);
        rules.set(GameRules.SPAWN_PATROLS, false, server);
        rules.set(GameRules.SPAWN_PHANTOMS, false, server);
        rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        rules.set(GameRules.SPAWN_WARDENS, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
        rules.set(GameRules.SHOW_DEATH_MESSAGES, false, server);
        PzCraftClient.LOG.info("world rules: difficulty normal, no Minecraft mob spawning");
    }

    private static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) return;
        if (!Session.pzConnected) {
            clearAll(level);
            return;
        }
        syncActors(level);
        // Proxy actor IDs belong to one PZ session. Discard saved proxies after a reload instead of duplicating them.
        if(logTicks%20==0) {
            // discard() mutates the level's backing entity map; collect first to keep its iterator valid.
            var stale=new java.util.ArrayList<Entity>();
            for(var entity:level.getAllEntities())
                if(isProxy(entity)&&!byUuid.containsKey(entity.getUUID()))stale.add(entity);
            stale.forEach(Entity::discard);
        }
        ServerPlayer sp = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().getFirst();
        if (sp != null) {
            applyZombieAttacks(level, sp);
            applyPzDamage(level, sp);
            syncVitals(sp);
            reportHealth(sp);
            EnvironmentBridge.tick(sp);
            var stats=new java.util.LinkedHashMap<String,Object>();
            stats.put("sleeping",sp.isSleeping());
            var respawn=sp.getRespawnConfig();
            if(respawn!=null){var pos=respawn.respawnData().pos();stats.put("spawn",List.of(pos.getX(),pos.getY(),pos.getZ()));}
            level.dimensionTypeRegistration().value().defaultClock().ifPresent(clock->stats.put("timeTicks",server.clockManager().getInstance(clock).totalTicks()));
            stats.put("proxyCount",byPzId.size());
            stats.put("tags",List.copyOf(sp.entityTags()));
            stats.put("inWater",sp.isInWater());stats.put("onFire",sp.isOnFire());stats.put("air",sp.getAirSupply());
            stats.put("food", sp.getFoodData().getFoodLevel()); stats.put("saturation", sp.getFoodData().getSaturationLevel());
            stats.put("minecraftOwnsFood", Session.steveDrives);
            stats.put("eyesInWater",sp.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            stats.put("xyz",List.of(sp.getX(),sp.getY(),sp.getZ()));stats.put("eyeY",sp.getEyeY());
            stats.put("fallFlying",sp.isFallFlying());stats.put("flying",sp.getAbilities().flying);
            stats.put("invulnerable",sp.getAbilities().invulnerable);stats.put("creative",sp.isCreative());
            stats.put("effects",sp.getActiveEffects().stream().map(e->e.getEffect().getRegisteredName()).toList());
            var entities=new java.util.ArrayList<Map<String,Object>>();
            for(var e:level.getEntities(sp,sp.getBoundingBox().inflate(24))) {
                if(entities.size()>=128)break;
                var es=new java.util.LinkedHashMap<String,Object>();
                es.put("id",e.getId());es.put("uuid",e.getUUID().toString());es.put("type",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                if(e.getCustomName()!=null)es.put("name",e.getCustomName().getString());
                es.put("xyz",List.of(e.getX(),e.getY(),e.getZ()));es.put("tags",List.copyOf(e.entityTags()));es.put("proxy",isProxy(e));
                es.put("width",e.getBbWidth());es.put("height",e.getBbHeight());es.put("vehicle",e.isPassenger()?e.getVehicle().getId():0);
                if(e instanceof LivingEntity le){es.put("health",le.getHealth());es.put("maxHealth",le.getMaxHealth());}
                if(e instanceof Mob mob){es.put("target",mob.getTarget()==null?0:mob.getTarget().getId());es.put("noAi",mob.isNoAi());es.put("navigationDone",mob.getNavigation().isDone());}
                entities.add(es);
            }
            stats.put("entities",entities);
            var states=new java.util.LinkedHashMap<String,String>();
            worldStats=Map.copyOf(stats);
        }
        syncTime(server, level);
        RespawnBridge.tick();
        if (++logTicks % 200 == 0 && Session.released) {
            PzCraftClient.LOG.info("proxies={} (PZ reports {}), vitals={}, health={}, attacks out of reach={}", byPzId.size(),
                    Session.actors.size(), Session.vitals, sp == null ? "-" : sp.getHealth(), missedAttacks);
        }
    }

    // ---- zombies ----

    private static void syncActors(ServerLevel level) {
        List<Wire.Actor> actors = Session.actors;
        Set<Integer> seen = new HashSet<>();
        var passengers=new java.util.ArrayList<pzcraft.protocol.Passengers.Rider>();
        var ground=new java.util.ArrayList<pzcraft.protocol.ActorGround.Pose>();
        for (Wire.Actor a : actors) {
            seen.add(a.id());
            Entity e = lookup(level, a.id());
            if (e == null) {
                Zombie z = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.EVENT);
                if (z == null) {
                    if (failures++ < 5) PzCraftClient.LOG.warn("could not create a proxy entity for PZ actor {}", a.id());
                    continue;
                }
                z.setNoAi(true);
                z.setSilent(true);       // PZ's zombie makes the noises
                z.setCustomName(net.minecraft.network.chat.Component.literal(proxyName(a)));
                z.setCustomNameVisible(false);
                z.refreshDimensions();
                z.setInvisible(true);    // ... and is the one you see
                z.setNoGravity(true);
                z.setCanPickUpLoot(false);
                z.setPersistenceRequired();
                AttributeInstance maxHealth = z.getAttribute(Attributes.MAX_HEALTH);
                if (maxHealth != null) maxHealth.setBaseValue(PROXY_HEALTH);
                z.setHealth((float) PROXY_HEALTH);
                z.snapTo(a.x(), a.y(), a.z(), a.yawDeg(), 0f);
                if (level.addFreshEntity(z)) {
                    byPzId.put(a.id(), z.getUUID());
                    byUuid.put(z.getUUID(), a.id());
                } else if (failures++ < 5) {
                    PzCraftClient.LOG.warn("addFreshEntity refused PZ actor {} at ({}, {}, {})", a.id(), a.x(), a.y(), a.z());
                }
            } else {
                String name=proxyName(a);
                if(e.getCustomName()==null||!e.getCustomName().getString().equals(name)){
                    e.setCustomName(net.minecraft.network.chat.Component.literal(name));e.refreshDimensions();
                }
                if(e.isPassenger()){
                    ActorTerrain.forget(a.id());
                    riding.add(a.id());
                    passengers.add(new pzcraft.protocol.Passengers.Rider(a.id(),e.getVehicle().getId(),e.getX(),e.getY(),e.getZ(),e.getYRot()));
                }else if(riding.remove(a.id())||dismountTicks.containsKey(a.id())){
                    ActorTerrain.forget(a.id());
                    int left=dismountTicks.getOrDefault(a.id(),10)-1;
                    passengers.add(new pzcraft.protocol.Passengers.Rider(a.id(),0,e.getX(),e.getY(),e.getZ(),e.getYRot()));
                    if(left<=0)dismountTicks.remove(a.id());else dismountTicks.put(a.id(),left);
                }else{
                    var pose=ActorTerrain.move(level,e,a);
                    if(pose!=null)ground.add(pose);
                    else {e.snapTo(a.x(), a.y(), a.z(), a.yawDeg(), 0f);e.setDeltaMovement(Vec3.ZERO);}
                }
                if (e instanceof LivingEntity le && le.getHealth() < le.getMaxHealth()) le.setHealth(le.getMaxHealth());
            }
        }
        // PZ no longer reports these (killed, despawned or out of range): drop their proxies.
        Iterator<Map.Entry<Integer, UUID>> it = byPzId.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, UUID> en = it.next();
            if (seen.contains(en.getKey())) continue;
            Entity e = level.getEntity(en.getValue());
            if (e != null) e.discard();
            byUuid.remove(en.getValue());
            it.remove();
        }
        riding.retainAll(seen);dismountTicks.keySet().retainAll(seen);
        ActorTerrain.retain(seen);
        Session.send(Wire.MSG_PASSENGERS,pzcraft.protocol.Passengers.encode(passengers));
        Session.send(Wire.MSG_ACTOR_GROUND,pzcraft.protocol.ActorGround.encode(ground));
    }
    private static String proxyName(Wire.Actor a){
        String shape=(a.flags()&Wire.Actor.FLAG_SMALL_ANIMAL)!=0?":small":(a.flags()&Wire.Actor.FLAG_ANIMAL)!=0?":animal":(a.flags()&Wire.Actor.FLAG_CRAWLING)!=0?":crawl":":stand";
        return "PzCraftProxy:"+a.id()+shape;
    }

    private static Entity lookup(ServerLevel level, int pzId) {
        UUID u = byPzId.get(pzId);
        if (u == null) return null;
        Entity e = level.getEntity(u);
        if (e == null || e.isRemoved()) {
            byPzId.remove(pzId);
            byUuid.remove(u);
            return null;
        }
        return e;
    }
    public static boolean isProxy(Entity entity) {
        return entity != null && ((entity.getCustomName() != null && entity.getCustomName().getString().startsWith("PzCraftProxy:"))
                || (entity instanceof Zombie z && z.isNoAi() && z.isSilent() && z.isNoGravity()
                    && z.getMaxHealth() >= 512));
    }
    static int pzId(Entity entity) { return byUuid.getOrDefault(entity.getUUID(), -1); }

    private static void clearAll(ServerLevel level) {
        if (byPzId.isEmpty()) return;
        for (UUID u : byPzId.values()) {
            Entity e = level.getEntity(u);
            if (e != null) e.discard();
        }
        byPzId.clear();
        byUuid.clear();
        ActorTerrain.clear();
        riding.clear();dismountTicks.clear();
    }

    /** A PZ zombie's attack landed: its proxy performs Minecraft's zombie melee attack on Steve. */
    private static void applyZombieAttacks(ServerLevel level, ServerPlayer sp) {
        Wire.ZombieAttack a;
        while ((a = zombieAttacks.poll()) != null) {
            if (!Session.released || !sp.isAlive()) continue;
            Entity proxy = lookup(level, a.zombieId());
            // PZ decided the hit in 2D, within one storey. Minecraft's own melee rules add the third dimension: a
            // zombie on the ground cannot reach Steve on top of a two-block pillar, nor hit him through Minecraft blocks.
            if (proxy instanceof Mob mob) {
                if(!ActorTerrain.controls(a.zombieId()))mob.snapTo(a.x(), a.y(), a.z(), mob.getYRot(), 0f);
                // PZ tests the latest client pose while the integrated server may be one tick behind a sprinting
                // Steve. Bound that tolerance horizontally; height and obstruction remain strict.
                double reach=Math.sqrt(2.04)-.6;
                boolean range=mob.isWithinMeleeAttackRange(sp)||mob.getBoundingBox().inflate(reach+.25,0,reach+.25).intersects(sp.getBoundingBox());
                boolean height=mob.getBoundingBox().maxY>sp.getBoundingBox().minY&&mob.getBoundingBox().minY<sp.getBoundingBox().maxY;
                Vec3 from=mob.getEyePosition(),to=sp.getEyePosition(),delta=to.subtract(from);
                boolean clear=mob.hasLineOfSight(sp)&&(delta.length()<.001||CollisionField.raycast(from,delta.normalize(),Math.max(0,delta.length()-.05))==null);
                if(range&&height&&clear){if(mob.doHurtTarget(level,sp))attacksApplied++;}
                else {missedAttacks++;if(!height)attacksHeight++;else if(!range)attacksRange++;else attacksWall++;
                    if(missedAttacks<=10||missedAttacks%50==0)PzCraftClient.LOG.info("PZ melee rejected: actor={} range={} height={} clear={} actorBox={} playerBox={}",a.zombieId(),range,height,clear,mob.getBoundingBox(),sp.getBoundingBox());}
            } else {
                // No proxy yet (just spawned): a plain zombie-strength hit, with the same reach as a zombie's.
                var reach = new net.minecraft.world.phys.AABB(a.x() - 0.3, a.y(), a.z() - 0.3, a.x() + 0.3, a.y() + 1.95, a.z() + 0.3)
                        .inflate(Math.sqrt(2.04) - 0.6, 0.0, Math.sqrt(2.04) - 0.6);
                Vec3 from=new Vec3(a.x(),a.y()+1.3,a.z()),to=sp.getEyePosition(),delta=to.subtract(from);
                boolean clear=level.clip(new net.minecraft.world.level.ClipContext(from,to,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,sp)).getType()==net.minecraft.world.phys.HitResult.Type.MISS
                        &&(delta.length()<.001||CollisionField.raycast(from,delta.normalize(),Math.max(0,delta.length()-.05))==null);
                if (reach.intersects(sp.getBoundingBox())&&clear) {
                    if(sp.hurtServer(level, level.damageSources().generic(), 3f))attacksApplied++;
                } else {missedAttacks++;if(!clear)attacksWall++;else attacksRange++;}
            }
        }
    }

    /** Harm PZ dealt to the player some other way (fire, bleeding, starvation, cold). */
    private static void applyPzDamage(ServerLevel level, ServerPlayer sp) {
        Float d;
        while ((d = pzDamage.poll()) != null) pendingPzDamage += d;
        // Apply in lumps that outlast Minecraft's invulnerability ticks, so small steady losses are not swallowed.
        if (pendingPzDamage >= 0.5f && sp.getInvulnerableTime() <= 10 && Session.released && sp.isAlive()) {
            float dmg = pendingPzDamage;
            pendingPzDamage = 0f;
            sp.hurtServer(level, level.damageSources().generic(), dmg);
        }
    }

    // ---- damage ----

    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        if (byUuid.containsKey(entity.getUUID())) {
            if (source.getEntity() instanceof ServerPlayer sp && source.getDirectEntity() == sp) {
                if (!PzWeapons.inReach(sp, entity)) { attacksRange++; return false; }
                if (sp.getBoundingBox().minY >= entity.getBoundingBox().maxY || sp.getBoundingBox().maxY <= entity.getBoundingBox().minY) {
                    attacksHeight++; return false;
                }
                Vec3 from = sp.getEyePosition(), to = entity.getEyePosition(), delta = to.subtract(from);
                if (!sp.hasLineOfSight(entity) || (delta.length() > .001
                        && CollisionField.raycast(from, delta.normalize(), Math.max(0, delta.length() - .05)) != null)) {
                    attacksWall++; return false;
                }
            }
            // PZ's limits for one swing: at most the weapon's maxHitCount targets (more are not touched at all)
            if (source.getEntity() instanceof ServerPlayer sp && source.getDirectEntity() == sp && !PzWeapons.allowMeleeHit(sp, entity)) {
                swingCapped++;
                return false;
            }
            // Real Minecraft combat and fluid/fire damage affect the PZ actor. Ignore incidental suffocation/falls.
            return byPlayer(source) || source.getEntity() != null
                    || source.is(net.minecraft.world.damagesource.DamageTypes.LAVA)
                    || source.is(net.minecraft.world.damagesource.DamageTypes.IN_FIRE)
                    || source.is(net.minecraft.world.damagesource.DamageTypes.ON_FIRE)
                    || source.is(net.minecraft.world.damagesource.DamageTypes.DROWN);
        }
        if (entity instanceof ServerPlayer && Session.pzConnected) {
            return Session.released; // while Steve is held waiting to be placed, nothing may hurt him
        }
        return true;
    }

    /** After Minecraft applied a hit (crits, sweeps, invulnerability ticks and all), PZ's zombie takes it too. */
    private static void afterDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
        Integer pzId = byUuid.get(entity.getUUID());
        if (pzId == null || damageTaken <= 0f) return;
        Entity attacker = source.getEntity();
        float kx = 0, kz = 0;
        if (attacker != null) {
            Vec3 d = entity.position().subtract(attacker.position());
            double len = Math.max(1e-4, Math.hypot(d.x, d.z));
            kx = (float) (d.x / len);
            kz = (float) (d.z / len);
        }
        if (!(attacker instanceof ServerPlayer && source.getDirectEntity() == attacker && PzWeapons.forwardMelee(entity, pzId)))
            Session.send(Wire.MSG_HIT_ACTOR, Wire.encodeHit(new Wire.Hit(pzId, damageTaken, kx, kz)));
    }

    private static boolean byPlayer(DamageSource source) {
        return source.getEntity() instanceof Player;
    }

    // ---- clock ----

    private static long lastTimeSet;

    /** Minecraft's day follows PZ's clock (MC tick 0 is 06:00), so lighting on placed blocks matches the picture. */
    private static void syncTime(MinecraftServer server, ServerLevel level) {
        TimeBridge.tick(server);
    }

    private static int timeTicks;

    // ---- vitals ----

    /** Steve's health is the player's health: PZ shows it and never overrides it. */
    private static void reportHealth(ServerPlayer sp) {
        if (sp.isAlive()) deathReported = false;
        float hp = sp.isAlive() ? sp.getHealth() : 0f;
        if (++healthTicks % 20 == 0 || Math.abs(hp - lastHealthSent) > 0.01f) {
            lastHealthSent = hp;
            Session.send(Wire.MSG_STEVE_HEALTH, Wire.encodeFloats(hp, sp.getMaxHealth()));
        }
    }

    private static void syncVitals(ServerPlayer sp) {
        Wire.Vitals v = Session.vitals;
        if (!Session.released) { blocksScanned = false; lastFoodSet = -1; }
        if (Session.released && !blocksScanned) {
            blocksScanned = true;
            scanBlocks(sp);
        }
        if (v != null && Session.released && sp.isAlive() && !Session.steveDrives) {
            // PZ owns hunger. Never quite 0: starving hurts through PZ (forwarded as damage), not twice.
            int food = Math.max(1, Math.min(20, Math.round(v.food01() * 20f)));
            int cur = sp.getFoodData().getFoodLevel();
            // Eating in Minecraft must still feed the PZ player: report any rise we did not cause.
            if (lastFoodSet >= 0 && cur > lastFoodSet) {
                PzCraftClient.LOG.info("food rose: mc={} lastSet={} pzTarget={} (food01={})", cur, lastFoodSet, food, v.food01());
                Session.send(Wire.MSG_ATE, Wire.encodeTime(cur - lastFoodSet));
            }
            if (cur != food) sp.getFoodData().setFoodLevel(food);
            lastFoodSet = food;
            sp.getFoodData().setSaturation(0f);
        }
        if (Session.steveDrives) lastFoodSet = -1;
        if (Session.released && !kitGiven && sp.isAlive()) {
            kitGiven = true;
            if (sp.getInventory().isEmpty()) giveStarterKit(sp);
        }
    }

    /** A fresh PZ session knows no blocks: describe every solid block already in the loaded chunks around Steve. */
    private static void scanBlocks(ServerPlayer sp) {
        ServerLevel level = sp.level();
        Session.send(Wire.MSG_BLOCKS_RESET, new byte[0]);
        int cx0 = sp.blockPosition().getX() >> 4, cz0 = sp.blockPosition().getZ() >> 4;
        int sent = 0;
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int cx = cx0 - 3; cx <= cx0 + 3; cx++) {
            for (int cz = cz0 - 3; cz <= cz0 + 3; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                var sections = chunk.getSections();
                for (int si = 0; si < sections.length; si++) {
                    var section = sections[si];
                    if (section == null || section.hasOnlyAir()) continue;
                    int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        if (section.getBlockState(x, y, z).isSolid() && sent < 3000) {
                            Session.send(Wire.MSG_BLOCK, Wire.encodeBlock((cx << 4) + x, baseY + y, (cz << 4) + z, true));
                            sent++;
                        }
                    }
                }
            }
        }
        PzCraftClient.LOG.info("scanned {} existing solid blocks for PZ", sent);
    }

    private static void giveStarterKit(ServerPlayer sp) {
        var inv = sp.getInventory();
        inv.add(new ItemStack(Items.IRON_SWORD));
        inv.add(new ItemStack(Items.IRON_PICKAXE));
        inv.add(new ItemStack(Items.OAK_PLANKS, 64));
        inv.add(new ItemStack(Items.STONE, 64));
        inv.add(new ItemStack(Items.GLASS, 32));
        inv.add(new ItemStack(Items.COOKED_BEEF, 16));
        PzCraftClient.LOG.info("starter kit given");
    }
}





