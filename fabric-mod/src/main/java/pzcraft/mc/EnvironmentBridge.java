package pzcraft.mc;

import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldObjects;

/** State-based scan includes placement, removal, lava and redstone-powered lamps. */
final class EnvironmentBridge {
    private static final int MAX_LAMPS=64,LAMP_RANGE=32;
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
        // Minecraft's light sources as PZ lamps, so PZ's own walls, floors and zombies get their light (Minecraft geometry
        // carries Minecraft's light itself, VertexLight): the nearest MAX_LAMPS within LAMP_RANGE blocks, at or above PZ's
        // ground (PZ has no squares under it); sections without a light source are skipped at once.
        int px=player.blockPosition().getX(),py=player.blockPosition().getY(),pz=player.blockPosition().getZ();
        int cx=px>>4,cz=pz>>4;
        record Found(WorldObjects.Light light,int distance){}
        var found=new ArrayList<Found>();
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
            var chunk=level.getChunkSource().getChunkNow(cx+dx,cz+dz);if(chunk==null)continue;
            var sections=chunk.getSections();
            for(int si=0;si<sections.length;si++) {
                var section=sections[si];
                int baseY=chunk.getSectionYFromSectionIndex(si)<<4;
                if(baseY+15<0||baseY>py+LAMP_RANGE||baseY+15<py-LAMP_RANGE)continue;
                if(section.hasOnlyAir()||!section.maybeHas(s->s.getLightEmission()>0))continue;
                for(int x=0;x<16;x++)for(int y=0;y<16;y++)for(int z=0;z<16;z++) {
                    var state=section.getBlockState(x,y,z);int emission=state.getLightEmission();
                    if(emission<=0||baseY+y<0)continue;
                    int wx=((cx+dx)<<4)+x,wy=baseY+y,wz=((cz+dz)<<4)+z;
                    int d=(wx-px)*(wx-px)+(wy-py)*(wy-py)+(wz-pz)*(wz-pz);
                    if(d>LAMP_RANGE*LAMP_RANGE)continue;
                    String name=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    float r=.5f,g=.4f,b=.25f;
                    if(name.contains("soul")){r=.15f;g=.4f;b=.5f;}
                    else if(name.contains("redstone")){r=.5f;g=.1f;b=.04f;}
                    else if(name.contains("sea_lantern")||name.contains("end_rod")){r=.4f;g=.48f;b=.5f;}
                    else if(name.contains("lava")){r=.5f;g=.2f;b=.03f;}
                    found.add(new Found(new WorldObjects.Light(wx,wy,wz,emission,r,g,b),d));
                }
            }
        }
        found.sort(java.util.Comparator.comparingInt(Found::distance));
        for(int i=0;i<found.size()&&i<MAX_LAMPS;i++)lights.add(found.get(i).light());
        Session.send(Wire.MSG_LIGHTS,WorldObjects.lights(lights));
    }
}
