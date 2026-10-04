package pzcraft.mc;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A ready column includes every PZ storey, including known empty sky. Both simulation threads use it. */
public final class TerrainCoverage {
    private static final Set<Long> ready = ConcurrentHashMap.newKeySet();
    private static volatile long heldUntil, holds;
    private TerrainCoverage() {}
    private static long key(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
    public static void column(int x, int z, boolean loaded) {
        if (loaded) ready.add(key(x,z)); else ready.remove(key(x,z));
    }
    public static void reset() { ready.clear(); }
    public static boolean known(double x, double z) { return ready.contains(key((int)Math.floor(x/8), (int)Math.floor(z/8))); }
    public static boolean loading() { return System.nanoTime() < heldUntil; }
    public static java.util.Map<String,Object> stats() {
        return java.util.Map.of("columns", ready.size(), "loading", loading(), "holds", holds);
    }
    public static Vec3 guard(Entity entity, Vec3 motion) {
        if (!Session.pzConnected || !Session.released || !Session.steveDrives || Session.playerUuid == null) return motion;
        boolean player=entity.getUUID().equals(Session.playerUuid);
        if(!player)for(var passenger:entity.getPassengers())if(passenger.getUUID().equals(Session.playerUuid)){player=true;break;}
        if(!player)return motion;
        AABB box = entity.getBoundingBox();
        boolean currentReady=true;
        for(int x=(int)Math.floor(box.minX/8);x<=(int)Math.floor((box.maxX-1e-7)/8);x++)
            for(int z=(int)Math.floor(box.minZ/8);z<=(int)Math.floor((box.maxZ-1e-7)/8);z++)
                if(!ready.contains(key(x,z)))currentReady=false;
        if (!currentReady) {
            heldUntil = System.nanoTime()+500_000_000L; holds++;
            entity.setDeltaMovement(Vec3.ZERO);
            return Vec3.ZERO;
        }
        AABB sweep = box.expandTowards(motion);
        var barriers = new ArrayList<VoxelShape>();
        int x0=(int)Math.floor(sweep.minX/8), x1=(int)Math.floor((sweep.maxX-1e-7)/8);
        int z0=(int)Math.floor(sweep.minZ/8), z1=(int)Math.floor((sweep.maxZ-1e-7)/8);
        for(int x=x0;x<=x1;x++) for(int z=z0;z<=z1;z++) if(!ready.contains(key(x,z)))
            barriers.add(Shapes.create(x*8, -10000, z*8, x*8+8, 10000, z*8+8));
        if(barriers.isEmpty()) return motion;
        double dx=Shapes.collide(Direction.Axis.X,box,barriers,motion.x);
        double dz=Shapes.collide(Direction.Axis.Z,box.move(dx,0,0),barriers,motion.z);
        if(dx!=motion.x || dz!=motion.z) {
            heldUntil=System.nanoTime()+500_000_000L; holds++;
            var v=entity.getDeltaMovement();
            entity.setDeltaMovement(dx!=motion.x?0:v.x,v.y,dz!=motion.z?0:v.z);
        }
        return new Vec3(dx,motion.y,dz);
    }
}

