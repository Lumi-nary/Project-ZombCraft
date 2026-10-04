package pzcraft.mc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.ActorGround;
import pzcraft.protocol.Wire;

/** PZ supplies horizontal intent; Minecraft settles ground zombies against real blocks and native collision. */
final class ActorTerrain {
    private static final Map<Integer,Double> falling=new HashMap<>();
    private ActorTerrain() {}
    static ActorGround.Pose move(ServerLevel level,Entity e,Wire.Actor a) {
        int x=(int)Math.floor(a.x()),z=(int)Math.floor(a.z());
        if((a.flags()&Wire.Actor.FLAG_GROUND_PHYSICS)==0 || !GroundBridge.isGroundColumn(x,z)
                || !TerrainCoverage.known(a.x(),a.z())) {falling.remove(a.id());return null;}
        // A native teleport or newly loaded actor starts from its persisted height. Ordinary snapshots never reset falling.
        if(Math.hypot(e.getX()-a.x(),e.getZ()-a.z())>1.5){e.snapTo(a.x(),a.y(),a.z(),a.yawDeg(),0);falling.remove(a.id());}
        int depth=Math.min(GroundBridge.MAX_DEPTH,Math.max(3,(int)Math.ceil(-e.getY())+2));
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)GroundBridge.ensure(level,x+dx,z+dz,depth);
        // Refilled ground may now surround the feet. Lift only an embedded actor to the first clear space above it.
        var feet=net.minecraft.core.BlockPos.containing(e.getX(),e.getY()+.01,e.getZ());
        if(!level.getBlockState(feet).getCollisionShape(level,feet).isEmpty()){
            for(int up=1;up<=8;up++){
                double y=Math.floor(e.getY())+up+.01,dy=y-e.getY();
                if(level.noCollision(e,e.getBoundingBox().move(0,dy,0))){e.snapTo(e.getX(),y,e.getZ(),a.yawDeg(),0);falling.remove(a.id());break;}
            }
        }
        double vy=Math.max(-3.92,(falling.getOrDefault(a.id(),0d)-.08)*.98);
        e.setYRot(a.yawDeg());
        e.move(MoverType.SELF,new Vec3(a.x()-e.getX(),vy,a.z()-e.getZ()));
        e.setDeltaMovement(Vec3.ZERO);
        if(e.verticalCollision)vy=0;
        falling.put(a.id(),vy);
        // Entity.move performs vanilla collision, on-ground and block-event handling; no proxy health/drops own the fall.
        return new ActorGround.Pose(a.id(),a.x(),a.z(),e.getX(),e.getY(),e.getZ());
    }
    static void forget(int id){falling.remove(id);}
    static boolean controls(int id){return falling.containsKey(id);}
    static void retain(Set<Integer> ids){falling.keySet().retainAll(ids);}
    static void clear(){falling.clear();}
}

