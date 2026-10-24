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
 *
 * <p>One exporter per link: the player's own Minecraft gets what is around the local player ({@link #tick(IsoPlayer)});
 * in a PZ server, the Minecraft server gets what is around every player ({@link #hostTick}, multiplayer).
 */
final class WorldExporter {
    /** Chunks (8 tiles) around the player to export, in each direction. */
    private static final int RADIUS_CHUNKS = 3;
    private static final int COLUMNS_PER_FRAME = 8;

    private static final WorldExporter PLAYER = new WorldExporter(LinkService.LINK, "Minecraft");
    /** A PZ server's: the host link to the Minecraft server (made when that link opens). */
    private static volatile WorldExporter host;

    private record Key(int level, int cx, int cy) {
        long id() { return CollisionSection.key64(level, cx, cy); }
    }
    private record Column(int cx,int cy) { long id() { return ((long)cx<<32)|(cy&0xffffffffL); } }
    /** A player the scan follows: where they are and how fast they go (the scan runs ahead of them). */
    private static final class Mover { int cx = Integer.MIN_VALUE, cy; long time; double x, y, vx, vy; }

    private final McLink link;
    private final String peer;
    /** Hash of the last version Minecraft acknowledged by us sending it; absent = Minecraft has nothing for this key. */
    private final Map<Long, Integer> sent = new HashMap<>();
    private final Map<Long, Key> sentKeys = new HashMap<>();
    /** Same for material sections (what the tiles are made of); they share the collision keys and their clear message. */
    private final Map<Long, Integer> sentMaterials = new HashMap<>();
    private List<Column> queue = new ArrayList<>();
    private final Map<Long,Long> scanTimes = new HashMap<>();
    private final java.util.Set<Long> readyColumns = new java.util.HashSet<>();
    private final Map<IsoPlayer, Mover> movers = new HashMap<>();
    private int cursor;
    private int seenEpoch = -1;
    private long queueTime;
    /** The first player followed (the local one in single player), for {@link #stats} and the camera's look-ahead. */
    private volatile double previousX, previousY;
    private volatile double vx,vy,scanMs;
    private volatile int loadedAhead, pendingAhead, sentThisFrame;

    private WorldExporter(McLink link, String peer) { this.link = link; this.peer = peer; }

    /** Multiplayer, PZ server: the host link is open; from now on {@link #hostTick} feeds the Minecraft server. */
    static void startHost(McLink link) { host = new WorldExporter(link, "Minecraft server"); }

    /** Multiplayer, PZ server's game thread: what is around every player, for the Minecraft server. */
    static void hostTick(List<IsoPlayer> players) {
        WorldExporter h = host;
        if (h != null) h.tick(players);
    }

    /** Something changed on this tile (an explosion, a felled tree): scan its column before anything else. */
    static void invalidate(int tileX, int tileY) {
        PLAYER.invalidateColumn(tileX, tileY);
        WorldExporter h = host;
        if (h != null) h.invalidateColumn(tileX, tileY);
    }
    static void changed() {
        PLAYER.forget();
        WorldExporter h = host;
        if (h != null) h.forget();
    }
    static boolean readyAt(double x, double y, double z) { return PLAYER.ready(x, z); }

    static void tick(IsoPlayer player) {
        if (player != null) PLAYER.tick(List.of(player));
    }

    private void invalidateColumn(int tileX, int tileY) {
        scanTimes.remove(new Column(Math.floorDiv(tileX, CollisionSection.SIZE), Math.floorDiv(tileY, CollisionSection.SIZE)).id());
        queueTime = 0; // rebuild the scan queue on the next frame so the column moves to the front
    }
    private void forget() { sent.clear(); sentMaterials.clear(); scanTimes.clear();readyColumns.clear(); queueTime = 0; }
    private boolean ready(double x, double z) {
        int cx=Math.floorDiv((int)Math.floor(x),CollisionSection.SIZE),cy=Math.floorDiv((int)Math.floor(z),CollisionSection.SIZE);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)
            if(!readyColumns.contains(new Column(cx+dx,cy+dy).id()))return false;
        return true;
    }

    private void tick(List<IsoPlayer> players) {
        if (!link.connected()) return;
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;

        if (seenEpoch != link.epoch()) {
            // Minecraft restarted or respawned: it has forgotten every section, so forget what we think it has.
            seenEpoch = link.epoch();
            sent.clear();
            sentMaterials.clear();
            sentKeys.clear();
            readyColumns.clear(); scanTimes.clear(); movers.clear();
            Log.info(peer + " (re)connected: resending collision");
        }

        long now=System.nanoTime();
        boolean moved = movers.keySet().retainAll(players);      // a player left: what only they needed goes
        for (IsoPlayer player : players) {
            Mover m = movers.computeIfAbsent(player, p -> new Mover());
            int cx = Math.floorDiv((int) Math.floor(player.getX()), CollisionSection.SIZE);
            int cy = Math.floorDiv((int) Math.floor(player.getY()), CollisionSection.SIZE);
            if(m.time!=0) {
                double dt=(now-m.time)/1e9, dx=player.getX()-m.x,dy=player.getY()-m.y;
                if(dt>.001 && Math.hypot(dx,dy)<12) { m.vx=m.vx*.7+dx/dt*.3; m.vy=m.vy*.7+dy/dt*.3; }
                else { m.vx=m.vy=0; }
            }
            m.time=now;m.x=player.getX();m.y=player.getY();
            if (cx != m.cx || cy != m.cy) { m.cx = cx; m.cy = cy; moved = true; }
        }
        Mover first = players.isEmpty() ? null : movers.get(players.get(0));
        if (first != null) { previousX = first.x; previousY = first.y; vx = first.vx; vy = first.vy; }
        if (moved || now-queueTime>250_000_000L) {
            queueTime=now;
            rebuildQueue(now);
        }
        if (queue.isEmpty()) return;

        double fastest = 0;
        for (Mover m : movers.values()) fastest = Math.max(fastest, Math.hypot(m.vx, m.vy));
        long start=System.nanoTime(),budget=fastest>10?4_000_000L:2_000_000L;
        sentThisFrame=0;
        for (int n = 0; n < COLUMNS_PER_FRAME && (n==0 || System.nanoTime()-start<budget); n++) {
            if (cursor >= queue.size()) cursor = 0;
            Column c = queue.get(cursor++);
            scanColumn(cell,c,now);
        }
        scanMs=(System.nanoTime()-start)/1e6;
        loadedAhead=pendingAhead=0;
        if (first == null) return;
        double speed=Math.hypot(first.vx,first.vy);
        for(int i=0;i<=6;i++) {
            double d=i*8;
            int ax=(int)Math.floor((first.x+(speed>1?first.vx/speed*d:0))/8);
            int ay=(int)Math.floor((first.y+(speed>1?first.vy/speed*d:0))/8);
            if(readyColumns.contains(new Column(ax,ay).id()))loadedAhead++;else pendingAhead++;
        }
    }

    /** Nearest sections first (to whichever player is nearest), and forget (tell Minecraft to drop) anything that fell out of everyone's range. */
    private void rebuildQueue(long now) {
        java.util.Set<Column> wanted=new java.util.HashSet<>();
        for (Mover m : movers.values()) {
            double speed=Math.hypot(m.vx,m.vy),distance=Math.min(64,speed*1.5);
            int steps=(int)Math.ceil(distance/8);
            for(int i=0;i<=steps;i++) {
                int leadX=m.cx+(int)Math.round(speed>1?m.vx/speed*i:0),leadY=m.cy+(int)Math.round(speed>1?m.vy/speed*i:0);
                for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                    for (int dy = -RADIUS_CHUNKS; dy <= RADIUS_CHUNKS; dy++) {
                        wanted.add(new Column(leadX+dx,leadY+dy));
                    }
                }
            }
        }
        List<Column> keys=new ArrayList<>(wanted);
        keys.sort(java.util.Comparator.comparingDouble(c -> {
            long age=now-scanTimes.getOrDefault(c.id(),0L);
            double best=Double.MAX_VALUE;
            for (Mover m : movers.values()) best=Math.min(best, distance(m, c));
            // Keep current and nearest unacknowledged terrain first, then the forward corridor, then refresh.
            return (age>1_000_000_000L?-10000:0)+best;
        }));
        queue = keys;
        cursor = 0;
        var columns=readyColumns.iterator();
        while(columns.hasNext()) {
            long id=columns.next();Column c=new Column((int)(id>>32),(int)id);
            if(!wanted.contains(c) && !near(c,4) && coverage(c,false)) {
                columns.remove();scanTimes.remove(id);
            }
        }

        Iterator<Map.Entry<Long, Key>> it = sentKeys.entrySet().iterator();
        while (it.hasNext()) {
            Key k = it.next().getValue();
            Column c=new Column(k.cx,k.cy);
            if (!wanted.contains(c) && !near(c, RADIUS_CHUNKS+1)) {
                if(readyColumns.contains(c.id()) && !coverage(c,false))continue;
                readyColumns.remove(c.id()); scanTimes.remove(c.id());
                if (link.send(SharedLink.MSG_COLLISION_CLEAR, CollisionSection.encodeClear(k.level, k.cx, k.cy))) {
                    sent.remove(k.id());
                    sentMaterials.remove(k.id());
                    it.remove();
                }
            }
        }
    }

    /** How soon a column is wanted for this player: near first, then ahead of them rather than behind or aside. */
    private static double distance(Mover m, Column c) {
        double speed=Math.hypot(m.vx,m.vy);
        double dx=(c.cx-m.cx)*8,dy=(c.cy-m.cy)*8;
        double along=speed>1?(dx*m.vx+dy*m.vy)/speed:0;
        double lateral=Math.abs(speed>1?(dx*m.vy-dy*m.vx)/speed:0);
        return Math.hypot(dx,dy)+(along<0?-along:0)+lateral*.5;
    }

    /** Within {@code r} columns of any player followed. */
    private boolean near(Column c, int r) {
        for (Mover m : movers.values()) if (Math.abs(c.cx-m.cx)<=r && Math.abs(c.cy-m.cy)<=r) return true;
        return false;
    }

    private boolean coverage(Column c,boolean ready) {
        return link.send(SharedLink.MSG_TERRAIN_COLUMN,java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(c.cx).putInt(c.cy).putInt(ready?1:0).array());
    }

    private void scanColumn(IsoCell cell,Column c,long now) {
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
            if(!link.send(SharedLink.MSG_COLLISION_CLEAR,CollisionSection.encodeClear(l,c.cx,c.cy)))return;
            sent.remove(k.id());sentMaterials.remove(k.id());sentKeys.remove(k.id());
        }
        if(!readyColumns.contains(c.id()) && !coverage(c,true))return;
        readyColumns.add(c.id());scanTimes.put(c.id(),now);
    }

    static Map<String,Object> stats() { return PLAYER.statsOf(); }
    private Map<String,Object> statsOf() {
        return Map.of("columns",readyColumns.size(),"queue",queue.size(),"sections",sent.size(),"scanMs",scanMs,
                "sentThisFrame",sentThisFrame,"speed",Math.hypot(vx,vy),"velocity",List.of(vx,vy),
                "loadedAhead",loadedAhead,"pendingAhead",pendingAhead);
    }
    static double speed() { return Math.hypot(PLAYER.vx,PLAYER.vy); }
    static double x() { return PLAYER.previousX; }
    static double y() { return PLAYER.previousY; }
    static double velocityX() { return PLAYER.vx; }
    static double velocityY() { return PLAYER.vy; }
    static double aheadX() { return PLAYER.previousX+PLAYER.vx*1.0; }
    static double aheadY() { return PLAYER.previousY+PLAYER.vy*1.0; }

    private boolean scanAndSend(IsoCell cell, Key k) {
        boolean collision = sendCollision(cell, k);
        boolean materials = sendMaterials(cell, k);
        return collision && materials; // either one failing (ring full) retries next pass; unchanged parts are not resent
    }

    private boolean sendCollision(IsoCell cell, Key k) {
        CollisionSection s = scan(cell, k);
        Integer previous = sent.get(k.id());
        if (s.isEmpty() && previous == null) return true; // loaded empty sky
        int hash = s.contentHash();
        if (previous != null && previous == hash) return true;
        byte[] payload = s.encode();
        if (link.send(SharedLink.MSG_COLLISION, payload)) {
            sent.put(k.id(), hash);
            sentKeys.put(k.id(), k);
            sentThisFrame++;return true;
        } // else: ring full; the next pass retries
        return false;
    }

    /** What the tiles are made of (floors, walls, objects, trees); sent only when it changed. */
    private boolean sendMaterials(IsoCell cell, Key k) {
        MaterialSection m = PzMaterials.scan(cell, k.cx, k.cy, k.level);
        Integer previous = sentMaterials.get(k.id());
        if (m.isEmpty() && previous == null) return true;
        int hash = m.contentHash();
        if (previous != null && previous == hash) return true;
        if (link.send(Wire.MSG_MATERIALS, m.encode())) {
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
                // a roof follows the slope Viewpoint draws (PZ gives it a flat floor or none, B28)
                float[] roof = sq.HasStairs() || sq.hasSlopedSurface() ? null : RoofShapes.heights(sq, n);
                if (roof != null) {
                    short[] h = new short[n * n];
                    for (int i = 0; i < h.length; i++) h[i] = (short) Math.max(0, Math.min(512, Math.round(roof[i] * 256f)));
                    s.rampHeights[idx] = h;
                    f |= CollisionSection.RAMP;
                } else if (sq.HasStairs() || sq.hasSlopedSurface()) {
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
                int well = (f & CollisionSection.SOLID) != 0 ? 0 : stairwell(cell, wx, wy, k.level, f);
                if (well != 0) {
                    f |= CollisionSection.STAIRWELL;
                    s.heights[idx] = (byte) well;
                }
                s.flags[idx] = (byte) f;
            }
        }
        return s;
    }

    /**
     * Tile (x, y, level) against the stairs on the storey below (CollisionSection.STAIRWELL, B29): a floor stops short of the
     * sides where a staircase is below its neighbour (TRIM_*), and a wall on its north or west edge starts higher when a
     * staircase is below either side of that edge (LIFT_*). 0 when no staircase is near.
     */
    private static int stairwell(IsoCell cell, int x, int y, int level, int flags) {
        int bits = 0;
        boolean below = stairs(cell, x, y, level - 1);
        if ((flags & CollisionSection.FLOOR) != 0) {
            if (stairs(cell, x, y - 1, level - 1)) bits |= CollisionSection.TRIM_N;
            if (stairs(cell, x + 1, y, level - 1)) bits |= CollisionSection.TRIM_E;
            if (stairs(cell, x, y + 1, level - 1)) bits |= CollisionSection.TRIM_S;
            if (stairs(cell, x - 1, y, level - 1)) bits |= CollisionSection.TRIM_W;
        }
        if ((flags & CollisionSection.EDGE_N) != 0 && (below || stairs(cell, x, y - 1, level - 1))) bits |= CollisionSection.LIFT_N;
        if ((flags & CollisionSection.EDGE_W) != 0 && (below || stairs(cell, x - 1, y, level - 1))) bits |= CollisionSection.LIFT_W;
        return bits;
    }

    private static boolean stairs(IsoCell cell, int x, int y, int level) {
        IsoGridSquare sq = cell.getGridSquare(x, y, level);
        return sq != null && sq.HasStairs();
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
