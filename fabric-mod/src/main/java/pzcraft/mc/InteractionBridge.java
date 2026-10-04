package pzcraft.mc;

import java.nio.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.*;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldObjects;

public final class InteractionBridge {
    public static volatile List<WorldObjects.ObjectFace> faces=List.of();
    public static volatile long receivedAt;
    private static int lastUsePress = Integer.MIN_VALUE;
    /** How tall the box around a container is, in blocks: furniture is waist to head high, and a tile is one block wide. */
    private static final double CONTAINER_HEIGHT = 1.4, BODY_HEIGHT = 0.45, FLOOR_HEIGHT = 0.5;
    private InteractionBridge() {}
    public static boolean use(Minecraft mc) {
        if(!Session.pzConnected||!Session.released||mc.player==null||mc.player.isShiftKeyDown()
                ||mc.player.isDeadOrDying()||System.nanoTime()-receivedAt>1_000_000_000L)return false;
        Vec3 from=mc.player.getEyePosition(), to=from.add(mc.player.getViewVector(1).scale(mc.player.blockInteractionRange()));
        double limit=mc.player.blockInteractionRange();
        if(mc.hitResult!=null&&mc.hitResult.getType()!=HitResult.Type.MISS)limit=Math.min(limit,from.distanceTo(mc.hitResult.getLocation())+.15);
        WorldObjects.ObjectFace chosen=null;
        boolean placing=mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem;
        for(var f:faces) {
            boolean north=(f.flags()&WorldObjects.ObjectFace.NORTH)!=0;
            double y=Coords.mcY(f.level());
            // Items on the floor are only looted with something other than a block in hand, so blocks can still be placed on the ground.
            if((f.flags()&WorldObjects.ObjectFace.FLOOR)!=0&&placing)continue;
            double height=(f.flags()&WorldObjects.ObjectFace.FLOOR)!=0?FLOOR_HEIGHT:(f.flags()&WorldObjects.ObjectFace.BODY)!=0?BODY_HEIGHT:CONTAINER_HEIGHT;
            var box=(f.flags()&WorldObjects.ObjectFace.CONTAINER)!=0
                    ? new AABB(f.x(), y, f.y(), f.x()+1, y+height, f.y()+1)
                    : new AABB(f.x()-(north?0:.06), y, f.y()-(north?.06:0), f.x()+(north?1:.06), y+Coords.BLOCKS_PER_LEVEL, f.y()+(north?.06:1));
            var hit=box.clip(from,to);
            if(hit.isPresent()&&from.distanceTo(hit.get())<=limit) {limit=from.distanceTo(hit.get());chosen=f;}
        }
        if(chosen==null)return false;
        var input=Session.readInput();
        if(input==null||input.usePresses==lastUsePress)return true;
        lastUsePress=input.usePresses;
        PzCraftClient.LOG.info("Right-click PZ object {} at {},{} level {}",chosen.id(),chosen.x(),chosen.y(),chosen.level());
        boolean container=(chosen.flags()&WorldObjects.ObjectFace.CONTAINER)!=0;
        Session.send(container?Wire.MSG_CONTAINER_OPEN:Wire.MSG_INTERACT,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chosen.id()).array());
        if(!container)mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        return true;
    }
}

