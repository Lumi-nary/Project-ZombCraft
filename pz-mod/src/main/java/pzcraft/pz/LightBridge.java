package pzcraft.pz;

import java.util.*;
import pzcraft.protocol.Coords;
import pzcraft.protocol.WorldObjects;
import zombie.iso.*;

final class LightBridge {
    private static volatile List<WorldObjects.Light> incoming=List.of();
    private static volatile long receivedAt;
    private static List<WorldObjects.Light> applied;
    private static IsoCell previous;
    private static final Map<WorldObjects.Light,IsoLightSource> owned=new HashMap<>();
    static void receive(List<WorldObjects.Light> lights){incoming=lights;receivedAt=System.nanoTime();}
    static void tick() {
        var cell=IsoWorld.instance.getCell();if(cell==null)return;
        if(cell!=previous){owned.clear();applied=null;previous=cell;}
        var lights=LinkService.connected()&&System.nanoTime()-receivedAt<3_000_000_000L?incoming:List.<WorldObjects.Light>of();
        if(lights.equals(applied))return;applied=lights;
        var keep=new HashSet<>(lights);
        var it=owned.entrySet().iterator();
        while(it.hasNext()){var e=it.next();if(!keep.contains(e.getKey())){cell.removeLamppost(e.getValue());it.remove();}}
        for(var light:lights)if(!owned.containsKey(light)) {
            int level=(int)Math.floor(Coords.pzZ(light.y()+.5));
            // The exporter already scales RGB for PZ/Viewpoint's doubled lamp contribution.
            var source=new IsoLightSource(light.x(),light.z(),level,light.r(),light.g(),light.b(),Math.max(1,light.emission()));
            source.hydroPowered=false;source.active=true;cell.addLamppost(source);owned.put(light,source);
        }
    }
    static int count(){return owned.size();}
}

