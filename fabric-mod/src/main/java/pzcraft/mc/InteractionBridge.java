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
    /** A lamp standing on a tile (on a table or the floor), and a wall switch's plate: from a hand's height to above the head. */
    private static final double LAMP_HEIGHT = 1.6, SWITCH_LOW = .7, SWITCH_HIGH = 1.9, SWITCH_DEPTH = .12;
    private InteractionBridge() {}

    /** The plate a wall switch is fixed to (WorldObjects.ObjectFace.NORTH/WEST/SOUTH/EAST), or null for a lamp. */
    private static AABB switchBox(WorldObjects.ObjectFace f,double y) {
        int flags=f.flags();double x0=f.x(),z0=f.y(),lo=y+SWITCH_LOW,hi=y+SWITCH_HIGH,d=SWITCH_DEPTH;
        if((flags&WorldObjects.ObjectFace.NORTH)!=0)return new AABB(x0,lo,z0,x0+1,hi,z0+d);
        if((flags&WorldObjects.ObjectFace.SOUTH)!=0)return new AABB(x0,lo,z0+1-d,x0+1,hi,z0+1);
        if((flags&WorldObjects.ObjectFace.WEST)!=0)return new AABB(x0,lo,z0,x0+d,hi,z0+1);
        if((flags&WorldObjects.ObjectFace.EAST)!=0)return new AABB(x0+1-d,lo,z0,x0+1,hi,z0+1);
        return null;
    }
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
            if((f.flags()&WorldObjects.ObjectFace.SWITCH)!=0) {
                var box=switchBox(f,y);
                if(box==null&&placing)continue;   // a lamp's tile stays free for blocks while one is in hand
                if(box==null)box=new AABB(f.x()+.15,y,f.y()+.15,f.x()+.85,y+LAMP_HEIGHT,f.y()+.85);
                var hit=box.clip(from,to);
                if(hit.isPresent()&&from.distanceTo(hit.get())<=limit){limit=from.distanceTo(hit.get());chosen=f;}
                continue;
            }
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
        PzCraftMain.LOG.info("Right-click PZ object {} at {},{} level {}",chosen.id(),chosen.x(),chosen.y(),chosen.level());
        boolean container=(chosen.flags()&WorldObjects.ObjectFace.CONTAINER)!=0;
        Session.send(container?Wire.MSG_CONTAINER_OPEN:Wire.MSG_INTERACT,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chosen.id()).array());
        //#if MC_26_1
        //$ if(!container)mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, false);
        //#else
        if(!container)mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        //#endif
        return true;
    }
}
