package pzcraft.pz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.MaterialSection;
import pzcraft.protocol.SharedLink;
import pzcraft.protocol.Wire;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.IsoWorld;

/**
 * Streams PZ's walkable geometry to Minecraft as collision sections: floors (including stair/slope height samples),
 * solid tiles (furniture only as tall as its top), and blocked tile edges (walls, closed doors and windows; low fences
 * knee-high). Everything near the
 * player is rescanned in complete columns within a time budget, and a section is only sent when it changed, so doors
 * opening and walls being built show up within a second.
 */
final class WorldExporter {
    /** Chunks (8 tiles) around the player to export, in each direction. */
    private static final int RADIUS_CHUNKS = 3;
    private static final int COLUMNS_PER_FRAME = 8;

    private record Key(int level, int cx, int cy) {
        long id() { return CollisionSection.key64(level, cx, cy); }
    }

    /** Hash of the last version Minecraft acknowledged by us sending it; absent = Minecraft has nothing for this key. */
    private static final Map<Long, Integer> sent = new HashMap<>();
    private static final Map<Long, Key> sentKeys = new HashMap<>();
    /** Same for material sections (what the tiles are made of); they share the collision keys and their clear message. */
    private static final Map<Long, Integer> sentMaterials = new HashMap<>();
    private record Column(int cx,int cy) { long id() { return ((long)cx<<32)|(cy&0xffffffffL); } }
    private static List<Column> queue = new ArrayList<>();
    private static final Map<Long,Long> scanTimes = new HashMap<>();
    private static final java.util.Set<Long> readyColumns = new java.util.HashSet<>();
    private static int cursor;
    private static int lastCenterCx = Integer.MIN_VALUE, lastCenterCy;
    private static int seenEpoch = -1;
    private static long previousTime, queueTime;
    private static volatile double previousX, previousY;
    private static volatile double vx,vy,scanMs;
    private static volatile int loadedAhead, pendingAhead, sentThisFrame;

    private WorldExporter() {}
    /** Something changed on this tile (an explosion, a felled tree): scan its column before anything else. */
    static void invalidate(int tileX, int tileY) {
        scanTimes.remove(new Column(Math.floorDiv(tileX, CollisionSection.SIZE), Math.floorDiv(tileY, CollisionSection.SIZE)).id());
        queueTime = 0; // rebuild the scan queue on the next frame so the column moves to the front
    }
    static void changed() { sent.clear(); sentMaterials.clear(); scanTimes.clear();readyColumns.clear(); lastCenterCx = Integer.MIN_VALUE; }
    static boolean readyAt(double x, double y, double z) {
        int cx=Math.floorDiv((int)Math.floor(x),CollisionSection.SIZE),cy=Math.floorDiv((int)Math.floor(z),CollisionSection.SIZE);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)
            if(!readyColumns.contains(new Column(cx+dx,cy+dy).id()))return false;
        return true;
    }

    static void tick(IsoPlayer player) {
        if (!LinkService.connected() || player == null) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;

        if (seenEpoch != LinkService.epoch()) {
            // Minecraft restarted or respawned: it has forgotten every section, so forget what we think it has.
            seenEpoch = LinkService.epoch();
            sent.clear();
            sentMaterials.clear();
            sentKeys.clear();
            readyColumns.clear(); scanTimes.clear(); previousTime=0;
            lastCenterCx = Integer.MIN_VALUE;
            Log.info("Minecraft (re)connected: resending collision");
        }

        int cx = Math.floorDiv((int) Math.floor(player.getX()), CollisionSection.SIZE);
        int cy = Math.floorDiv((int) Math.floor(player.getY()), CollisionSection.SIZE);
        long now=System.nanoTime();
        if(previousTime!=0) {
            double dt=(now-previousTime)/1e9, dx=player.getX()-previousX,dy=player.getY()-previousY;
            if(dt>.001 && Math.hypot(dx,dy)<12) { vx=vx*.7+dx/dt*.3; vy=vy*.7+dy/dt*.3; }
            else { vx=vy=0; }
        }
        previousTime=now;previousX=player.getX();previousY=player.getY();
        if (cx != lastCenterCx || cy != lastCenterCy || now-queueTime>250_000_000L) {
            lastCenterCx = cx; lastCenterCy = cy; queueTime=now;
            rebuildQueue(cx, cy, now);
        }
        if (queue.isEmpty()) return;

        long start=System.nanoTime(),budget=Math.hypot(vx,vy)>10?4_000_000L:2_000_000L;
        sentThisFrame=0;
        for (int n = 0; n < COLUMNS_PER_FRAME && (n==0 || System.nanoTime()-start<budget); n++) {
            if (cursor >= queue.size()) cursor = 0;
            Column c = queue.get(cursor++);
            scanColumn(cell,c,now);
        }
        scanMs=(System.nanoTime()-start)/1e6;
        loadedAhead=pendingAhead=0;
        double speed=Math.hypot(vx,vy);
        for(int i=0;i<=6;i++) {
            double d=i*8;
            int ax=(int)Math.floor((player.getX()+(speed>1?vx/speed*d:0))/8);
            int ay=(int)Math.floor((player.getY()+(speed>1?vy/speed*d:0))/8);
            if(readyColumns.contains(new Column(ax,ay).id()))loadedAhead++;else pendingAhead++;
        }
    }

    /** Nearest sections first, and forget (tell Minecraft to drop) anything that fell out of range. */
    private static void rebuildQueue(int cx, int cy, long now) {
        java.util.Set<Column> wanted=new java.util.HashSet<>();
        double speed=Math.hypot(vx,vy),distance=Math.min(64,speed*1.5);
        int steps=(int)Math.ceil(distance/8);
        for(int i=0;i<=steps;i++) {
            int leadX=cx+(int)Math.round(speed>1?vx/speed*i:0),leadY=cy+(int)Math.round(speed>1?vy/speed*i:0);
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                for (int dy = -RADIUS_CHUNKS; dy <= RADIUS_CHUNKS; dy++) {
                    wanted.add(new Column(leadX+dx,leadY+dy));
                }
            }
        }
        List<Column> keys=new ArrayList<>(wanted);
        keys.sort(java.util.Comparator.comparingDouble(c -> {
            long age=now-scanTimes.getOrDefault(c.id(),0L);
            double dx=(c.cx-cx)*8,dy=(c.cy-cy)*8;
            double along=speed>1?(dx*vx+dy*vy)/speed:0;
            double lateral=Math.abs(speed>1?(dx*vy-dy*vx)/speed:0);
            // Keep current and nearest unacknowledged terrain first, then the forward corridor, then refresh.
            return (age>1_000_000_000L?-10000:0)+Math.hypot(dx,dy)+(along<0?-along:0)+lateral*.5;
        }));
        queue = keys;
        cursor = 0;
        var columns=readyColumns.iterator();
        while(columns.hasNext()) {
            long id=columns.next();Column c=new Column((int)(id>>32),(int)id);
            if(!wanted.contains(c) && (Math.abs(c.cx-cx)>4 || Math.abs(c.cy-cy)>4) && coverage(c,false)) {
                columns.remove();scanTimes.remove(id);
            }
        }

        Iterator<Map.Entry<Long, Key>> it = sentKeys.entrySet().iterator();
        while (it.hasNext()) {
            Key k = it.next().getValue();
            Column c=new Column(k.cx,k.cy);
            if (!wanted.contains(c) && (Math.abs(k.cx-cx)>RADIUS_CHUNKS+1 || Math.abs(k.cy-cy)>RADIUS_CHUNKS+1)) {
                if(readyColumns.contains(c.id()) && !coverage(c,false))continue;
                readyColumns.remove(c.id()); scanTimes.remove(c.id());
                if (LinkService.send(SharedLink.MSG_COLLISION_CLEAR, CollisionSection.encodeClear(k.level, k.cx, k.cy))) {
                    sent.remove(k.id());
                    sentMaterials.remove(k.id());
                    it.remove();
                }
            }
        }
    }

    private static boolean coverage(Column c,boolean ready) {
        return LinkService.send(SharedLink.MSG_TERRAIN_COLUMN,java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(c.cx).putInt(c.cy).putInt(ready?1:0).array());
    }

    private static void scanColumn(IsoCell cell,Column c,long now) {
        zombie.iso.IsoChunk chunk=cell.getChunkForGridSquare(c.cx*8,c.cy*8,0);
        if(chunk==null || !chunk.loaded) {
            if(readyColumns.contains(c.id()) && coverage(c,false))readyColumns.remove(c.id());
            scanTimes.put(c.id(),now);
            return; // Never mistake a chunk still on the streamer for empty air.
        }
        int lo=Math.max(-32,Math.min(-1,chunk.minLevel)),hi=Math.min(31,Math.max(0,chunk.maxLevel));
        for(int l=lo;l<=hi;l++) if(!scanAndSend(cell,new Key(l,c.cx,c.cy)))return;
        // Retract storeys removed since the previous scan before acknowledging the complete column.
        for(int l=-32;l<=31;l++) if((l<lo||l>hi) && (sent.containsKey(CollisionSection.key64(l,c.cx,c.cy)) || sentMaterials.containsKey(CollisionSection.key64(l,c.cx,c.cy)))) {
            Key k=new Key(l,c.cx,c.cy);
            if(!LinkService.send(SharedLink.MSG_COLLISION_CLEAR,CollisionSection.encodeClear(l,c.cx,c.cy)))return;
            sent.remove(k.id());sentMaterials.remove(k.id());sentKeys.remove(k.id());
        }
        if(!readyColumns.contains(c.id()) && !coverage(c,true))return;
        readyColumns.add(c.id());scanTimes.put(c.id(),now);
    }

    static Map<String,Object> stats() {
        return Map.of("columns",readyColumns.size(),"queue",queue.size(),"sections",sent.size(),"scanMs",scanMs,
                "sentThisFrame",sentThisFrame,"speed",Math.hypot(vx,vy),"velocity",List.of(vx,vy),
                "loadedAhead",loadedAhead,"pendingAhead",pendingAhead);
    }
    static double speed() { return Math.hypot(vx,vy); }
    static double x() { return previousX; }
    static double y() { return previousY; }
    static double velocityX() { return vx; }
    static double velocityY() { return vy; }
    static double aheadX() { return previousX+vx*1.0; }
    static double aheadY() { return previousY+vy*1.0; }

    private static boolean scanAndSend(IsoCell cell, Key k) {
        boolean collision = sendCollision(cell, k);
        boolean materials = sendMaterials(cell, k);
        return collision && materials; // either one failing (ring full) retries next pass; unchanged parts are not resent
    }

    private static boolean sendCollision(IsoCell cell, Key k) {
        CollisionSection s = scan(cell, k);
        Integer previous = sent.get(k.id());
        if (s.isEmpty() && previous == null) return true; // loaded empty sky
        int hash = s.contentHash();
        if (previous != null && previous == hash) return true;
        byte[] payload = s.encode();
        if (LinkService.send(SharedLink.MSG_COLLISION, payload)) {
            sent.put(k.id(), hash);
            sentKeys.put(k.id(), k);
            sentThisFrame++;return true;
        } // else: ring full; the next pass retries
        return false;
    }

    /** What the tiles are made of (floors, walls, objects, trees); sent only when it changed. */
    private static boolean sendMaterials(IsoCell cell, Key k) {
        MaterialSection m = PzMaterials.scan(cell, k.cx, k.cy, k.level);
        Integer previous = sentMaterials.get(k.id());
        if (m.isEmpty() && previous == null) return true;
        int hash = m.contentHash();
        if (previous != null && previous == hash) return true;
        if (LinkService.send(Wire.MSG_MATERIALS, m.encode())) {
            sentMaterials.put(k.id(), hash);
            sentKeys.put(k.id(), k);
            return true;
        }
        return false;
    }

    static CollisionSection scan(IsoCell cell, Key k) {
        CollisionSection s = new CollisionSection(k.cx, k.cy, k.level);
        int n = CollisionSection.RAMP_N;
        for (int ty = 0; ty < CollisionSection.SIZE; ty++) {
            for (int tx = 0; tx < CollisionSection.SIZE; tx++) {
                int wx = k.cx * CollisionSection.SIZE + tx;
                int wy = k.cy * CollisionSection.SIZE + ty;
                IsoGridSquare sq = cell.getGridSquare(wx, wy, k.level);
                if (sq == null) continue;

                int f = 0;
                int idx = ty * CollisionSection.SIZE + tx;
                if (sq.HasStairs() || sq.hasSlopedSurface()) {
                    short[] h = new short[n * n];
                    for (int sy = 0; sy < n; sy++) {
                        for (int sx = 0; sx < n; sx++) {
                            float z = sq.getApparentZ((sx + 0.5f) / n, (sy + 0.5f) / n) - k.level;
                            h[sy * n + sx] = (short) Math.max(0, Math.min(512, Math.round(z * 256f)));
                        }
                    }
                    s.rampHeights[idx] = h;
                    f |= CollisionSection.RAMP;
                } else if (sq.hasFloor()) {
                    f |= CollisionSection.FLOOR;
                }
                // A tile that is only "solid" because of a Minecraft block is already a real block over there.
                // A tree is a Minecraft block over there (mineable, with its own trunk collision), not a PZ box.
                if (!BlockBridge.has(wx, wy, k.level) && !sq.HasTree()) {
                    if (sq.isSolid()) {
                        f |= CollisionSection.SOLID;
                    } else if (sq.isSolidTrans()) {
                        f |= CollisionSection.SOLID;
                        s.heights[idx] = furnitureHeight(sq);
                    }
                }
                f |= edge(sq, cell.getGridSquare(wx, wy - 1, k.level), CollisionSection.EDGE_N, CollisionSection.EDGE_N_LOW);
                f |= edge(sq, cell.getGridSquare(wx - 1, wy, k.level), CollisionSection.EDGE_W, CollisionSection.EDGE_W_LOW);
                s.flags[idx] = (byte) f;
            }
        }
        return s;
    }

    /**
     * What stands on the edge between {@code sq} and its neighbour: walls, closed doors and windows block it for the full
     * storey; a low fence (PZ lets you vault it) only up to Steve's knees, so he can jump it. Deliberately not PZ's own
     * collide test, which also blocks every edge into a solid neighbour: solid tiles get their own box, and a tile made
     * solid by a Minecraft block must not grow invisible storey-high walls around that block.
     */
    private static int edge(IsoGridSquare sq, IsoGridSquare n, int full, int low) {
        if (n == null) return 0;
        IsoObject hop = sq.getHoppableTo(n);
        if (hop != null && hop.getHoppableDirection() != null && !hop.isTallHoppable()) return full | low;
        if (sq.isBlockedTo(n)) return full;
        return hop != null ? full : 0; // an empty window frame: the opening is too small for Steve anyway
    }

    /**
     * Counters, tables and other "solid but see-through" furniture: as tall as their top surface (PZ sprites give it in
     * pixels, 96 to a storey), so Steve can jump onto them like onto a Minecraft block. Unknown heights stay full.
     */
    private static byte furnitureHeight(IsoGridSquare sq) {
        float best = 0f;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject o = sq.getObjects().get(i);
            zombie.core.properties.PropertyContainer p = o.getProperties();
            if (p == null || !(p.has(IsoFlagType.solidtrans) || p.has(IsoFlagType.solid))) continue;
            float px = o.getSurfaceOffsetNoTable();
            if (px < 4f) return 0;
            best = Math.max(best, px);
        }
        int steps = Math.round(best / 96f * CollisionSection.HEIGHT_STEPS);
        return steps <= 0 || steps >= CollisionSection.HEIGHT_STEPS ? 0 : (byte) steps;
    }
}

