package pzcraft.mc;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import pzcraft.protocol.CollisionBoxes;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.Coords;

/**
 * PZ's world as extra collision boxes. Not blocks: they never occupy the block grid, so blocks the player places can
 * sit on or beside PZ geometry. Written by the link thread, read by the client and server threads; sections are
 * immutable once published.
 */
public final class CollisionField {
    private record Section(double[] boxes, VoxelShape[] shapes) {}

    private static final ConcurrentHashMap<Long, Section> SECTIONS = new ConcurrentHashMap<>();
    private static final double PAD = 0.25;
    private static volatile Section[] vehicles = new Section[0];

    private CollisionField() {}
    public static void reset() {SECTIONS.clear();vehicles=new Section[0];}

    public static void vehicles(List<pzcraft.protocol.VehicleHull> hulls) {
        Section[] next = new Section[hulls.size()];
        for (int j = 0; j < next.length; j++) {
            double[] boxes = hulls.get(j).boxes();
            VoxelShape[] shapes = new VoxelShape[boxes.length / 6];
            for (int i = 0; i < shapes.length; i++) {
                int o = i * 6;
                shapes[i] = Shapes.create(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5]);
            }
            next[j] = new Section(boxes, shapes);
        }
        vehicles = next;
    }

    public static void put(CollisionSection s) {
        double[] boxes = CollisionBoxes.build(s);
        if (boxes.length == 0) {
            SECTIONS.remove(s.key64());
            return;
        }
        VoxelShape[] shapes = new VoxelShape[boxes.length / 6];
        for (int i = 0; i < shapes.length; i++) {
            int o = i * 6;
            shapes[i] = Shapes.create(boxes[o], boxes[o + 1], boxes[o + 2], boxes[o + 3], boxes[o + 4], boxes[o + 5]);
        }
        SECTIONS.put(s.key64(), new Section(boxes, shapes));
    }

    public static void remove(int level, int cx, int cy) {
        SECTIONS.remove(CollisionSection.key64(level, cx, cy));
    }

    public static int sectionCount() {
        return SECTIONS.size();
    }

    /** True if some collision exists in the 8x8-tile column containing this MC x/z, at any level. */
    public static boolean hasSectionAt(double mcX, double mcZ) {
        int cx = (int) Math.floor(mcX / CollisionSection.SIZE);
        int cy = (int) Math.floor(mcZ / CollisionSection.SIZE);
        for (int level = -8; level <= 24; level++) {
            if (SECTIONS.containsKey(CollisionSection.key64(level, cx, cy))) return true;
        }
        return false;
    }

    /** A ray hit on PZ geometry: where, which face of the box, and the air voxel just in front of that face. */
    public record Hit(net.minecraft.world.phys.Vec3 point, net.minecraft.core.Direction face, net.minecraft.core.BlockPos air, double distance) {}

    /** First PZ box along the ray within maxDist, or null. */
    public static Hit raycast(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 dir, double maxDist) {
        if (maxDist <= 0) return null;
        net.minecraft.world.phys.Vec3 to = from.add(dir.scale(maxDist));
        AABB region = new AABB(from, to).inflate(0.3);
        double h = Coords.BLOCKS_PER_LEVEL;
        int cx0 = (int) Math.floor(region.minX / CollisionSection.SIZE), cx1 = (int) Math.floor(region.maxX / CollisionSection.SIZE);
        int cy0 = (int) Math.floor(region.minZ / CollisionSection.SIZE), cy1 = (int) Math.floor(region.maxZ / CollisionSection.SIZE);
        int lv0 = (int) Math.floor(region.minY / h) - 1, lv1 = (int) Math.floor(region.maxY / h) + 1;
        double bestT = Double.MAX_VALUE;
        int bestAxis = -1;
        double[] d = {dir.x, dir.y, dir.z};
        double[] o = {from.x, from.y, from.z};
        var candidates = new java.util.ArrayList<Section>();
        candidates.addAll(java.util.Arrays.asList(vehicles));
        for (int level = lv0; level <= lv1; level++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cy = cy0; cy <= cy1; cy++) {
                    Section s = SECTIONS.get(CollisionSection.key64(level, cx, cy));
                    if (s != null) candidates.add(s);
                }
            }
        }
        for (Section s : candidates) {
                    double[] b = s.boxes;
                    for (int i = 0, n = b.length / 6; i < n; i++) {
                        int off = i * 6;
                        double tMin = 0, tMax = maxDist;
                        int axisIn = -1;
                        boolean hit = true;
                        for (int ax = 0; ax < 3; ax++) {
                            double lo = b[off + ax], hi = b[off + 3 + ax];
                            if (Math.abs(d[ax]) < 1e-12) {
                                if (o[ax] < lo || o[ax] > hi) { hit = false; break; }
                            } else {
                                double t1 = (lo - o[ax]) / d[ax], t2 = (hi - o[ax]) / d[ax];
                                double tn = Math.min(t1, t2), tf = Math.max(t1, t2);
                                if (tn > tMin) { tMin = tn; axisIn = ax; }
                                tMax = Math.min(tMax, tf);
                                if (tMin > tMax) { hit = false; break; }
                            }
                        }
                        if (hit && axisIn >= 0 && tMin > 0 && tMin < bestT) { bestT = tMin; bestAxis = axisIn; }
                    }
        }
        if (bestAxis < 0) return null;
        net.minecraft.world.phys.Vec3 point = from.add(dir.scale(bestT));
        net.minecraft.core.Direction face = switch (bestAxis) {
            case 0 -> dir.x > 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST;
            case 1 -> dir.y > 0 ? net.minecraft.core.Direction.DOWN : net.minecraft.core.Direction.UP;
            default -> dir.z > 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH;
        };
        net.minecraft.world.phys.Vec3 nudged = point.add(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
        return new Hit(point, face, net.minecraft.core.BlockPos.containing(nudged), bestT);
    }

    /**
     * True if some PZ box (wall, furniture, floor, vehicle) reaches more than {@code depth} into {@code area} on every
     * axis. A block placed against a wall only grazes its thin edge box; one placed inside a wall, a counter, the floor
     * above or a car overlaps deeply.
     */
    public static boolean overlapsDeeply(AABB area, double depth) {
        List<VoxelShape> found = new java.util.ArrayList<>();
        collect(area, found);
        for (VoxelShape shape : found) {
            for (AABB b : shape.toAabbs()) {
                double x = Math.min(b.maxX, area.maxX) - Math.max(b.minX, area.minX);
                double y = Math.min(b.maxY, area.maxY) - Math.max(b.minY, area.minY);
                double z = Math.min(b.maxZ, area.maxZ) - Math.max(b.minZ, area.minZ);
                if (x > depth && y > depth && z > depth) return true;
            }
        }
        return false;
    }

    public static VoxelShape localShape(net.minecraft.core.BlockPos pos) {
        var box = new AABB(pos);
        var shapes = new java.util.ArrayList<VoxelShape>();
        collect(box.inflate(.005), shapes);
        VoxelShape result = net.minecraft.world.phys.shapes.Shapes.empty();
        for (var shape : shapes) for (var b : shape.toAabbs()) {
            double x0 = Math.max(b.minX, box.minX), y0 = Math.max(b.minY, box.minY), z0 = Math.max(b.minZ, box.minZ);
            double x1 = Math.min(b.maxX, box.maxX), y1 = Math.min(b.maxY, box.maxY), z1 = Math.min(b.maxZ, box.maxZ);
            if (x1 > x0 && y1 > y0 && z1 > z0) result = net.minecraft.world.phys.shapes.Shapes.or(result,
                    net.minecraft.world.phys.shapes.Shapes.box(x0-pos.getX(), y0-pos.getY(), z0-pos.getZ(), x1-pos.getX(), y1-pos.getY(), z1-pos.getZ()));
        }
        return result;
    }
    /** A saved spawn uses integer block Y; PZ storeys can have fractional walking surfaces. */
    public static double spawnSurface(double x,double y,double z) {
        var shapes=new java.util.ArrayList<VoxelShape>();
        collect(new AABB(x-.29,y-.2,z-.29,x+.29,y+.9,z+.29),shapes);
        double best=y,distance=Double.MAX_VALUE;
        for(var shape:shapes)for(var b:shape.toAabbs()) {
            if(b.minX<=x&&b.maxX>=x&&b.minZ<=z&&b.maxZ>=z&&b.maxY>=y-.1&&b.maxY<=y+.9
                    &&Math.abs(b.maxY-y)<distance){best=b.maxY+.02;distance=Math.abs(b.maxY-y);}
        }
        return best;
    }
    public static boolean supports(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction face) {
        var shapes = new java.util.ArrayList<VoxelShape>();
        collect(new AABB(pos).inflate(.01), shapes);
        double cx=pos.getX()+.5, cy=pos.getY()+.5, cz=pos.getZ()+.5;
        for (var shape : shapes) for (var b : shape.toAabbs()) {
            if (face == net.minecraft.core.Direction.UP && Math.abs(b.maxY-(pos.getY()+1))<.02 && b.minX<=cx && b.maxX>=cx && b.minZ<=cz && b.maxZ>=cz) return true;
            if (face == net.minecraft.core.Direction.DOWN && Math.abs(b.minY-pos.getY())<.02 && b.minX<=cx && b.maxX>=cx && b.minZ<=cz && b.maxZ>=cz) return true;
            if (face == net.minecraft.core.Direction.EAST && Math.abs(b.maxX-pos.getX()-1)<.13 && b.minY<=cy && b.maxY>=cy && b.minZ<=cz && b.maxZ>=cz) return true;
            if (face == net.minecraft.core.Direction.WEST && Math.abs(b.minX-pos.getX())<.13 && b.minY<=cy && b.maxY>=cy && b.minZ<=cz && b.maxZ>=cz) return true;
            if (face == net.minecraft.core.Direction.SOUTH && Math.abs(b.maxZ-pos.getZ()-1)<.13 && b.minY<=cy && b.maxY>=cy && b.minX<=cx && b.maxX>=cx) return true;
            if (face == net.minecraft.core.Direction.NORTH && Math.abs(b.minZ-pos.getZ())<.13 && b.minY<=cy && b.maxY>=cy && b.minX<=cx && b.maxX>=cx) return true;
        }
        return false;
    }
    public static boolean blocksFlow(net.minecraft.core.BlockPos from,net.minecraft.core.BlockPos to) {
        net.minecraft.world.phys.Vec3 a=net.minecraft.world.phys.Vec3.atCenterOf(from), b=net.minecraft.world.phys.Vec3.atCenterOf(to);
        var found=new java.util.ArrayList<VoxelShape>();collect(new AABB(a,b).inflate(.01),found);
        for(var shape:found)for(var box:shape.toAabbs())if(box.clip(a,b).isPresent()||box.contains(b))return true;
        return false;
    }

    /** Appends every PZ box overlapping {@code area} to {@code out}. */
    public static void collect(AABB area, List<VoxelShape> out) {
        for (Section s : vehicles) {
            double[] b = s.boxes;
            for (int i = 0; i < s.shapes.length; i++) {
                int o = i * 6;
                if (b[o] < area.maxX && b[o + 3] > area.minX && b[o + 1] < area.maxY
                        && b[o + 4] > area.minY && b[o + 2] < area.maxZ && b[o + 5] > area.minZ) out.add(s.shapes[i]);
            }
        }
        if (SECTIONS.isEmpty()) return;
        double h = Coords.BLOCKS_PER_LEVEL;
        int cx0 = (int) Math.floor((area.minX - PAD) / CollisionSection.SIZE);
        int cx1 = (int) Math.floor((area.maxX + PAD) / CollisionSection.SIZE);
        int cy0 = (int) Math.floor((area.minZ - PAD) / CollisionSection.SIZE);
        int cy1 = (int) Math.floor((area.maxZ + PAD) / CollisionSection.SIZE);
        int lv0 = (int) Math.floor(area.minY / h) - 1;
        int lv1 = (int) Math.floor(area.maxY / h) + 1;
        for (int level = lv0; level <= lv1; level++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cy = cy0; cy <= cy1; cy++) {
                    Section s = SECTIONS.get(CollisionSection.key64(level, cx, cy));
                    if (s == null) continue;
                    double[] b = s.boxes;
                    for (int i = 0, n = b.length / 6; i < n; i++) {
                        int o = i * 6;
                        if (b[o] < area.maxX && b[o + 3] > area.minX
                                && b[o + 1] < area.maxY && b[o + 4] > area.minY
                                && b[o + 2] < area.maxZ && b[o + 5] > area.minZ) {
                            out.add(s.shapes[i]);
                        }
                    }
                }
            }
        }
    }
}

