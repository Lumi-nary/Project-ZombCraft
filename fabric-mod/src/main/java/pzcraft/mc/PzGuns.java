package pzcraft.mc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    /** Where each fired request started and ended (muzzle, impact), by sequence, for the tracer when PZ confirms the shot. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Vec3[]> shots = new java.util.concurrent.ConcurrentHashMap<>();
    private static String heldType = "Base.Pistol";
    static final String MAGAZINE = "Base.9mmClip", AMMO = "Base.Bullets9mm";
    private PzGuns() {}

    static void init() {
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

    public static boolean held(ItemStack stack) { return "Base.Pistol".equals(PzItems.typeOf(stack)); }
    static boolean magazine(ItemStack stack) { return MAGAZINE.equals(PzItems.typeOf(stack)); }

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
     * R: with the pistol in hand it reloads (PZ needs a loaded magazine in the inventory); with a magazine in hand and 9mm rounds
     * in the off-hand it puts ONE round into the magazine, and sneaking (shift+R) takes the rounds out again. Returns true when
     * the key belonged to a gun or a magazine.
     */
    static boolean reloadKey(Minecraft mc) {
        if (mc.player == null) return false;
        ItemStack main = mc.player.getMainHandItem();
        if (held(main)) return request(mc, GunAction.RELOAD);
        if (!magazine(main)) return false;
        if (mc.player.isShiftKeyDown()) return request(mc, GunAction.UNLOAD_MAGAZINE);
        if (AMMO.equals(PzItems.typeOf(mc.player.getOffhandItem()))) return request(mc, GunAction.LOAD_ONE_ROUND);
        mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal("Magazine: hold 9mm rounds in your off-hand to load one with R (shift+R unloads)"), false);
        return true;
    }

    static boolean request(Minecraft mc, int action) {
        boolean magazineAction = action == GunAction.LOAD_MAGAZINE || action == GunAction.UNLOAD_MAGAZINE || action == GunAction.LOAD_ONE_ROUND;
        if (!Session.pzConnected || !Session.released || mc.player == null
                || !(magazineAction ? magazine(mc.player.getMainHandItem()) : held(mc.player.getMainHandItem()))) return false;
        var server = mc.getSingleplayerServer();
        if (server != null && !mc.player.isDeadOrDying()) {
            var id = mc.player.getUUID();
            server.execute(() -> { var p = server.getPlayerList().getPlayer(id); if (p != null) send(p, action); });
        }
        return true;
    }

    private static boolean send(ServerPlayer player, int action) {
        ItemStack stack = player.getMainHandItem();
        boolean magazineAction = action == GunAction.LOAD_MAGAZINE || action == GunAction.UNLOAD_MAGAZINE || action == GunAction.LOAD_ONE_ROUND;
        if (!Session.pzConnected || !(magazineAction ? magazine(stack) : held(stack)) || player.isDeadOrDying()) return false;
        var state = PzItems.stateOf(stack);
        if (!state.has("i")) return false; // wait for a whole native item, never manufacture ammo client-side
        if (magazineAction) {
            Session.send(Wire.MSG_GUN_ACTION, new GunAction(sequence.incrementAndGet(), action, state.get("i").getAsInt(), -1,
                    GunAction.MISS, 0, 0, 0, PzItems.typeOf(stack)).encode());
            return true;
        }
        Vec3 from = player.getEyePosition(), dir = player.getViewVector(1);
        var e = PzItemCatalog.get(PzItems.typeOf(stack));
        double range = Math.min(48, e.weapon().get("maxRange").getAsDouble() * 1.5);
        Vec3 point = from.add(dir.scale(range));
        int kind = GunAction.MISS, actor = -1;
        var block = player.level().clip(new ClipContext(from, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (block.getType() != HitResult.Type.MISS) { point = block.getLocation(); range = from.distanceTo(point); kind = GunAction.MC_BLOCK; }
        var nativeHit = CollisionField.raycast(from, dir, range);
        if (nativeHit != null && nativeHit.distance() < range) { point = nativeHit.point(); range = nativeHit.distance(); kind = GunAction.PZ_SURFACE; }
        if (action == GunAction.FIRE) {
            for (var target : player.level().getEntities(player, new AABB(from, point).inflate(1), ActorProxies::isProxy)) {
                var hit = target.getBoundingBox().inflate(.025).clip(from, point);
                if (hit.isPresent() && from.distanceTo(hit.get()) < range) {
                    point = hit.get(); range = from.distanceTo(point); actor = ActorProxies.pzId(target); kind = GunAction.MISS;
                }
            }
        }
        long seq = sequence.incrementAndGet();
        if (action == GunAction.FIRE) {
            Vec3 right = dir.cross(new Vec3(0, 1, 0)).normalize();
            if (shots.size() > 64) shots.clear();
            shots.put(seq, new Vec3[] {from.add(dir.scale(0.7)).add(right.scale(0.13)).add(0, -0.16, 0), point});
        }
        var request = new GunAction(seq, action, state.get("i").getAsInt(), actor,
                kind, point.x, point.y, point.z, PzItems.typeOf(stack));
        Session.send(Wire.MSG_GUN_ACTION, request.encode());
        return true;
    }

    static void receive(byte[] data) {
        if (results.size() < 128) results.add(JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
    }

    static void tick(Minecraft mc) {
        if (!Session.pzConnected || mc.player == null) { results.clear(); heldId = -1; return; }
        ItemStack stack = mc.player.getMainHandItem();
        var state = PzItems.stateOf(stack);
        boolean gunOrMagazine = held(stack) || magazine(stack);
        int id = gunOrMagazine && state.has("i") ? state.get("i").getAsInt() : -1;
        if (heldId >= 0 && id != heldId) {
            Session.send(Wire.MSG_GUN_ACTION, new GunAction(sequence.incrementAndGet(), GunAction.CANCEL, heldId, -1,
                    GunAction.MISS, 0, 0, 0, heldType).encode());
        }
        heldId = id;
        if (id >= 0) heldType = PzItems.typeOf(stack);
        JsonObject r;
        while ((r = results.poll()) != null) {
            last = r;
            PzCraftClient.LOG.info("Native gun result: {}", r);
            if (r.get("itemId").getAsInt() == id && r.has("clip")) {
                String clip = r.get("clip").getAsString();
                // a reload that starts with no round in the chamber is the one that ends with the slide being released/racked
                if (clip.equals("reload") && r.get("status").getAsString().equals("reload started") && r.has("chamber") && !r.get("chamber").getAsBoolean()) clip = "reloadempty";
                PzItems.gunClip(mc, clip, r.has("pace") ? r.get("pace").getAsDouble() : 1.0);
            }
            String status = r.get("status").getAsString();
            if (status.equals("fired")) {
                Vec3[] shot = shots.remove(r.get("sequence").getAsLong());
                GunFeel.fired(shot == null ? null : shot[0], shot == null ? null : shot[1]);
            }
            if (!status.equals("fired") && !status.equals("missing") && r.get("itemId").getAsInt() == id) {
                String rounds = r.has("ammo") ? "  " + r.get("ammo").getAsInt() + (r.has("chamber") && r.get("chamber").getAsBoolean() ? "+1" : "")
                        + " / " + r.get("capacity").getAsInt() : "";
                mc.gui.hud.setOverlayMessage(net.minecraft.network.chat.Component.literal(
                        (magazine(stack) ? "Magazine: " : "M9: ") + status + rounds), false);
            }
        }
    }

    static JsonObject state() { return last; }
}

