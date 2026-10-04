package pzcraft.mc;

import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldObjects;

/** State-based scan includes placement, removal, lava and redstone-powered lamps. */
final class EnvironmentBridge {
    private static int ticks;
    private static final java.util.Map<Integer,Boolean> power=new java.util.HashMap<>();
    static void tick(ServerPlayer player) {
        if(!Session.pzConnected||++ticks%10!=0)return;
        var level=player.level();var lights=new ArrayList<WorldObjects.Light>();
        var seen=new java.util.HashSet<Integer>();
        for(var face:InteractionBridge.faces){
            if((face.flags()&(WorldObjects.ObjectFace.WINDOW|WorldObjects.ObjectFace.CONTAINER))!=0)continue;
            seen.add(face.id());
            var pos=new BlockPos(face.x(),(int)Math.floor(pzcraft.protocol.Coords.mcY(face.level())+.01),face.y());
            boolean powered=level.hasNeighborSignal(pos)||level.hasNeighborSignal(pos.above());
            Boolean old=power.put(face.id(),powered);
            if(old!=null&&old!=powered||old==null&&powered)
                Session.send(Wire.MSG_INTERACT,java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(face.id()).putInt(powered?1:2).array());
        }
        power.keySet().retainAll(seen);
        int cx=player.blockPosition().getX()>>4,cz=player.blockPosition().getZ()>>4;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            var chunk=level.getChunkSource().getChunkNow(cx+dx,cz+dz);if(chunk==null)continue;
            var sections=chunk.getSections();
            for(int si=0;si<sections.length;si++) {
                var section=sections[si];if(section.hasOnlyAir())continue;
                int baseY=chunk.getSectionYFromSectionIndex(si)<<4;
                for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++) {
                    var state=section.getBlockState(x,y,z);int emission=state.getLightEmission();
                    if(emission<=0||lights.size()>=2048)continue;
                    String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    float r=.5f,g=.4f,b=.25f;
                    if(name.contains("soul")){r=.15f;g=.4f;b=.5f;}
                    else if(name.contains("redstone")){r=.5f;g=.1f;b=.04f;}
                    else if(name.contains("sea_lantern")||name.contains("end_rod")){r=.4f;g=.48f;b=.5f;}
                    else if(name.contains("lava")){r=.5f;g=.2f;b=.03f;}
                    lights.add(new WorldObjects.Light(((cx+dx)<<4)+x,baseY+y,((cz+dz)<<4)+z,emission,r,g,b));
                }
            }
        }
        Session.send(Wire.MSG_LIGHTS,WorldObjects.lights(lights));
    }
}

