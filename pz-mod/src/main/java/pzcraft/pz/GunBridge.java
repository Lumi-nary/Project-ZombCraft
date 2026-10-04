package pzcraft.pz;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Coords;
import pzcraft.protocol.GunAction;
import pzcraft.protocol.Wire;
import zombie.CombatManager;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.skills.PerkFactory;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.objects.IsoWindow;
import zombie.network.fields.hit.HitInfo;

/** Native M9 state machine. Never edits a different weapon or consumes ammo for a rejected action. */
final class GunBridge {
    private static final ConcurrentLinkedQueue<GunAction> requests = new ConcurrentLinkedQueue<>();
    private record Reload(GunAction request, int magazineId, int rounds, int oldRounds, boolean oldChamber, double start, double end, boolean empty) {}
    private static Reload reload;
    private static int reloadCue;
    /**
     * What is heard when during Point Blank's M9 reload clips (seconds after the clip starts; magazine out, magazine in, slide
     * release, the gun coming back up), so the sounds follow the animation instead of the end of PZ's timed action. The
     * sounds themselves are PZ's own (this weapon's script): 0 eject, 1 insert, 2 rack, 3 insert stop.
     */
    private static final double[][] CUES = {{.3667, 0}, {.6667, 1}, {1.1, 3}};
    private static final double[][] CUES_EMPTY = {{.1, 0}, {.6333, 1}, {.9167, 2}, {1.1, 3}};
    /** How long the gun is busy: the clip's reload phase (1.58 s, or 1.68 s with an empty chamber), at the Reloading skill's pace. */
    private static final double RELOAD_SECONDS = 1.58, RELOAD_EMPTY_SECONDS = 1.68;
    /** Loading rounds into (or unloading them from) a magazine, one round at a time: native ISLoadBulletsInMagazine / ISUnloadBulletsFromMagazine. */
    private record Load(GunAction request, int magazineId, String ammoKey, boolean unload, double next, int moved, int limit) {}
    private static Load loading;
    private static final String MAGAZINE = "Base.9mmClip";
    private static long lastSequence;
    private static int epoch = -1;
    private static double clock, nextShot;
    private static long lastNanos = System.nanoTime();
    private static int shots, dry, hits, noise, reloaded, cancelled, rejected, windows, failures, roundsLoaded, roundsUnloaded;
    private static volatile Map<String,Object> last = Map.of();
    private static Method hitChance;
    private static java.lang.reflect.Field chanceField;

    private GunBridge() {}
    static void receive(GunAction request) { if (requests.size() < 128) requests.add(request); }

    static void tick(IsoPlayer p) {
        long now = System.nanoTime();
        double elapsed = Math.min(.1, Math.max(0, (now - lastNanos) / 1_000_000_000.0));
        lastNanos = now;
        if (!SteveControl.ownsSurvival(p) || !LinkService.minecraftReady()) {
            requests.clear(); reload = null; loading = null; return;
        }
        if (epoch != LinkService.epoch()) {
            epoch = LinkService.epoch(); lastSequence = 0; reload = null; loading = null; nextShot = 0;
        }
        if (GameTime.isGamePaused()) return;
        clock += elapsed; // FrameTick may run more often than GameTime updates; never reuse its last delta.
        GunAction r;
        while ((r = requests.poll()) != null) {
            try {
                if (r.sequence() <= lastSequence) { rejected++; continue; }
                lastSequence = r.sequence();
                if (r.action() == GunAction.CANCEL) {
                    if (reload != null && reload.request.itemId() == r.itemId()) { reload = null; cancelled++; }
                    if (loading != null && loading.request.itemId() == r.itemId()) stopLoading(p, "stopped");
                    continue;
                }
                if (r.action() == GunAction.LOAD_MAGAZINE || r.action() == GunAction.UNLOAD_MAGAZINE || r.action() == GunAction.LOAD_ONE_ROUND) { startLoading(p, r); continue; }
                InventoryItem item = Gameplay.findWeapon(p, r.weaponType(), r.itemId());
                if (!(item instanceof HandWeapon gun) || !"Base.Pistol".equals(gun.getFullType()) || !gun.isRanged()
                        || gun.getCondition() <= 0) { rejected++; result(r, null, "missing", null); continue; }
                if (reload != null) { rejected++; result(r, gun, "reloading", null); continue; }
                if (r.action() == GunAction.RELOAD) startReload(p, r, gun);
                else fire(p, r, gun);
            } catch (Exception e) { failures++; Log.error("native gun action failed", e); result(r, null, "failed", null); }
        }
        if (loading != null && clock >= loading.next) {
            try { stepLoading(p); }
            catch (Exception e) { failures++; Log.error("native magazine loading failed", e); loading = null; }
        }
        if (reload != null) {
            var cues = reload.empty ? CUES_EMPTY : CUES;
            double pace = (reload.end - reload.start) / (reload.empty ? RELOAD_EMPTY_SECONDS : RELOAD_SECONDS);
            while (reloadCue < cues.length && clock >= reload.start + cues[reloadCue][0] * pace) {
                if (SteveControl.ownsSurvival(p) && p.getPrimaryHandItem() instanceof HandWeapon gun) {
                    String name = switch ((int) cues[reloadCue][1]) {
                        case 0 -> gun.getEjectAmmoSound(); case 1 -> gun.getInsertAmmoSound(); case 2 -> gun.getRackSound();
                        default -> gun.getInsertAmmoStopSound(); };
                    sound(p, name);
                }
                reloadCue++;
            }
        }
        if (reload != null && clock >= reload.end) {
            Reload pending = reload; reload = null;
            try { finishReload(p, pending); }
            catch (Exception e) { failures++; Log.error("native gun reload failed", e); result(pending.request, null, "failed", null); }
        }
    }

    // ---- magazines ----

    /** Native ReloadSpeed: 0.8 plus 0.1 per Reloading level (as the gun reload below). */
    private static double reloadSpeed(IsoPlayer p) { return .8 + p.getPerkLevel(PerkFactory.Perks.Reloading) * .1; }

    private static InventoryItem magazineById(IsoPlayer p, int id) {
        for (var i : new ArrayList<>(p.getInventory().getItems())) if (i != null && i.getID() == id && MAGAZINE.equals(i.getFullType())) return i;
        return null;
    }

    private static void startLoading(IsoPlayer p, GunAction r) {
        InventoryItem mag = Gameplay.findWeapon(p, r.weaponType(), r.itemId());
        if (mag == null || !MAGAZINE.equals(mag.getFullType()) || mag.getAmmoType() == null || mag.getMaxAmmo() <= 0) {
            rejected++; result(r, null, "missing", null); return;
        }
        if (reload != null || loading != null) { rejected++; result(r, mag, "busy", null); return; }
        boolean unload = r.action() == GunAction.UNLOAD_MAGAZINE;
        String key = mag.getAmmoType().getItemKey();
        if (unload ? mag.getCurrentAmmoCount() <= 0 : mag.getCurrentAmmoCount() >= mag.getMaxAmmo()) {
            rejected++; result(r, mag, unload ? "magazine empty" : "magazine full", null); return;
        }
        if (!unload && !p.getInventory().containsWithModule(key)) { rejected++; result(r, mag, "no rounds", null); return; }
        // the native animation inserts its first round at half of the loop, then one per loop (base 500 / 550 ms, scaled by ReloadSpeed)
        loading = new Load(r, mag.getID(), key, unload, clock + .5 / reloadSpeed(p), 0, r.action() == GunAction.LOAD_ONE_ROUND ? 1 : Integer.MAX_VALUE);
        result(r, mag, unload ? "unloading" : "loading", null);
    }

    private static void stepLoading(IsoPlayer p) {
        Load l = loading;
        InventoryItem mag = magazineById(p, l.magazineId);
        if (mag == null) { stopLoading(p, "cancelled"); return; }
        if (l.unload ? mag.getCurrentAmmoCount() <= 0 : mag.getCurrentAmmoCount() >= mag.getMaxAmmo()) { stopLoading(p, l.unload ? "unloaded" : "loaded"); return; }
        if (l.unload) {
            InventoryItem round = InventoryItemFactory.CreateItem(l.ammoKey);
            if (round == null) { stopLoading(p, "invalid ammo"); return; }
            mag.setCurrentAmmoCount(mag.getCurrentAmmoCount() - 1);
            p.getInventory().AddItem(round);
            roundsUnloaded++;
        } else {
            if (p.getInventory().RemoveOneOf(l.ammoKey, true) == null) { stopLoading(p, "no rounds"); return; }
            mag.setCurrentAmmoCount(mag.getCurrentAmmoCount() + 1);
            roundsLoaded++;
        }
        p.getInventory().setDirty(true);
        sound(p, "MagazineInsertAmmo");
        loading = new Load(l.request, l.magazineId, l.ammoKey, l.unload, clock + .55 / reloadSpeed(p), l.moved + 1, l.limit);
        if (l.moved + 1 >= l.limit) stopLoading(p, l.unload ? "unloaded" : "loaded");
        else if (l.unload ? mag.getCurrentAmmoCount() <= 0 : mag.getCurrentAmmoCount() >= mag.getMaxAmmo()) stopLoading(p, l.unload ? "unloaded" : "loaded");
    }

    private static void stopLoading(IsoPlayer p, String status) {
        Load l = loading;
        loading = null;
        if (l == null) return;
        result(l.request, magazineById(p, l.magazineId), status + " (" + l.moved + " round" + (l.moved == 1 ? "" : "s") + (l.unload ? " out" : " in") + ")", null);
    }

    private static void startReload(IsoPlayer p, GunAction r, HandWeapon gun) {
        if (gun.isJammed()) {
            // Native unjam/rack preserves any live chambered round and does not invent ammunition.
            if (gun.checkUnJam(p)) { rack(gun); sound(p, gun.getRackSound()); result(r, gun, "unjammed", "reload"); }
            else result(r, gun, "jammed", null);
            return;
        }
        if (!gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
            rack(gun); sound(p, gun.getRackSound()); result(r, gun, "racked", "reload"); return;
        }
        InventoryItem mag = gun.getBestMagazine(p);
        if (mag == null || mag.getCurrentAmmoCount() <= 0
                || (gun.isContainsClip() && mag.getCurrentAmmoCount() <= gun.getCurrentAmmoCount())) {
            rejected++; result(r, gun, "no loaded magazine", null); return;
        }
        if (mag.getCurrentAmmoCount() > gun.getMaxAmmo()) { rejected++; result(r, gun, "invalid magazine", null); return; }
        double speed = .8 + p.getPerkLevel(PerkFactory.Perks.Reloading) * .1;   // native Reloading skill modifier
        boolean empty = !gun.isRoundChambered();
        double seconds = (empty ? RELOAD_EMPTY_SECONDS : RELOAD_SECONDS) * .8 / speed;
        reload = new Reload(r, mag.getID(), mag.getCurrentAmmoCount(), gun.getCurrentAmmoCount(), gun.isRoundChambered(), clock, clock + seconds, empty);
        reloadCue = 0;
        result(r, gun, "reload started", "reload", .8 / speed);
    }

    private static void finishReload(IsoPlayer p, Reload r) {
        var item = Gameplay.findWeapon(p, r.request.weaponType(), r.request.itemId());
        if (!(item instanceof HandWeapon gun) || gun.getCurrentAmmoCount() != r.oldRounds || gun.isRoundChambered() != r.oldChamber) {
            cancelled++; result(r.request, null, "reload cancelled", null); return;
        }
        InventoryItem magazine = null;
        for (var i : p.getInventory().getItems()) if (i.getID() == r.magazineId) magazine = i;
        if (magazine == null || !magazine.getFullType().equals(gun.getMagazineType()) || magazine.getCurrentAmmoCount() != r.rounds) {
            cancelled++; result(r.request, gun, "reload cancelled", null); return;
        }
        InventoryItem old = null;
        if (gun.isContainsClip()) {
            old = InventoryItemFactory.CreateItem(gun.getMagazineType());
            if (old == null) { rejected++; result(r.request, gun, "invalid magazine type", null); return; }
            old.setCurrentAmmoCount(gun.getCurrentAmmoCount());
        }
        // Same representation as native ISEjectMagazine/ISInsertMagazine: the inserted clip is in weapon fields.
        p.getInventory().Remove(magazine);
        if (old != null) p.getInventory().AddItem(old);
        gun.setContainsClip(true); gun.setCurrentAmmoCount(r.rounds);
        if (!gun.isRoundChambered()) rack(gun);
        p.getInventory().setDirty(true); reloaded++;
        result(r.request, gun, "reloaded", null);
    }

    private static void rack(HandWeapon gun) {
        if (gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
            gun.setRoundChambered(true); gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot());
        }
    }

    private static void fire(IsoPlayer p, GunAction r, HandWeapon gun) throws Exception {
        if (clock < nextShot) { rejected++; result(r, gun, "cooldown", null); return; }
        if (gun.isJammed() || (gun.haveChamber() ? !gun.isRoundChambered() : gun.getCurrentAmmoCount() < gun.getAmmoPerShoot())) {
            nextShot = clock + .2; dry++; sound(p, gun.getClickSound()); result(r, gun, gun.isJammed() ? "jammed" : "empty", null); return;
        }
        double distance = Math.hypot(r.x() - p.getX(), r.z() - p.getY());
        double range = gun.getMaxRange(p) * gun.getRangeMod(p);
        // A server ray can extend beyond native range; this still fires a real round, but cannot damage beyond it.
        IsoGameCharacter target = Gameplay.actor(r.actorId());
        InventoryItem primary = p.getPrimaryHandItem(), secondary = p.getSecondaryHandItem();
        boolean critical = p.isCriticalHit(), shove = p.isDoShove(), floor = p.isAimAtFloor();
        try {
            p.setPrimaryHandItem(gun); p.setSecondaryHandItem(gun.isTwoHandWeapon() ? gun : null);
            p.setDoShove(false); p.setAimAtFloor(false);
            nextShot = clock + Math.max(.1, gun.getRecoilDelay(p) / 30.0);
            if (gun.haveChamber()) { gun.setRoundChambered(false); rack(gun); }
            else gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot());
            sound(p, gun.getSwingSound());
            p.addWorldSoundUnlessInvisible(gun.getSoundRadius(), gun.getSoundVolume(), false); noise++;
            Gameplay.rollWear(p, gun); shots++;
            if (target != null && !target.isDead() && distance <= range + .1
                    && Math.abs(ActorTerrain.mcY(target) - pzcraft.protocol.Coords.mcY(p.getZ())) <= pzcraft.protocol.Coords.BLOCKS_PER_LEVEL) {
                var los = LosUtil.lineClear(IsoWorld.instance.getCell(), (int)p.getX(), (int)p.getY(), (int)p.getZ(),
                        (int)target.getX(), (int)target.getY(), (int)target.getZ(), false);
                if (los == LosUtil.TestResults.Clear || los == LosUtil.TestResults.ClearThroughOpenDoor) {
                    HitInfo info = new HitInfo().init(target, 1, (float)(distance * distance), target.getX(), target.getY(), target.getZ());
                    if (hitChance == null) {
                        hitChance = CombatManager.class.getDeclaredMethod("calculateHitChanceData", IsoGameCharacter.class, HandWeapon.class, HitInfo.class);
                        hitChance.setAccessible(true);
                    }
                    Object chanceData = hitChance.invoke(CombatManager.getInstance(), p, gun, info);
                    if (chanceField == null) {
                        chanceField = chanceData.getClass().getDeclaredField("hitChance"); chanceField.setAccessible(true);
                    }
                    float chance = chanceField.getFloat(chanceData);
                    if (Rand.Next(100) < chance) {
                        p.setCriticalHit(Rand.Next(100) < p.calculateCritChance(target));
                        float damage = Rand.Next(gun.getMinDamage(), gun.getMaxDamage()) * gun.getDamageMod(p) * p.getHittingMod();
                        target.Hit(gun, p, damage, false, 1, false); hits++;
                    }
                }
            } else if (r.impactKind() == GunAction.PZ_SURFACE && distance <= range + .1) impactWindow(r);
            gun.checkJam(p, false);
            result(r, gun, "fired", "fire");
        } finally {
            p.setPrimaryHandItem(primary); p.setSecondaryHandItem(secondary);
            p.setCriticalHit(critical); p.setDoShove(shove); p.setAimAtFloor(floor);
        }
    }

    private static void impactWindow(GunAction r) {
        int x = (int)Math.floor(r.x()), y = (int)Math.floor(r.z()), z = (int)Math.floor(Coords.pzZ(r.y()));
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
            var square = IsoWorld.instance.getCell().getGridSquare(x + dx, y + dy, z);
            if (square == null) continue;
            for (var obj : new ArrayList<>(square.getSpecialObjects())) {
                if (!(obj instanceof IsoWindow w) || w.IsOpen() || w.isDestroyed()) continue;
                double across = w.getNorth() ? r.z() - square.y : r.x() - square.x;
                double along = w.getNorth() ? r.x() - square.x : r.z() - square.y;
                if (Math.abs(across) < .15 && along >= 0 && along <= 1) {
                    w.smashWindow(); windows++; WorldExporter.changed(); return;
                }
            }
        }
    }

    private static void sound(IsoPlayer p, String sound) { if (sound != null && !sound.isBlank()) p.getEmitter().playSoundImpl(sound, null); }

    private static void result(GunAction r, InventoryItem item, String status, String clip) { result(r, item, status, clip, 1.0); }

    /** {@code pace}: how much longer than the clip's own length the action takes (the Reloading skill makes it shorter than 1). */
    private static void result(GunAction r, InventoryItem item, String status, String clip, double pace) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("sequence", r.sequence()); out.put("itemId", r.itemId()); out.put("status", status);
        if (clip != null) out.put("clip", clip);
        if (pace != 1.0) out.put("pace", pace);
        if (item != null) {
            out.put("ammo", item.getCurrentAmmoCount()); out.put("capacity", item.getMaxAmmo());
            if (item instanceof HandWeapon gun) { out.put("chamber", gun.isRoundChambered()); out.put("jammed", gun.isJammed()); }
        }
        last = out;
        LinkService.send(Wire.MSG_GUN_RESULT, JsonLite.write(out).getBytes(StandardCharsets.UTF_8));
    }

    static Map<String,Object> stats() {
        var out = new LinkedHashMap<String,Object>();
        out.put("shots", shots); out.put("dry", dry); out.put("hits", hits); out.put("noise", noise);
        out.put("reloads", reloaded); out.put("cancelled", cancelled); out.put("rejected", rejected);
        out.put("windows", windows); out.put("failures", failures); out.put("reloading", reload != null); out.put("last", last);
        out.put("loading", loading != null); out.put("roundsLoaded", roundsLoaded); out.put("roundsUnloaded", roundsUnloaded);
        return out;
    }
}

