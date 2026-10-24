package pzcraft.pz;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import zombie.scripting.objects.WeaponReloadType;

/**
 * Native firearm state machine for every PZ gun (pistols, revolvers, rifles, shotguns). Follows PZ's own rules: a shot is
 * {@code ISReloadWeaponAction.onShoot} (chamber, spent round, ammo, jam), pump/bolt/lever guns are racked after each shot, a
 * gun with a magazine swaps magazines and a gun without one takes rounds one at a time with the reload type's own pace. Never
 * edits a different weapon or consumes ammunition for a rejected action.
 */
final class GunBridge {
    private static final ConcurrentLinkedQueue<GunAction> requests = new ConcurrentLinkedQueue<>();
    private record Reload(GunAction request, int magazineId, int rounds, int oldRounds, boolean oldChamber, double start, double end, boolean empty,
                          double[][] cues, double phase) {}
    private static Reload reload;
    private static int reloadCue;
    /**
     * What is heard when during the default reload motion (seconds after the clip starts; magazine out, magazine in, slide
     * release, the gun coming back up), so the sounds follow the animation instead of the end of PZ's timed action. The
     * sounds themselves are PZ's own (this weapon's script): 0 eject, 1 insert, 2 rack, 3 insert stop.
     */
    private static final double[][] CUES = {{.3667, 0}, {.6667, 1}, {1.1, 3}};
    private static final double[][] CUES_EMPTY = {{.1, 0}, {.6333, 1}, {.9167, 2}, {1.1, 3}};
    /**
     * I: inspect the weapon, with this weapon's PZ sound cues: slide back/forward = rack, the gun coming back up = insert stop. Not a busy state: a
     * shot, reload, eject or weapon swap simply ends it without blocking the next action.
     */
    private record Inspect(int itemId, double start, boolean empty, double[][] cues) {}
    private static Inspect inspect;
    private static int inspectCue;
    private static final double[][] INSPECT_CUES = {{4.1167, 2}, {4.8833, 3}};
    private static final double[][] INSPECT_CUES_EMPTY = {{4.1167, 2}, {4.7333, 2}, {5.25, 3}};
    /** How long the gun is busy: the clip's reload phase (1.58 s, or 1.68 s with an empty chamber), at the Reloading skill's pace. */
    private static final double RELOAD_SECONDS = 1.58, RELOAD_EMPTY_SECONDS = 1.68;
    /** Loading rounds into (or unloading them from) a magazine, one round at a time: native ISLoadBulletsInMagazine / ISUnloadBulletsFromMagazine. */
    private record Load(GunAction request, int magazineId, String ammoKey, boolean unload, double next, int moved, int limit) {}
    private static Load loading;
    /**
     * Rounds going into (or out of) a gun that has no magazine, one per loop of the reload type's own pace (native
     * ISReloadWeaponAction / ISUnloadBulletsFromFirearm; a double-barrel takes its whole load in one go).
     */
    private record Rounds(GunAction request, int itemId, String ammoKey, boolean unload, double next, double per, int moved, boolean all) {}
    private static Rounds rounds;
    /**
     * Working the action (pump, bolt, lever, slide): after every shot of a rack-after-shoot gun and after loading an unchambered one.
     * {@code free}: it follows a creative Steve's shot, so the round it chambers is not taken from the magazine.
     */
    private record Rack(GunAction request, int itemId, double start, double end, boolean soundPlayed, boolean closeOnly, boolean free) {}
    private static Rack rack;
    private static final pzcraft.protocol.PlayerState steve = new pzcraft.protocol.PlayerState();
    /** Shift+R on a gun whose model has a clip for taking the magazine out: the magazine leaves when the clip's hand has it. */
    private record Eject(GunAction request, int itemId, double at) {}
    private static Eject eject;
    private static long lastSequence;
    private static int epoch = -1;
    private static double clock, nextShot;
    private static long lastNanos = System.nanoTime();
    private static int shots, dry, hits, noise, reloaded, cancelled, rejected, windows, failures, roundsLoaded, roundsUnloaded, ejected, inspects, inspectSounds, racks, gunRoundsLoaded;
    private static boolean ejectedNow, hitNow, killedNow;
    private static float damageNow;
    private static List<Map<String, Object>> hitsNow;
    /** Timing for the client's animation, sent with the next answer (reload length, a round's loop, the rack stroke). */
    private static Map<String, Object> extraNow;
    private static volatile Map<String,Object> last = Map.of();
    private static Method hitChance;
    private static java.lang.reflect.Field chanceField;

    private GunBridge() {}
    static void receive(GunAction request) { if (requests.size() < 128) requests.add(request); }

    private static void idle() { reload = null; loading = null; rounds = null; rack = null; inspect = null; eject = null; }

    static void tick(IsoPlayer p) {
        long now = System.nanoTime();
        double elapsed = Math.min(.1, Math.max(0, (now - lastNanos) / 1_000_000_000.0));
        lastNanos = now;
        if (!SteveControl.ownsSurvival(p) || !LinkService.minecraftReady()) {
            requests.clear(); idle(); return;
        }
        if (epoch != LinkService.epoch()) {
            epoch = LinkService.epoch(); lastSequence = 0; idle(); nextShot = 0;
        }
        if (PauseFreeze.pzPaused()) return;
        clock += elapsed; // FrameTick may run more often than GameTime updates; never reuse its last delta.
        GunAction r;
        while ((r = requests.poll()) != null) {
            try {
                if (r.sequence() <= lastSequence) { rejected++; continue; }
                lastSequence = r.sequence();
                if (r.action() != GunAction.INSPECT) inspect = null;   // anything else done with the gun ends the inspection
                if (r.action() == GunAction.CANCEL) {
                    if (reload != null && reload.request.itemId() == r.itemId()) { reload = null; cancelled++; }
                    if (loading != null && loading.request.itemId() == r.itemId()) stopLoading(p, "stopped");
                    if (rounds != null && rounds.itemId == r.itemId()) cancelRounds(p, "stopped");
                    if (rack != null && rack.itemId == r.itemId()) { rack = null; cancelled++; }
                    if (eject != null && eject.itemId == r.itemId()) { eject = null; cancelled++; }   // the magazine stays in
                    continue;
                }
                if (r.action() == GunAction.LOAD_MAGAZINE || r.action() == GunAction.UNLOAD_MAGAZINE || r.action() == GunAction.LOAD_ONE_ROUND) { startLoading(p, r); continue; }
                InventoryItem item = Gameplay.findWeapon(p, r.weaponType(), r.itemId());
                if (!(item instanceof HandWeapon gun) || !GunTypes.firearm(gun) || gun.getCondition() <= 0) { rejected++; result(r, null, "missing", null); continue; }
                // Shooting while the rounds go in stops the loading, as the native attack hook clears the action queue.
                if (rounds != null && r.action() == GunAction.FIRE) cancelRounds(p, "stopped");
                // Reload while loading rounds: stop and work the action (native OnPressReloadButton while isLoading).
                if (rounds != null && r.action() == GunAction.RELOAD) { cancelRounds(p, "stopped"); startRack(r, gun, false); continue; }
                if (reload != null) { rejected++; result(r, gun, "reloading", null); continue; }
                if (rounds != null) { rejected++; result(r, gun, "loading", null); continue; }
                if (rack != null && r.action() != GunAction.INSPECT) { rejected++; result(r, gun, "racking", null); continue; }
                if (eject != null) { rejected++; result(r, gun, "ejecting", null); continue; }
                if (r.action() == GunAction.RELOAD) startReload(p, r, gun);
                else if (r.action() == GunAction.EJECT_MAGAZINE) ejectRequested(p, r, gun);
                else if (r.action() == GunAction.INSPECT) inspectRequested(r, gun);
                else if (r.action() == GunAction.FIRE_MODE) fireModeRequested(r, gun);
                else if (r.action() == GunAction.RACK) startRack(r, gun, false);      // X: the same stroke as a reload that starts by working the action
                else fire(p, r, gun);
            } catch (Exception e) { failures++; Log.error("native gun action failed", e); result(r, null, "failed", null); }
        }
        if (loading != null && clock >= loading.next) {
            try { stepLoading(p); }
            catch (Exception e) { failures++; Log.error("native magazine loading failed", e); loading = null; }
        }
        if (rounds != null && clock >= rounds.next) {
            try { stepRounds(p); }
            catch (Exception e) { failures++; Log.error("native round loading failed", e); rounds = null; }
        }
        if (reload != null) {
            var cues = reload.cues;
            double pace = (reload.end - reload.start) / reload.phase;
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
        if (inspect != null) {
            var cues = inspect.cues;
            while (inspectCue < cues.length && clock >= inspect.start + cues[inspectCue][0]) {
                if (p.getPrimaryHandItem() instanceof HandWeapon gun && gun.getID() == inspect.itemId) {
                    sound(p, cues[inspectCue][1] == 2 ? gun.getRackSound() : gun.getInsertAmmoStopSound());
                    inspectSounds++;
                }
                inspectCue++;
            }
            if (inspectCue >= cues.length) inspect = null;
        }
        if (rack != null) {
            if (!rack.soundPlayed && clock >= rack.start) {
                if (itemById(p, rack.itemId) instanceof HandWeapon gun) sound(p, gun.getRackSound());
                rack = new Rack(rack.request, rack.itemId, rack.start, rack.end, true, rack.closeOnly, rack.free);
            }
            if (rack != null && clock >= rack.end) {
                Rack pending = rack; rack = null;
                try { finishRack(p, pending); }
                catch (Exception e) { failures++; Log.error("native gun rack failed", e); }
            }
        }
        if (eject != null && clock >= eject.at) {
            Eject pending = eject; eject = null;
            try {
                if (itemById(p, pending.itemId) instanceof HandWeapon gun && gun.isContainsClip() && ejectMagazine(p, gun)) result(pending.request, gun, "magazine ejected", null);
                else { cancelled++; result(pending.request, null, "eject cancelled", null); }
            } catch (Exception e) { failures++; Log.error("native magazine eject failed", e); result(pending.request, null, "failed", null); }
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

    /** Any item of the player's inventory by its PZ id (a gun, whatever its type). */
    private static InventoryItem itemById(IsoPlayer p, int id) {
        for (var i : new ArrayList<>(p.getInventory().getItems())) if (i != null && i.getID() == id) return i;
        return null;
    }

    private static InventoryItem magazineById(IsoPlayer p, int id) {
        for (var i : new ArrayList<>(p.getInventory().getItems())) if (i != null && i.getID() == id && GunTypes.magazine(i.getFullType())) return i;
        return null;
    }

    private static void startLoading(IsoPlayer p, GunAction r) {
        InventoryItem mag = Gameplay.findWeapon(p, r.weaponType(), r.itemId());
        if (mag == null || !GunTypes.magazine(mag.getFullType()) || mag.getAmmoType() == null || mag.getMaxAmmo() <= 0) {
            rejected++; result(r, null, "missing", null); return;
        }
        if (reload != null || loading != null || rounds != null) { rejected++; result(r, mag, "busy", null); return; }
        boolean unload = r.action() == GunAction.UNLOAD_MAGAZINE;
        String key = mag.getAmmoType().getItemKey();
        if (unload ? mag.getCurrentAmmoCount() <= 0 : mag.getCurrentAmmoCount() >= mag.getMaxAmmo()) {
            rejected++; result(r, mag, unload ? "magazine empty" : "magazine full", null); return;
        }
        if (!unload && !p.getInventory().containsWithModule(key)) { rejected++; result(r, mag, "no rounds", null); return; }
        // the native animation inserts its first round at half of the loop, then one per loop (base 500 / 550 ms, scaled by ReloadSpeed)
        loading = new Load(r, mag.getID(), key, unload, clock + .5 / reloadSpeed(p), 0, r.action() == GunAction.LOAD_ONE_ROUND ? 1 : Integer.MAX_VALUE);
        // when the first round moves and how long each one after it takes, for a model that shows the hand doing it
        extraNow = new LinkedHashMap<>(); extraNow.put("first", .5 / reloadSpeed(p)); extraNow.put("per", .55 / reloadSpeed(p));
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
            // Native unjam/rack preserves any live chambered round and does not invent ammunition. checkUnJam answers whether the
            // gun is STILL jammed after the attempt (ISRackFirearm returns when it is).
            if (!gun.checkUnJam(p)) { rack(gun); sound(p, gun.getRackSound()); result(r, gun, "unjammed", "reload"); }
            else result(r, gun, "jammed", null);
            return;
        }
        if (gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
            rack(gun); sound(p, gun.getRackSound()); result(r, gun, "racked", gun.getMagazineType() == null ? "rack" : "reload"); return;
        }
        if (gun.getMagazineType() == null) { startRounds(p, r, gun, false); return; }
        InventoryItem mag = gun.getBestMagazine(p);
        if (mag == null || mag.getCurrentAmmoCount() <= 0
                || (gun.isContainsClip() && mag.getCurrentAmmoCount() <= gun.getCurrentAmmoCount())) {
            rejected++; result(r, gun, "no loaded magazine", null); return;
        }
        if (mag.getCurrentAmmoCount() > gun.getMaxAmmo()) { rejected++; result(r, gun, "invalid magazine", null); return; }
        double speed = .8 + p.getPerkLevel(PerkFactory.Perks.Reloading) * .1;   // native Reloading skill modifier
        boolean empty = !gun.isRoundChambered();
        // The gun's own model says how long its magazine change is and when its sounds belong (the reload phase of its clip, the
        // clip's sound marks); without that, the M9 clip's timing, longer for a two-handed gun. Either way at the Reloading
        // skill's pace, and a model that has no empty-chamber clip plays the ordinary one.
        GunAction.Timing hint = r.timing(empty);
        if (hint == null && empty) hint = r.timing(false);
        if (hint != null && (hint.phase() < .4 || hint.phase() > 8)) hint = null;
        double phase = hint != null ? hint.phase() : (empty ? RELOAD_EMPTY_SECONDS : RELOAD_SECONDS);
        double scale = hint == null && gun.isTwoHandWeapon() ? 1.45 : 1.0;
        double seconds = phase * scale * .8 / speed;
        reload = new Reload(r, mag.getID(), mag.getCurrentAmmoCount(), gun.getCurrentAmmoCount(), gun.isRoundChambered(), clock, clock + seconds, empty,
                hint != null ? hint.cues() : empty ? CUES_EMPTY : CUES, phase);
        reloadCue = 0;
        extraNow = new LinkedHashMap<>(); extraNow.put("seconds", seconds);
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

    // ---- rounds into a gun without a magazine ----

    /** The native loop length for one round, by reload type (ISReloadWeaponAction.serverStart), before the Reloading skill. */
    private static double roundSeconds(HandWeapon gun) {
        WeaponReloadType type = gun.getWeaponReloadType();
        if (type == WeaponReloadType.SHOTGUN) return .833;
        if (type == WeaponReloadType.REVOLVER) return .95;
        if (type == WeaponReloadType.BOLT_ACTION_NO_MAG) return .59;
        if (type == WeaponReloadType.DOUBLE_BARREL_SHOTGUN || type == WeaponReloadType.DOUBLE_BARREL_SHOTGUN_SAWN) return 2.5;
        return 1.0;
    }

    private static void startRounds(IsoPlayer p, GunAction r, HandWeapon gun, boolean unload) {
        String key = gun.getAmmoType() == null ? null : gun.getAmmoType().getItemKey();
        if (key == null) { rejected++; result(r, gun, "no ammunition type", null); return; }
        if (unload ? gun.getCurrentAmmoCount() <= 0 && !gun.isRoundChambered() : gun.getCurrentAmmoCount() >= gun.getMaxAmmo()) {
            rejected++; result(r, gun, unload ? "gun empty" : "gun full", null); return;
        }
        if (!unload && !p.getInventory().containsWithModule(key)) { rejected++; result(r, gun, "no rounds", null); return; }
        if (!unload) ejectSpent(p, gun);   // native ISReloadWeaponAction:start
        double per = roundSeconds(gun) / reloadSpeed(p);
        boolean all = !unload && gun.isInsertAllBulletsReload();
        // The gun's own model says how long opening the action takes and how long one round's trip of the hand is (sent as the
        // two "phases" of the request's timing): the first round goes in no sooner than that, at the Reloading skill's pace.
        GunAction.Timing open = r.timing(false), trip = r.timing(true);
        double first = per;
        if (open != null && trip != null && open.phase() > 0 && open.phase() < 4 && trip.phase() > 0 && trip.phase() < 4)
            first = Math.max(per, open.phase() * .8 / reloadSpeed(p) + Math.min(trip.phase(), .8 * per));
        rounds = new Rounds(r, gun.getID(), key, unload, clock + first, per, 0, all);
        if (!unload) sound(p, gun.getInsertAmmoStartSound());
        extraNow = new LinkedHashMap<>(); extraNow.put("per", per); extraNow.put("first", first); extraNow.put("all", all);
        result(r, gun, unload ? "unloading" : "loading", "load");
    }

    private static void stepRounds(IsoPlayer p) {
        Rounds l = rounds;
        if (!(itemById(p, l.itemId) instanceof HandWeapon gun)) { stopRounds(p, "cancelled"); return; }
        int moved = l.moved;
        boolean done;
        if (l.unload) {
            // one round out per loop, the chambered one last
            InventoryItem round = InventoryItemFactory.CreateItem(l.ammoKey);
            if (round == null) { stopRounds(p, "invalid ammo"); return; }
            if (gun.getCurrentAmmoCount() > 0) gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - 1);
            else if (gun.isRoundChambered()) gun.setRoundChambered(false);
            else { stopRounds(p, "unloaded"); return; }
            p.getInventory().AddItem(round);
            roundsUnloaded++; moved++;
            done = gun.getCurrentAmmoCount() <= 0 && !gun.isRoundChambered();
        } else {
            int room = gun.getMaxAmmo() - gun.getCurrentAmmoCount();
            int take = l.all ? room : 1;
            int put = 0;
            while (put < take && p.getInventory().RemoveOneOf(l.ammoKey, true) != null) put++;
            if (put == 0) { stopRounds(p, "no rounds"); return; }
            gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() + put);
            roundsLoaded += put; gunRoundsLoaded += put; moved += put;
            done = gun.getCurrentAmmoCount() >= gun.getMaxAmmo() || !p.getInventory().containsWithModule(l.ammoKey);
        }
        p.getInventory().setDirty(true);
        sound(p, l.unload ? gun.getEjectAmmoSound() : gun.getInsertAmmoSound());
        if (done) { rounds = new Rounds(l.request, l.itemId, l.ammoKey, l.unload, l.next, l.per, moved, l.all); stopRounds(p, l.unload ? "unloaded" : "loaded"); return; }
        rounds = new Rounds(l.request, l.itemId, l.ammoKey, l.unload, clock + l.per, l.per, moved, l.all);
    }

    /** The rounds are in (or out): the loop ends, and a loaded gun with an empty chamber is racked (native: rack only if no round is chambered). */
    private static void stopRounds(IsoPlayer p, String status) { endRounds(p, status, true); }

    /** The loop was interrupted (a shot, a weapon swap): rounds already inserted stay, nothing is racked or closed. */
    private static void cancelRounds(IsoPlayer p, String status) { endRounds(p, status, false); }

    private static void endRounds(IsoPlayer p, String status, boolean finishAction) {
        Rounds l = rounds;
        rounds = null;
        if (l == null) return;
        var item = itemById(p, l.itemId);
        if (item instanceof HandWeapon gun) {
            sound(p, gun.getInsertAmmoStopSound());
            if (finishAction && !l.unload && l.moved > 0) {
                if (gun.haveChamber() && !gun.isRoundChambered() && !gun.isJammed() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) startRack(l.request, gun, false);
                else if (gun.needToBeClosedOnceReload()) rack = new Rack(l.request, gun.getID(), clock, clock + .7, true, true, false);   // closing the action takes a moment; no round moves
            }
        }
        result(l.request, item, status + " (" + l.moved + " round" + (l.moved == 1 ? "" : "s") + (l.unload ? " out" : " in") + ")", null);
    }

    /** Native ISReloadWeaponAction:ejectSpentRounds (also ISRackFirearm): spent cases fall out when the action is worked. */
    private static void ejectSpent(IsoPlayer p, HandWeapon gun) {
        if (gun.getSpentRoundCount() > 0) gun.setSpentRoundCount(0);
        else if (gun.isSpentRoundChambered()) gun.setSpentRoundChambered(false);
        else return;
        sound(p, gun.getShellFallSound());
    }

    // ---- working the action ----

    private static double rackSeconds(IsoPlayer p) {
        // native: rack speed is 0.8 + 0.04 per Reloading level; the pump/bolt stroke itself takes about 0.85 s at 0.8
        return .85 * .8 / (.8 + p.getPerkLevel(PerkFactory.Perks.Reloading) * .04);
    }

    private static void startRack(GunAction r, HandWeapon gun, boolean afterShot) {
        startRack(r, gun, afterShot, false);
    }

    private static void startRack(GunAction r, HandWeapon gun, boolean afterShot, boolean free) {
        IsoPlayer p = IsoPlayer.getInstance();
        double lead = afterShot ? .3 : 0;   // the shot's own animation finishes before the action is worked
        double seconds = p == null ? .85 : rackSeconds(p);
        rack = new Rack(r, gun.getID(), clock + lead, clock + lead + seconds, false, false, free);
        if (afterShot) { extraNow = new LinkedHashMap<>(); extraNow.put("rackLead", lead); extraNow.put("rackSeconds", seconds); }   // goes out with "fired"
        else { extraNow = new LinkedHashMap<>(); extraNow.put("seconds", seconds); result(r, gun, "racking", "rack"); }
    }

    /** Native ISRackFirearm:rackBullet. */
    private static void finishRack(IsoPlayer p, Rack pending) {
        var item = itemById(p, pending.itemId);
        if (!(item instanceof HandWeapon gun)) return;
        if (pending.closeOnly) return;   // the action was only closed after loading: nothing is chambered, ejected or given back
        ejectSpent(p, gun);
        if (gun.haveChamber()) {
            if (gun.isJammed() && gun.checkUnJam(p)) { result(pending.request, gun, "jammed", null); return; }   // failed to clear it
            if (!gun.isJammed() && gun.isRoundChambered()) giveBackRound(p, gun);
            gun.setRoundChambered(false); gun.setJammed(false);
            if (pending.free || gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
                gun.setRoundChambered(true);
                if (!pending.free) {
                    gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot());
                    if (gun.getJamGunChance() > 0) gun.checkJam(p, true);
                }
            }
        } else {
            if (!gun.isJammed() && gun.getCurrentAmmoCount() > 0) { giveBackRound(p, gun); gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot()); }
            gun.setJammed(false);
        }
        p.getInventory().setDirty(true);
        racks++;
        result(pending.request, gun, "racked", "rack");
    }

    private static void giveBackRound(IsoPlayer p, HandWeapon gun) {
        if (gun.getAmmoType() == null) return;
        InventoryItem round = InventoryItemFactory.CreateItem(gun.getAmmoType().getItemKey());
        if (round != null) p.getInventory().AddItem(round);
    }

    // ---- inspect, eject ----

    /** I: only from rest (not while a magazine is being loaded, right after a shot or while jammed); nothing in the gun changes. */
    private static void inspectRequested(GunAction r, HandWeapon gun) {
        if (loading != null) { rejected++; result(r, gun, "busy", null); return; }
        if (clock < nextShot) { rejected++; result(r, gun, "cooldown", null); return; }
        boolean empty = !gun.isRoundChambered() && gun.getCurrentAmmoCount() <= 0;
        GunAction.Timing hint = r.timing(empty);
        if (hint == null && empty) hint = r.timing(false);
        inspect = new Inspect(gun.getID(), clock, empty, hint != null ? hint.cues() : empty ? INSPECT_CUES_EMPTY : INSPECT_CUES);
        inspectCue = 0;
        inspects++;
        result(r, gun, "inspecting", empty ? "inspectempty" : "inspect");
    }

    /** B: a select-fire gun (the M16) cycles between its modes (native {@code HandWeapon.cycleFireMode}). */
    private static void fireModeRequested(GunAction r, HandWeapon gun) {
        if (!gun.isSelectFire()) { rejected++; result(r, gun, "no fire modes", null); return; }
        String mode = gun.cycleFireMode();
        extraNow = new LinkedHashMap<>(); extraNow.put("mode", mode);
        result(r, gun, "mode " + mode, null);
    }

    /** Full auto fires on a fixed cadence of its own (about 670 rounds a minute); a single shot waits PZ's recoil delay. */
    private static final double AUTO_SECONDS = 0.09;

    /**
     * Shift+R: take the magazine out by hand (a round already in the chamber stays there, as on the real pistol); a gun without a
     * magazine gives its rounds back one at a time instead. Answers "eject started" (clip "unload") and then "magazine ejected"
     * when the model has a clip for it, "magazine ejected" alone otherwise.
     */
    private static void ejectRequested(IsoPlayer p, GunAction r, HandWeapon gun) {
        if (gun.getMagazineType() == null) { startRounds(p, r, gun, true); return; }
        if (!gun.isContainsClip()) { rejected++; result(r, gun, "no magazine", null); return; }
        // The gun's own model says how long taking the magazine out is and when in that clip the magazine leaves (at the Reloading
        // skill's pace, like a reload); the gun is busy until then. Without that the magazine is out at once, as before.
        GunAction.Timing hint = r.timing(false);
        if (hint != null && hint.cues().length > 0 && hint.phase() >= .3 && hint.phase() <= 5 && hint.cues()[0][0] <= hint.phase()) {
            double pace = .8 / reloadSpeed(p);
            eject = new Eject(r, gun.getID(), clock + hint.cues()[0][0] * pace);
            extraNow = new LinkedHashMap<>(); extraNow.put("seconds", hint.phase() * pace);
            result(r, gun, "eject started", "unload", pace);
            return;
        }
        if (ejectMagazine(p, gun)) result(r, gun, "magazine ejected", null);
        else { rejected++; result(r, gun, "invalid magazine type", null); }
    }

    /**
     * Same representation as native ISEjectMagazine: the clip leaves the weapon fields and returns to the inventory as an item
     * holding whatever rounds were left (an empty one after the last shot).
     */
    private static boolean ejectMagazine(IsoPlayer p, HandWeapon gun) {
        InventoryItem mag = InventoryItemFactory.CreateItem(gun.getMagazineType());
        if (mag == null) return false;
        mag.setCurrentAmmoCount(gun.getCurrentAmmoCount());
        gun.setContainsClip(false); gun.setCurrentAmmoCount(0);
        p.getInventory().AddItem(mag);
        p.getInventory().setDirty(true);
        sound(p, gun.getEjectAmmoSound());
        ejected++; ejectedNow = true;
        return true;
    }

    /** A gun with nothing left to fire (no round in the chamber, none in the magazine) drops its empty magazine by itself. */
    private static boolean autoEject(IsoPlayer p, HandWeapon gun) {
        if (gun.getMagazineType() == null || !gun.isContainsClip() || gun.isRoundChambered() || gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) return false;
        return ejectMagazine(p, gun);
    }

    private static void rack(HandWeapon gun) {
        if (gun.haveChamber() && !gun.isRoundChambered() && gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
            gun.setRoundChambered(true); gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot());
        }
        gun.setSpentRoundChambered(false);
    }

    /** Steve is in creative mode (Minecraft's frame flags): his shots use up no rounds (B20). Game thread. */
    private static boolean creative() {
        return LinkService.pullInto(steve) && (steve.flags & pzcraft.protocol.PlayerState.FLAG_CREATIVE) != 0;
    }

    /** Native ISReloadWeaponAction.onShoot, step for step. */
    private static void afterShot(IsoPlayer p, HandWeapon gun) {
        if (gun.haveChamber()) { gun.setRoundChambered(false); gun.setSpentRoundChambered(true); }
        if (!gun.isRackAfterShoot()) {
            // automatic weapons throw the case out by themselves
            if (!gun.isManuallyRemoveSpentRounds()) { gun.setSpentRoundChambered(false); sound(p, gun.getShellFallSound()); }
            if (gun.getCurrentAmmoCount() >= gun.getAmmoPerShoot()) {
                if (gun.haveChamber()) gun.setRoundChambered(true);
                gun.setCurrentAmmoCount(gun.getCurrentAmmoCount() - gun.getAmmoPerShoot());
                if (gun.getJamGunChance() > 0) gun.checkJam(p, false);
            }
        }
        if (gun.isManuallyRemoveSpentRounds()) gun.setSpentRoundCount(gun.getSpentRoundCount() + gun.getAmmoPerShoot());
    }

    private static void fire(IsoPlayer p, GunAction r, HandWeapon gun) throws Exception {
        if (clock < nextShot) { rejected++; result(r, gun, "cooldown", null); return; }
        if (gun.isJammed() || (gun.haveChamber() ? !gun.isRoundChambered() : gun.getCurrentAmmoCount() < gun.getAmmoPerShoot())) {
            nextShot = clock + .2; dry++; sound(p, gun.getClickSound());
            if (!gun.isJammed()) autoEject(p, gun);   // an empty magazine still in the gun comes out on the click
            result(r, gun, gun.isJammed() ? "jammed" : "empty", null); return;
        }
        double distance = Math.hypot(r.x() - p.getX(), r.z() - p.getY());
        double range = gun.getMaxRange(p) * gun.getRangeMod(p);
        // Every distinct actor the shot reached (the nearest first), up to the weapon's own maximum hit count.
        List<Integer> actorIds = new ArrayList<>();
        if (r.actorId() >= 0) actorIds.add(r.actorId());
        for (int t : r.targets()) if (t >= 0 && !actorIds.contains(t)) actorIds.add(t);
        InventoryItem primary = p.getPrimaryHandItem(), secondary = p.getSecondaryHandItem();
        boolean critical = p.isCriticalHit(), shove = p.isDoShove(), floor = p.isAimAtFloor();
        try {
            p.setPrimaryHandItem(gun); p.setSecondaryHandItem(gun.isTwoHandWeapon() ? gun : null);
            p.setDoShove(false); p.setAimAtFloor(false);
            nextShot = clock + (gun.isSelectFire() && "Auto".equals(gun.getFireMode()) ? AUTO_SECONDS : Math.max(.1, gun.getRecoilDelay(p) / 30.0));
            boolean free = creative();
            int roundsBefore = gun.getCurrentAmmoCount(), spentBefore = gun.getSpentRoundCount();
            afterShot(p, gun);
            if (free && !gun.isRackAfterShoot()) {
                // creative: the round fired is put straight back (the chamber refilled, no case to throw out, no jam)
                gun.setCurrentAmmoCount(roundsBefore); gun.setSpentRoundCount(spentBefore); gun.setJammed(false);
                if (gun.haveChamber()) { gun.setRoundChambered(true); gun.setSpentRoundChambered(false); }
            }
            sound(p, gun.getSwingSound());
            p.addWorldSoundUnlessInvisible(gun.getSoundRadius(), gun.getSoundVolume(), false); noise++;
            Gameplay.rollWear(p, gun); shots++;
            if (actorIds.isEmpty()) {
                if (r.impactKind() == GunAction.PZ_SURFACE && distance <= range + .1) impactWindow(r);
            } else {
                int considered = 0;
                List<Map<String, Object>> hitList = new ArrayList<>();
                for (int id : actorIds) {
                    if (considered >= Math.max(1, gun.getMaxHitCount())) break;
                    IsoGameCharacter target = Gameplay.actor(id);
                    if (target == null || target.isDead()) continue;
                    considered++;
                    double d = Math.hypot(target.getX() - p.getX(), target.getY() - p.getY());
                    if (d > range + .1 || Math.abs(ActorTerrain.mcY(target) - pzcraft.protocol.Coords.mcY(p.getZ())) > pzcraft.protocol.Coords.BLOCKS_PER_LEVEL) continue;
                    var los = LosUtil.lineClear(IsoWorld.instance.getCell(), (int)p.getX(), (int)p.getY(), (int)p.getZ(),
                            (int)target.getX(), (int)target.getY(), (int)target.getZ(), false);
                    if (los != LosUtil.TestResults.Clear && los != LosUtil.TestResults.ClearThroughOpenDoor) continue;
                    HitInfo info = new HitInfo().init(target, 1, (float)(d * d), target.getX(), target.getY(), target.getZ());
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
                        boolean killed = target.isDead() || target.getHealth() <= 0f;
                        Map<String, Object> h = new LinkedHashMap<>();
                        h.put("actor", id); h.put("damage", damage); h.put("killed", killed);
                        hitList.add(h);
                        hitNow = true; damageNow += damage; killedNow |= killed;
                    }
                }
                hitsNow = hitList;
            }
            autoEject(p, gun);
            // pump, bolt and lever guns are worked after every shot (native OnPlayerAttackFinished queues ISRackFirearm); the answer
            // carries when, so the client plays the stroke right after the shot's own animation
            if (gun.isRackAfterShoot()) startRack(r, gun, true, free);
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
        if (ejectedNow) { out.put("ejected", true); ejectedNow = false; }
        // What the shot did to its target, for the client's impact effects (blood only when PZ's own roll hit and hurt).
        if (extraNow != null) { out.putAll(extraNow); extraNow = null; }
        if (hitNow) {
            out.put("hit", true); out.put("damage", damageNow); out.put("killed", killedNow);
            if (hitsNow != null) out.put("hits", hitsNow);
            hitNow = killedNow = false; damageNow = 0f; hitsNow = null;
        }
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
        out.put("magazinesEjected", ejected); out.put("inspects", inspects); out.put("inspectSounds", inspectSounds);
        out.put("gunRoundsLoaded", gunRoundsLoaded); out.put("racks", racks); out.put("loadingRounds", rounds != null); out.put("racking", rack != null); out.put("ejecting", eject != null);
        return out;
    }
}
