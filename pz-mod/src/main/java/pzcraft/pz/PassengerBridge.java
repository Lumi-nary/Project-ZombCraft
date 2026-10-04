package pzcraft.pz;

import java.util.*;
import pzcraft.protocol.*;
import zombie.characters.IsoZombie;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoWorld;

/** Vanilla vehicles own the mounted pose; PZ's AI and movement resume when the snapshot releases the rider. */
public final class PassengerBridge {
    private static volatile List<Passengers.Rider> incoming=List.of();
    private static volatile long receivedAt;
    private static final Map<Integer,Passengers.Rider> mounted=new HashMap<>();
    private PassengerBridge(){}
    static void receive(List<Passengers.Rider> riders){incoming=riders;receivedAt=System.nanoTime();}
    static void tick(){
        var cell=IsoWorld.instance.getCell();if(cell==null)return;
        var riders=LinkService.connected()&&System.nanoTime()-receivedAt<2_000_000_000L?incoming:List.<Passengers.Rider>of();
        var byId=new HashMap<Integer,IsoGameCharacter>();for(var z:cell.getZombieList())if(z!=null)byId.put(z.getID(),z);
        for(var animal:cell.getAnimals())byId.put(animal.getID(),animal);
        var seen=new HashSet<Integer>();
        for(var r:riders){var z=byId.get(r.actorId());if(z==null||z.isDead())continue;
            if(r.vehicleId()!=0){seen.add(r.actorId());mounted.put(r.actorId(),r);apply(z,r);}
            else{apply(z,r);mounted.remove(r.actorId());}
        }
        mounted.keySet().retainAll(seen);
    }
    public static boolean holds(IsoGameCharacter zombie){
        var rider=mounted.get(zombie.getID());if(rider==null||zombie.isDead())return false;
        apply(zombie,rider);return true;
    }
    private static void apply(IsoGameCharacter z,Passengers.Rider r){
        ActorTerrain.release(z);
        if(z instanceof IsoZombie zombie)zombie.setTarget(null);
        z.setForceX((float)r.x());z.setForceY((float)r.z());z.setZ((float)Coords.pzZ(r.y()));
        var sq=z.findCurrentGridSquare();if(sq!=null){z.setCurrent(sq);z.setMovingSquare(sq);}
        z.setDirectionAngle(Coords.mcYawDegToPzRad(r.yaw()));
    }
    static int count(){return mounted.size();}
}

