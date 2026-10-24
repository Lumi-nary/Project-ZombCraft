package pzcraft.mc;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.WorldTargets;

/**
 * Steve mining PZ things (W2), client side. The crosshair finds the nearest {@link PzTargets} target and reports it as a
 * block hit; holding attack on it runs vanilla's mining sum against the target's stand-in block, with its hit sounds and
 * particles and a small bar under the crosshair (Minecraft has no block there to crack). When done the server is asked
 * ({@link PzMineServer.Mine}), the target is hidden and the break sound and particles play.
 */
public final class PzMining {
    /** The target under the crosshair this frame, or null. Client thread. */
    private static WorldTargets.Target aimed;
    /** A car side under the crosshair (W5), or null. */
    private static PzCars.Aim aimedCar;
    private static BlockPos aimedPos = BlockPos.ZERO;
    private static Vec3 aimedPoint = Vec3.ZERO;
    private static Direction aimedFace = Direction.UP;
    private static WorldTargets.Target mining;
    private static float progress;
    private static int ticks, delay;

    private PzMining() {}

    static void init() {
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((graphics,partialTick)->drawProgress(graphics));
    }

    /**
     * LocalPlayer.raycastHitResult's result, with PZ targets: a target nearer than the block or miss wins; an entity wins
     * over grass, bushes and stones (attack through them), and over a boulder only when it is nearer.
     */
    public static HitResult pick(LocalPlayer player, Entity camera, float partialTicks, HitResult result) {
        aimed = null;
        aimedCar = null;
        if (camera != player || player.isSpectator() || !Session.released) return result;
        Vec3 from = camera.getEyePosition(partialTicks), dir = camera.getViewVector(partialTicks);
        PzTargets.Hit hit = PzTargets.raycast(from, dir, (player.isCreative()?5.0:4.5));
        PzCars.Aim car = player.isPassenger() ? null : PzCars.aim(from, dir, (player.isCreative()?5.0:4.5));
        if (car != null && (hit == null || car.distance() <= hit.distance())) {
            if (result instanceof EntityHitResult e && from.distanceTo(e.getLocation()) <= car.distance()) return result;
            if (result.getType() == HitResult.Type.BLOCK && car.distance() > from.distanceTo(result.getLocation()) + 0.05) return result;
            aimedCar = car;
            Vec3 p = car.point();
            aimedPos = BlockPos.containing(p.add(car.face().getStepX() * .01, car.face().getStepY() * .01, car.face().getStepZ() * .01));
            return new BlockHitResult(p, car.face(), aimedPos, false);
        }
        if (hit == null) return result;
        if (result instanceof EntityHitResult e) {
            if (PzMineables.attackThrough(hit.target().kind()) || from.distanceTo(e.getLocation()) <= hit.distance()) return result;
        } else if (result.getType() == HitResult.Type.BLOCK && hit.distance() > from.distanceTo(result.getLocation()) + 0.05) {
            return result; // something nearer; a boulder ties with its own collision box and wins
        }
        aimed = hit.target();
        aimedPoint = hit.point();
        aimedFace = hit.face();
        BlockPos pos = PzMineables.replaceable(aimed.kind()) ? PzTargets.voxel(aimed)
                : BlockPos.containing(hit.point().add(hit.face().getStepX() * .01, hit.face().getStepY() * .01, hit.face().getStepZ() * .01));
        aimedPos = pos;
        return new BlockHitResult(hit.point(), hit.face(), pos, false);
    }

    private static int lastRestPress = Integer.MIN_VALUE, lastCarPress = Integer.MIN_VALUE, lastTilePress = Integer.MIN_VALUE;

    /**
     * Right click on a floor holding a PZ wall or fence from the creative tabs: it goes on the edge of the clicked tile
     * nearest to where the floor was clicked (a south or east edge is the next tile's north or west one); the server asks
     * PZ, which puts PZ's sprite down as its Brush Tool does.
     */
    public static boolean placeTile(Minecraft mc) {
        if (mc.player == null || !Session.pzConnected || !Session.released) return false;
        String sprite = PzTiles.spriteOf(mc.player.getMainHandItem());
        if (sprite == null) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) return true;
        var input = Session.readInput();
        if (input == null || input.usePresses == lastTilePress) return true; // once per click
        lastTilePress = input.usePresses;
        Vec3 at = hit.getLocation();
        int tx = (int) Math.floor(at.x), tz = (int) Math.floor(at.z), level = (int) Math.floor(pzcraft.protocol.Coords.pzZ(at.y + 0.05));
        int[] edge = WorldTargets.nearestEdge(at.x - tx, at.z - tz);
        ClientPlayNetworking.send(new PzTiles.Place(sprite, tx + edge[0], tz + edge[1], level, edge[2] == 1));
        swing(mc);
        return true;
    }

    /** Left click on a car side with a window that is still whole: PZ takes Steve's hit off the window's condition (W5). */
    private static Boolean hitCar(Minecraft mc) {
        var side = aimedCar.side();
        if (mc.player == null || mc.missTime > 0 || mc.player.isHandsBusy() || side.windowPart() < 0
                || !side.has(pzcraft.protocol.Cars.Side.WINDOW_INTACT) || PzItems.firearm(mc.player.getMainHandItem())) return null;
        double damage = mc.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        int amount = pzcraft.protocol.Cars.windowDamage(damage, mc.player.getAttackStrengthScale(0.5f));
        Session.send(pzcraft.protocol.Cars.MSG_HIT, new pzcraft.protocol.Cars.Hit(side.vehicle(), side.windowPart(), amount).encode());
        swing(mc);
        mc.player.resetAttackStrengthTicker();
        return false;
    }

    /**
     * Right click on a car side: get into its seat (W5); PZ's rules decide (a locked door needs the key or a smashed window).
     * Shift+right-click anywhere on a car: PZ's own menu for it (open the hood or trunk, lock, mechanics, siphon...).
     */
    public static boolean useCar(Minecraft mc) {
        PzCars.Aim car = aimedCar;
        if (mc.player == null || !Session.pzConnected) return false;
        if (mc.player.isShiftKeyDown()) {
            int vehicle = car != null ? car.side().vehicle()
                    : mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK && mc.level != null
                            && mc.level.getBlockState(((BlockHitResult) mc.hitResult).getBlockPos()).isAir()   // PZ geometry, not a block
                    ? PzCars.vehicleNear(mc.hitResult.getLocation()) : -1;
            if (vehicle < 0) return false;
            var input = Session.readInput();
            if (input == null || input.usePresses == lastCarPress) return true;
            lastCarPress = input.usePresses;
            Session.send(pzcraft.protocol.Cars.MSG_MENU, java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(vehicle).array());
            PzCraftMain.LOG.info("car menu asked for vehicle {}", vehicle);
            return true;
        }
        if (car == null) return false;
        if (car.side().seat() < 0) return false;
        var input = Session.readInput();
        if (input == null || input.usePresses == lastCarPress) return true; // once per click
        lastCarPress = input.usePresses;
        if (car.side().has(pzcraft.protocol.Cars.Side.OCCUPIED)) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("That seat is taken"), true);
            return true;
        }
        Session.send(pzcraft.protocol.Cars.MSG_ENTER, new pzcraft.protocol.Cars.Enter(car.side().vehicle(), car.side().seat()).encode());
        return true;
    }

    /**
     * Minecraft.startUseItem (W4): right-clicking a PZ seat or bed asks the server to sit or sleep there ({@link PzRest});
     * true when it took the click. Sneaking keeps the click for placing blocks.
     */
    public static boolean rest(Minecraft mc) {
        WorldTargets.Target t = aimed;
        if (!PzRest.restsOn(t) || mc.player == null || mc.player.isShiftKeyDown() || !Session.pzConnected || !Session.released) return false;
        var input = Session.readInput();
        if (input == null || input.usePresses == lastRestPress) return true; // once per click
        lastRestPress = input.usePresses;
        ClientPlayNetworking.send(new PzRest.Use(t.kind(), t.x(), t.y(), t.level(), t.key()));
        mc.player.swing(InteractionHand.MAIN_HAND, false);
        return true;
    }

    /** What Better Combat's "is this mineable" check sees at {@code pos}: the aimed PZ thing's stand-in block. */
    public static net.minecraft.world.level.block.state.BlockState standInAt(BlockPos pos, net.minecraft.world.level.block.state.BlockState real) {
        PzCars.Aim car = aimedCar;
        if (car != null && real.isAir() && pos.equals(aimedPos) && car.side().has(pzcraft.protocol.Cars.Side.WINDOW_INTACT))
            return net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(); // a car window is hit, not swung through
        WorldTargets.Target t = aimed;
        if (t == null || !real.isAir() || !pos.equals(aimedPos)) return real;
        return PzMineables.def(t.kind(), t.variant()).standIn();
    }

    private static boolean canMine(Minecraft mc) {
        return aimed != null && mc.player != null && mc.level != null && mc.gameMode != null && !mc.player.isSpectator()
                && mc.missTime <= 0 && !mc.player.isHandsBusy() && !mc.player.isUsingItem()
                && !PzItems.firearm(mc.player.getMainHandItem()) && Session.pzConnected;
    }

    /**
     * Minecraft.startAttack: null lets vanilla run, otherwise its return value (true = the attack ended this tick). As
     * vanilla's startDestroyBlock, it only breaks a thing that breaks at once; continueAttack adds the progress.
     */
    public static Boolean startAttack(Minecraft mc) {
        if (aimedCar != null) return hitCar(mc);
        if (!canMine(mc)) return null;
        mining = aimed;
        progress = 0;
        ticks = 0;
        delay = 0;
        swing(mc);
        PzMineables.Def def = PzMineables.def(mining.kind(), mining.variant());
        if (def.progressPerTick(mc.player) < 1) return false;
        finish(mc, def, def.standIn().getSoundType());
        return true;
    }

    /** Minecraft.continueAttack: true when it handled the tick (vanilla's must not run). */
    public static boolean continueAttack(Minecraft mc, boolean down) {
        if (down && aimedCar != null) return true; // one hit per click on a car window, as on an entity
        if (!down || !canMine(mc)) {
            mining = null;
            progress = 0;
            return false;
        }
        if (delay > 0) { delay--; return true; }
        if (!aimed.same(mining)) { mining = aimed; progress = 0; ticks = 0; }
        swing(mc);
        tick(mc);
        return true;
    }

    /** One tick of mining the current target; true when it broke. */
    private static boolean tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        PzMineables.Def def = PzMineables.def(mining.kind(), mining.variant());
        SoundType sound = def.standIn().getSoundType();
        progress += def.progressPerTick(player);
        if (ticks % 4 == 0 && progress < 1) {
            mc.level.playLocalSound(aimedPoint.x, aimedPoint.y, aimedPoint.z, sound.getHitSound(), SoundSource.BLOCKS,
                    (sound.getVolume() + 1) / 8, sound.getPitch() * 0.5f, false);
        }
        ticks++;
        Vec3 p = aimedPoint.add(aimedFace.getStepX() * .1, aimedFace.getStepY() * .1, aimedFace.getStepZ() * .1);
        mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, def.standIn()), p.x, p.y, p.z, 0, 0, 0);
        if (progress < 1) return false;
        finish(mc, def, sound);
        return true;
    }

    private static void finish(Minecraft mc, PzMineables.Def def, SoundType sound) {
        WorldTargets.Target t = mining;
        PzTargets.hide(t);
        ClientPlayNetworking.send(new PzMineServer.Mine(t.kind(), t.variant(), t.x(), t.y(), t.level(), t.key()));
        Vec3 c = PzTargets.center(t);
        mc.level.playLocalSound(c.x, c.y, c.z, sound.getBreakSound(), SoundSource.BLOCKS, (sound.getVolume() + 1) / 2, sound.getPitch() * 0.8f, false);
        mc.level.addDestroyBlockEffect(PzTargets.voxel(t), def.standIn());
        mining = null;
        progress = 0;
        delay = 5; // vanilla's pause between blocks broken by holding attack
    }

    private static void swing(Minecraft mc) {
        mc.player.swing(InteractionHand.MAIN_HAND, false);
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));   // 26.1.2's swing packet
    }

    /** A thin bar under the crosshair while a PZ thing is being mined. */
    private static void drawProgress(GuiGraphics g) {
        var mc = Minecraft.getInstance();
        if (mining == null || progress <= 0 || progress >= 1 || mc.screen != null) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2 + 9, half = 10;
        g.fill(cx - half - 1, cy - 1, cx + half + 1, cy + 2, 0x90000000);
        g.fill(cx - half, cy, cx - half + Math.round(2 * half * progress), cy + 1, 0xFFFFFFFF);
    }
}
