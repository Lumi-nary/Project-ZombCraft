package pzcraft.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.GunAction;
import pzcraft.protocol.Wire;

/** Single M9 integration. Integrated server chooses the ray; native PZ confirms every gameplay action. */
public final class PzGuns {
    private static final AtomicLong sequence = new AtomicLong();
    private static final ConcurrentLinkedQueue<JsonObject> results = new ConcurrentLinkedQueue<>();
    private static volatile JsonObject last = new JsonObject();
    private static int heldId = -1;
    /** Times an off-hand item was sent back to the inventory because the pistol was in the main hand (test counter). */
    static volatile int offhandCleared;
    /**
     * What the server's ray found, kept by sequence until PZ confirms the shot: the tracer runs {@code from} (near the muzzle) to
     * {@code to}; {@code normal} is the surface's outward direction (null in the air or on a living target); {@code block} the
     * Minecraft block hit; {@code target} the box of the native actor in the way, which the impact effects (blood) are built on.
     */
    record Shot(Vec3 from, Vec3 to, Vec3 normal, net.minecraft.core.BlockPos block, AABB target, Vec3 eye, Vec3 dir,
                java.util.List<Impact> impacts, java.util.List<Vec3> tracerEnds) {}

    /** Where one pellet (or the one bullet) ended or went through a body: a surface ({@code normal}, maybe a block) or an actor ({@code target}). */
    record Impact(Vec3 at, Vec3 normal, net.minecraft.core.BlockPos block, AABB target, int actor) {}
    private static final java.util.concurrent.ConcurrentHashMap<Long, Shot> shots = new java.util.concurrent.ConcurrentHashMap<>();
    private static String heldType = "Base.Pistol";
    private PzGuns() {}

    static void init() {
        // Two hands are on the pistol: nothing may be held in the off-hand while it is in the main hand. Whatever is there (the swap
        // key, a pickup, a test command) goes back into the inventory, or drops at Steve's feet when that is full.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ItemStack off = player.getOffhandItem();
                if (off.isEmpty() || !held(player.getMainHandItem()) || player.isDeadOrDying()) continue;
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                //#if MC_26_1
                //$ player.getInventory().placeItemBackInInventory(off);
                //#else
                player.getInventory().placeItemBackInInventory(off, net.minecraft.util.Prediction.SERVER_ONLY);
                //#endif
                offhandCleared++;
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            var cmd = Commands.literal("pzgun");
            String[] actions = {"fire", "reload", "cancel"};
            for (int i = 0; i < actions.length; i++) {
                final int action = i;
                cmd.then(Commands.literal(actions[i]).executes(ctx -> send(ctx.getSource().getPlayerOrException(), action) ? 1 : 0));
            }
            dispatcher.register(cmd);
        });
    }

    /** Any PZ firearm ({@link PzItems#firearm}). */
    public static boolean held(ItemStack stack) { return PzItems.firearm(stack); }

    /** A gun drawn with a rigged model (sights to bring to the screen centre, clips, arm bones); the others are PZ's bare meshes. */
    static boolean hasSights(ItemStack stack) { return GunModels.kind(stack) != null; }

    /**
     * A gun's view kick relative to the M9's: the rigged model's own figure over the M9's 3.0 (a pump gun kicks about three times
     * as hard, the M16 half), or for a gun without one PZ's recoil delay over the M9's 12.
     */
    static double recoilScale(ItemStack stack) {
        var kind = GunModels.kind(stack);
        if (kind != null && kind.number("viewRecoil", 0) > 0) return Math.max(.4, Math.min(3.5, kind.number("viewRecoil", 3.0) / 3.0));
        var e = stack == null ? null : PzItemCatalog.get(PzItems.typeOf(stack));
        if (e == null || e.weapon() == null || !e.weapon().has("recoilDelay")) return 1.0;
        return Math.max(.4, Math.min(3.0, e.weapon().get("recoilDelay").getAsDouble() / 12.0));
    }

    private static volatile java.util.Map<String, String> magazineAmmo = java.util.Map.of();
    private static int magazineAmmoFor = -1;

    /** The ammunition key a magazine type holds ({@code Base.9mmClip} gives {@code Base.Bullets9mm}), or null if it is not a magazine. */
    static String ammoOfMagazine(String type) {
        if (type == null) return null;
        int size = PzItemCatalog.size();
        if (size != magazineAmmoFor) {
            var map = new java.util.HashMap<String, String>();
            for (String id : PzItemCatalog.ids()) {
                var w = PzItemCatalog.get(id).weapon();
                if (w != null && w.has("firearm") && w.has("magazine") && !w.get("magazine").isJsonNull() && w.has("ammoKey") && !w.get("ammoKey").isJsonNull())
                    map.put(w.get("magazine").getAsString(), w.get("ammoKey").getAsString());
            }
            magazineAmmo = java.util.Map.copyOf(map);
            magazineAmmoFor = size;
        }
        return magazineAmmo.get(type);
    }

    static boolean magazine(ItemStack stack) { return ammoOfMagazine(PzItems.typeOf(stack)) != null; }

    /**
     * The use key (right click) with the pistol in hand is aiming down the sights: nothing else is used or placed. A magazine is
     * no longer loaded by right click (R does that: see {@link #reloadKey}).
     */
    public static boolean use(Minecraft mc) {
        if (!Session.pzConnected || !Session.released || mc.player == null || mc.player.isDeadOrDying()) return false;
        return held(mc.player.getMainHandItem());
    }

    /** Returning true also prevents the vanilla melee/mining path, including an empty or jammed gun. */
    public static boolean attack(Minecraft mc) { return request(mc, GunAction.FIRE); }

    /**
     * R: with the pistol in hand it reloads (PZ needs a loaded magazine in the inventory) and shift+R takes the magazine out of the
     * gun (it also drops out by itself when the gun is empty); with a magazine in hand and 9mm rounds
     * in the off-hand it puts ONE round into the magazine, and sneaking (shift+R) takes the rounds out again. Returns true when
     * the key belonged to a gun or a magazine.
     */
    static boolean reloadKey(Minecraft mc) {
        if (mc.player == null) return false;
        ItemStack main = mc.player.getMainHandItem();
        if (held(main)) return request(mc, mc.player.isShiftKeyDown() ? GunAction.EJECT_MAGAZINE : GunAction.RELOAD);
        if (!magazine(main)) return false;
        if (mc.player.isShiftKeyDown()) return request(mc, GunAction.UNLOAD_MAGAZINE);
        String ammo = ammoOfMagazine(PzItems.typeOf(main));
        if (ammo != null && ammo.equals(PzItems.typeOf(mc.player.getOffhandItem()))) return request(mc, GunAction.LOAD_ONE_ROUND);
        //#if MC_26_1
        //$ mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("Magazine: hold its rounds in your off-hand to load one with R (shift+R unloads)"), false);
        //#else
        mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("Magazine: hold its rounds in your off-hand to load one with R (shift+R unloads)"), false);
        //#endif
        return true;
    }

    /**
     * I: look the weapon over. Requires a free off-hand and no sprint, so the run pose does not interrupt it. Returns true when the key belonged to the gun.
     */
    static boolean inspectKey(Minecraft mc) {
        if (mc.player == null || !held(mc.player.getMainHandItem())) return false;
        if (!mc.player.getOffhandItem().isEmpty() || mc.player.isSprinting() || GunFeel.sprinting()) return true;
        return request(mc, GunAction.INSPECT);
    }

    /** B: single / auto on a select-fire gun (PZ answers "no fire modes" on the others). */
    static boolean fireModeKey(Minecraft mc) {
        if (mc.player == null || !held(mc.player.getMainHandItem())) return false;
        return request(mc, GunAction.FIRE_MODE);
    }

    /** X: work the action by hand. PZ runs the native rack (the chamber from the magazine); the clip plays when PZ answers "racking". */
    static boolean rackKey(Minecraft mc) {
        if (mc.player == null || !held(mc.player.getMainHandItem())) return false;
        return request(mc, GunAction.RACK);
    }

    /** The fire mode of the held gun as PZ keeps it ("Single", "Auto"), or null for a gun with only one. */
    static String fireMode(ItemStack stack) {
        var g = PzItems.stateOf(stack);
        if (g == null || !g.has("g")) return null;
        var gun = g.getAsJsonObject("g");
        return gun.has("mode") ? gun.get("mode").getAsString() : null;
    }

    private static long lastAuto;

    /** The attack key held down (every client tick): a gun in full auto keeps firing at its own cadence. */
    public static void holdAttack(Minecraft mc) {
        if (mc.player == null || !Session.pzConnected || !Session.released) return;
        ItemStack main = mc.player.getMainHandItem();
        if (!held(main) || !"Auto".equals(fireMode(main))) return;
        long now = System.nanoTime();
        if (now - lastAuto < 80_000_000L) return;
        lastAuto = now;
        request(mc, GunAction.FIRE);
    }

    /** Aiming or sprinting ends an inspection: tell PZ so it stops the remaining inspect sounds. */
    static void cancelInspect() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (heldId < 0 || server == null) return;
        Session.send(Wire.MSG_GUN_ACTION, new GunAction(sequence.incrementAndGet(), GunAction.CANCEL, heldId, -1,
                GunAction.MISS, 0, 0, 0, heldType).encode());
    }

    static boolean request(Minecraft mc, int action) {
        boolean magazineAction = action == GunAction.LOAD_MAGAZINE || action == GunAction.UNLOAD_MAGAZINE || action == GunAction.LOAD_ONE_ROUND;
        if (!Session.pzConnected || !Session.released || mc.player == null
                || !(magazineAction ? magazine(mc.player.getMainHandItem()) : held(mc.player.getMainHandItem()))) return false;
        var server = mc.getSingleplayerServer();
        if (server != null && !mc.player.isDeadOrDying()) {
            var id = mc.player.getUUID();
            // The ray is the camera's, read now on the client: the server's copy of Steve's look is only as new as the last movement
            // packet (up to a tick behind), which put a shot off wherever the view had just been moved to.
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 eye = mc.player.getEyePosition(partial), look = mc.player.getViewVector(partial);
            server.execute(() -> { var p = server.getPlayerList().getPlayer(id); if (p != null) send(p, action, eye, look); });
        }
        return true;
    }

    private static boolean send(ServerPlayer player, int action) { return send(player, action, null, null); }

    private static boolean send(ServerPlayer player, int action, Vec3 clientEye, Vec3 clientLook) {
        ItemStack stack = player.getMainHandItem();
        boolean magazineAction = action == GunAction.LOAD_MAGAZINE || action == GunAction.UNLOAD_MAGAZINE || action == GunAction.LOAD_ONE_ROUND;
        if (!Session.pzConnected || !(magazineAction ? magazine(stack) : held(stack)) || player.isDeadOrDying()) return false;
        var state = PzItems.stateOf(stack);
        if (!state.has("i")) return false; // wait for a whole native item, never manufacture ammo client-side
        if (magazineAction || action == GunAction.EJECT_MAGAZINE || action == GunAction.INSPECT || action == GunAction.FIRE_MODE || action == GunAction.RELOAD || action == GunAction.RACK) {
            // a reload or an inspection of a rigged gun tells PZ when in its clip the sounds belong (and how long its reload is)
            var kind = GunModels.kind(stack);
            java.util.List<Integer> timing = java.util.List.of();
            if (kind != null && kind.raw() != null && action == GunAction.RELOAD && kind.recipe().equals("magazine"))
                // a gun with no magazine in it is reloaded with clips of its own (nothing comes out first: shorter, no eject sound)
                timing = GunItem.withoutMagazine() && kind.has("reloadnomag")
                        ? GunAction.timing(kind.timing("reloadnomag", "reloadNoMagPhase"), kind.timing("reloademptynomag", "reloadEmptyNoMagPhase"))
                        : GunAction.timing(kind.timing("reload", "reloadPhase"), kind.timing("reloadempty", "reloadEmptyPhase"));
            else if (kind != null && kind.rounds() && (action == GunAction.RELOAD || action == GunAction.EJECT_MAGAZINE))
                // loaded round by round: how long opening the action takes, and one round's trip of the hand
                timing = GunAction.timing(new GunAction.Timing(kind.length("loadstart"), new double[0][]), new GunAction.Timing(kind.length("load"), new double[0][]));
            else if (kind != null && kind.raw() != null && action == GunAction.EJECT_MAGAZINE && kind.has("unload"))
                // taking the magazine out of a rigged gun: how long its clip is and when in it the magazine leaves
                timing = GunAction.timing(kind.timing("unload", "unloadPhase"), null);
            else if (kind != null && kind.raw() != null && action == GunAction.INSPECT)
                timing = GunAction.timing(kind.timing("inspect", null), kind.timing("inspectempty", null));
            Session.send(Wire.MSG_GUN_ACTION, new GunAction(sequence.incrementAndGet(), action, state.get("i").getAsInt(), -1,
                    GunAction.MISS, 0, 0, 0, PzItems.typeOf(stack), timing).encode());
            return true;
        }
        Vec3 from = player.getEyePosition(), dir = player.getViewVector(1);
        if (clientEye != null && clientLook != null && clientLook.lengthSqr() > 1e-6 && clientEye.distanceToSqr(from) < 16) {
            from = clientEye; dir = clientLook.normalize();
        }
        var weapon = PzItemCatalog.get(PzItems.typeOf(stack)).weapon();
        double range = Math.min(48, weapon.get("maxRange").getAsDouble() * 1.5);
        boolean fire = action == GunAction.FIRE;
        // A shotgun shot is several pellets (PZ's own projectile count and spread; the first goes where the crosshair is);
        // a piercing bullet goes on through the bodies it hits, up to the weapon's own maximum hit count.
        int pellets = fire && weapon.has("projectiles") ? Math.max(1, Math.min(9, weapon.get("projectiles").getAsInt())) : 1;
        // PZ's ProjectileSpread (0.6 double barrel .. 2.0 sawn-off) read as degrees of half-angle times a feel factor: a pump gun's
        // pellets cover about a body width at 4 blocks, a sawn-off's about two
        double spread = Math.toRadians((weapon.has("spread") ? weapon.get("spread").getAsDouble() : 0) * Tuning.get("pellet.spreadScale", 6.0));
        boolean piercing = weapon.has("piercing") && weapon.get("piercing").getAsBoolean();
        int maxHits = Math.max(1, weapon.has("maxHitCount") ? weapon.get("maxHitCount").getAsInt() : 1);
        Ray main = null;
        var impacts = new ArrayList<Impact>();
        var tracerEnds = new ArrayList<Vec3>();
        var actors = new java.util.LinkedHashMap<Integer, Double>();      // distinct actor id -> nearest distance
        for (int i = 0; i < pellets; i++) {
            Ray ray = cast(player, from, i == 0 ? dir : spreadDirection(dir, spread), range, fire, piercing, maxHits);
            if (i == 0) main = ray;
            for (ActorHit hit : ray.hits) {
                actors.merge(hit.id, hit.distance, Math::min);
                impacts.add(new Impact(hit.point, null, null, hit.box, hit.id));
            }
            if (ray.kind != GunAction.MISS) impacts.add(new Impact(ray.end, ray.normal, ray.block, null, -1));
            if (tracerEnds.size() < 4) tracerEnds.add(ray.end);
        }
        var ordered = new ArrayList<>(actors.entrySet());
        ordered.sort(java.util.Map.Entry.comparingByValue());
        int actor = ordered.isEmpty() ? -1 : ordered.get(0).getKey();
        var targets = new ArrayList<Integer>();
        for (int i = 1; i < ordered.size() && targets.size() < Math.min(maxHits - 1, GunAction.MAX_TARGETS); i++) targets.add(ordered.get(i).getKey());
        long seq = sequence.incrementAndGet();
        if (fire) {
            Vec3 right = dir.cross(new Vec3(0, 1, 0)).normalize();
            if (shots.size() > 64) shots.clear();
            AABB targetBox = main.hits.isEmpty() ? null : main.hits.get(0).box;
            shots.put(seq, new Shot(from.add(dir.scale(0.7)).add(right.scale(0.13)).add(0, -0.16, 0), main.end, main.hits.isEmpty() ? main.normal : null,
                    main.hits.isEmpty() ? main.block : null, targetBox, from, dir, impacts, tracerEnds));
        }
        int kind = main.hits.isEmpty() || piercing ? main.kind : GunAction.MISS;
        var request = new GunAction(seq, action, state.get("i").getAsInt(), actor, kind, main.end.x, main.end.y, main.end.z,
                PzItems.typeOf(stack), targets);
        Session.send(Wire.MSG_GUN_ACTION, request.encode());
        return true;
    }

    private record ActorHit(int id, AABB box, Vec3 point, double distance) {}

    /** Where one ray ended (a Minecraft block, a PZ surface, or nothing within range) and the actors in its way, nearest first. */
    private record Ray(Vec3 end, int kind, Vec3 normal, net.minecraft.core.BlockPos block, java.util.List<ActorHit> hits) {}

    private static Ray cast(ServerPlayer player, Vec3 from, Vec3 dir, double range, boolean withActors, boolean piercing, int maxHits) {
        Vec3 point = from.add(dir.scale(range));
        int kind = GunAction.MISS;
        Vec3 normal = null; net.minecraft.core.BlockPos blockPos = null;
        var block = player.level().clip(new ClipContext(from, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) {
            point = block.getLocation(); range = from.distanceTo(point); kind = GunAction.MC_BLOCK;
            normal = Vec3.atLowerCornerOf(block.getDirection().getUnitVec3i()); blockPos = block.getBlockPos();
        }
        var nativeHit = CollisionField.raycast(from, dir, range);
        if (nativeHit != null && nativeHit.distance() < range) {
            point = nativeHit.point(); range = nativeHit.distance(); kind = GunAction.PZ_SURFACE;
            normal = Vec3.atLowerCornerOf(nativeHit.face().getUnitVec3i()); blockPos = null;
        }
        var hits = new ArrayList<ActorHit>();
        if (withActors) {
            for (var target : player.level().getEntities(player, new AABB(from, point).inflate(1), ActorProxies::isProxy)) {
                var hit = target.getBoundingBox().inflate(.025).clip(from, point);
                if (hit.isPresent()) hits.add(new ActorHit(ActorProxies.pzId(target), target.getBoundingBox(), hit.get(), from.distanceTo(hit.get())));
            }
            hits.sort(java.util.Comparator.comparingDouble(ActorHit::distance));
            // a bullet stops in the first body it hits unless it pierces
            int keep = piercing ? maxHits : 1;
            while (hits.size() > keep) hits.remove(hits.size() - 1);
            if (!hits.isEmpty() && !piercing) { point = hits.get(0).point; kind = GunAction.MISS; normal = null; blockPos = null; }
        }
        return new Ray(point, kind, normal, blockPos, hits);
    }

    /** A direction inside a cone of half-angle {@code spread} (radians) around {@code dir}, uniform over the disc. */
    private static Vec3 spreadDirection(Vec3 dir, double spread) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        Vec3 right = dir.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-6) right = new Vec3(1, 0, 0);
        right = right.normalize();
        Vec3 up = right.cross(dir).normalize();
        double angle = random.nextDouble() * Math.PI * 2, radius = Math.sqrt(random.nextDouble()) * spread;
        return dir.add(right.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius)).normalize();
    }

    static void receive(byte[] data) {
        if (results.size() < 128) results.add(JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
    }

    static void tick(Minecraft mc) {
        if (!Session.pzConnected || mc.player == null) { results.clear(); heldId = -1; GunMeshAnim.stop(); return; }
        ItemStack stack = mc.player.getMainHandItem();
        var state = PzItems.stateOf(stack);
        boolean gunOrMagazine = held(stack) || magazine(stack);
        int id = gunOrMagazine && state.has("i") ? state.get("i").getAsInt() : -1;
        if (id != heldId) GunMeshAnim.stop();
        if (held(stack) && !hasSights(stack)) GunMeshAnim.weapon(PzItems.typeOf(stack));
        if (heldId >= 0 && id != heldId) {
            Session.send(Wire.MSG_GUN_ACTION, new GunAction(sequence.incrementAndGet(), GunAction.CANCEL, heldId, -1,
                    GunAction.MISS, 0, 0, 0, heldType).encode());
        }
        // a firearm that comes into the hand is drawn (the M9 plays its own draw clip)
        if (id >= 0 && id != heldId && held(stack) && !hasSights(stack)) GunMeshAnim.draw();
        heldId = id;
        if (id >= 0) heldType = PzItems.typeOf(stack);
        boolean meshGun = held(stack) && !hasSights(stack);     // drawn from PZ's own mesh: procedural animation instead of clips
        JsonObject r;
        while ((r = results.poll()) != null) {
            last = r;
            PzCraftMain.LOG.info("Native gun result: {}", r);
            boolean mine = r.get("itemId").getAsInt() == id;
            if (mine && meshGun && r.has("clip")) {
                String clip = r.get("clip").getAsString();
                // a reload that starts with no round in the chamber is the one that ends with the slide being released/racked
                if (clip.equals("reload") && r.get("status").getAsString().equals("reload started") && r.has("chamber") && !r.get("chamber").getAsBoolean()) clip = "reloadempty";
                GunMeshAnim.clip(clip, r);
            }
            if (mine && !meshGun) GunItem.result(mc, r);      // the rigged model plays the clip that fits PZ's answer
            String status = r.get("status").getAsString();
            if (status.equals("fired")) {
                Shot shot = shots.remove(r.get("sequence").getAsLong());
                GunFeel.fired(shot, r);
                if (meshGun && mine) GunMeshAnim.fired(r);
            }
            if (mine && meshGun && (status.startsWith("loaded") || status.startsWith("unloaded"))) GunMeshAnim.loaded();
            else if (mine && meshGun && (status.startsWith("stopped") || status.startsWith("cancelled") || status.equals("no rounds")))
                GunMeshAnim.stop();
            if (!status.equals("fired") && !status.equals("missing") && !status.equals("inspecting") && !status.equals("racking") && !status.equals("cooldown") && mine) {
                String rounds = r.has("ammo") ? "  " + r.get("ammo").getAsInt() + (r.has("chamber") && r.get("chamber").getAsBoolean() ? "+1" : "")
                        + " / " + r.get("capacity").getAsInt() : "";
                //#if MC_26_1
                //$ mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(
                //#else
                mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(
                //#endif
                        (magazine(stack) ? "Magazine: " : stack.getHoverName().getString() + ": ") + status + rounds), false);
            }
        }
    }

    static JsonObject state() { return last; }
}
