package pzcraft.pz;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import pzcraft.protocol.ActorGround;
import pzcraft.protocol.Coords;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;

/** Keep native AI on its real square; persist and render the Minecraft physical height separately. */
public final class ActorTerrain {
    private static final String KEY="PzCraftTerrainY";
    private static volatile List<ActorGround.Pose> incoming=List.of();
    private static volatile long revision,receivedAt;
    private static long applied;
    private static final Map<IsoZombie,Float> renderHeights=new ConcurrentHashMap<>();
    private static final ThreadLocal<Integer> renderDepth=ThreadLocal.withInitial(()->0);
    private static volatile int corrected,rejected,rendered;
    private ActorTerrain() {}
    static void receive(List<ActorGround.Pose> poses){incoming=poses;receivedAt=System.nanoTime();revision++;}
    static boolean ground(IsoGameCharacter z){return z instanceof IsoZombie&&Math.abs(z.getZ())<.03&&!PassengerBridge.holds(z);}
    static double mcY(IsoGameCharacter z){
        Object y=z.getModData().rawget(KEY);
        return Math.abs(z.getZ())<.03&&y instanceof Number n&&Double.isFinite(n.doubleValue()) ? n.doubleValue():Coords.mcY(z.getZ())+.01;
    }
    static void tick(){
        var cell=IsoWorld.instance.getCell();if(cell==null)return;
        if(!LinkService.minecraftReady()||!ViewpointBridge.viewEnabled()||System.nanoTime()-receivedAt>2_000_000_000L){renderHeights.clear();return;}
        long rev=revision;if(rev==applied)return;var poses=incoming;applied=rev;
        var actors=new java.util.HashMap<Integer,IsoZombie>();for(var z:cell.getZombieList())if(z!=null&&!z.isDead())actors.put(z.getID(),z);
        var seen=new java.util.HashSet<IsoZombie>();
        for(var p:poses){var z=actors.get(p.id());if(z==null||!ground(z)||Math.hypot(z.getX()-p.desiredX(),z.getY()-p.desiredZ())>.75){rejected++;continue;}
            // Apply only meaningful wall corrections; native AI remains free to produce its next horizontal step.
            if(Math.hypot(p.x()-p.desiredX(),p.z()-p.desiredZ())>.015){z.setForceX((float)p.x());z.setForceY((float)p.z());
                var sq=z.findCurrentGridSquare();if(sq!=null){z.setCurrent(sq);z.setMovingSquare(sq);}corrected++;}
            z.getModData().rawset(KEY,Math.abs(p.y())>.02?p.y():null);
            renderHeights.put(z,(float)Coords.pzZ(p.y()));seen.add(z);
        }
        renderHeights.keySet().retainAll(seen);
    }
    public static void beginRender(){renderDepth.set(renderDepth.get()+1);}
    static void release(IsoGameCharacter z){z.getModData().rawset(KEY,null);if(z instanceof IsoZombie zombie)renderHeights.remove(zombie);}
    public static void endRender(){renderDepth.set(Math.max(0,renderDepth.get()-1));}
    public static float renderZ(IsoMovingObject object,float nativeZ){
        if(renderDepth.get()>0&&object instanceof IsoZombie z){Float height=renderHeights.get(z);if(height!=null){rendered++;return height;}}
        return nativeZ;
    }
    static Map<String,Object> stats(){
        var heights=new java.util.LinkedHashMap<String,Object>();renderHeights.forEach((z,y)->heights.put(Integer.toString(z.getID()),Coords.mcY(y)));
        return Map.of("active",renderHeights.size(),"corrected",corrected,"rejected",rejected,"rendered",rendered,"heights",heights);
    }
}

