package pzcraft.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldTargets;

/**
 * Placing PZ furniture (W3), client side: right-click a floor holding a PZ furniture item (one Steve mined, or found) and
 * PZ places it with its own rules, its front facing Steve; the inventory mirror then takes the item away. PZ says no
 * (no room, a wall in the way, water...) with a line over the hotbar.
 */
public final class PzFurniture {
    private static int lastUsePress = Integer.MIN_VALUE, seq;
    private static volatile int refusedSeq;

    private PzFurniture() {}

    /** The stack is a PZ furniture item: PZ told its script ("mv") when it reached Minecraft. */
    public static boolean isFurniture(ItemStack stack) {
        if (PzItems.typeOf(stack) == null) return false;
        return stack.hasTag() && stack.getTag().getCompound("pz").contains("mv");
    }

    /** Minecraft.startUseItem: true when it took the right-click. */
    public static boolean use(Minecraft mc) {
        if (!Session.pzConnected || !Session.released || mc.player == null || mc.player.isDeadOrDying()) return false;
        ItemStack stack = mc.player.getMainHandItem();
        if (!isFurniture(stack)) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP) return false;
        var input = Session.readInput();
        if (input == null || input.usePresses == lastUsePress) return true; // one placement per click, not every 4 ticks
        lastUsePress = input.usePresses;
        CompoundTag pz = stack.getTag().getCompound("pz");
        int id = pz.getInt("i");
        if (id == 0) {
            mc.player.displayClientMessage(Component.literal("PZ does not know this piece any more"), true);
            return true;
        }
        Vec3 at = hit.getLocation();
        int level = (int) Math.floor(Coords.pzZ(at.y + 0.05));
        int face = switch (mc.player.getDirection().getOpposite()) { // its front looks back at Steve
            case EAST -> WorldTargets.FACE_E;
            case SOUTH -> WorldTargets.FACE_S;
            case WEST -> WorldTargets.FACE_W;
            default -> WorldTargets.FACE_N;
        };
        var place = new WorldTargets.Place(++seq, id, (int) Math.floor(at.x), (int) Math.floor(at.z), level, face);
        Session.send(Wire.MSG_PLACE_FURNITURE, place.encode());
        PzCraftMain.LOG.info("place PZ furniture {} ({}) at {},{} level {} facing {}", PzItems.typeOf(stack), id, place.x(), place.y(),
                level, WorldTargets.faceName(face));
        mc.player.swing(InteractionHand.MAIN_HAND, false);
        return true;
    }

    /** Link thread: PZ's answer. */
    static void answer(byte[] payload) {
        var placed = WorldTargets.Placed.decode(payload);
        if (placed.placed() || placed.seq() == refusedSeq) return;
        refusedSeq = placed.seq();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> { if (mc.player != null) mc.player.displayClientMessage(Component.literal("It does not fit there"), true); });
    }
}
