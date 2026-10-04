package pzcraft.pz;

import java.util.IdentityHashMap;
import java.util.Locale;
import pzcraft.protocol.CollisionSection;
import pzcraft.protocol.MaterialSection;
import pzcraft.protocol.Materials;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.sprite.IsoSprite;

/**
 * Classifies what PZ's tiles are made of (floor, walls, doors, windows, fences, furniture, trees) into the shared
 * {@link Materials} vocabulary. PZ marks sprites with MaterialType / Material / FootstepMaterial tile properties; results
 * are cached per sprite, so a section scan only touches each object once. Game thread only.
 */
final class PzMaterials {
    /** What a sprite is, worked out once from its tile properties and name. */
    private record Info(boolean cutN, boolean cutW, boolean hoppableN, boolean hoppableW, boolean solid, boolean floor,
                        int material, int floorClass, int species) {}

    private static final IdentityHashMap<IsoSprite, Info> infos = new IdentityHashMap<>();

    private PzMaterials() {}

    private static Info info(IsoSprite sprite) {
        Info in = infos.get(sprite);
        if (in != null) return in;
        PropertyContainer p = sprite.getProperties();
        String name = sprite.getName();
        boolean floor = p != null && p.has(IsoFlagType.solidfloor);
        in = new Info(sprite.cutN, sprite.cutW,
                p != null && p.has(IsoFlagType.HoppableN), p != null && p.has(IsoFlagType.HoppableW),
                p != null && (p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans)), floor,
                material(p, name), floor ? floorClass(p, name) : Materials.FLOOR_NONE, species(name));
        infos.put(sprite, in);
        return in;
    }

    // ---- classification of property values ----

    private static int materialFromType(String type) {
        if (type == null) return 0;
        return switch (type) {
            case "Brick" -> Materials.MAT_BRICK;
            case "Wood", "Wood_Solid" -> Materials.MAT_WOOD;
            case "Plaster" -> Materials.MAT_PLASTER;
            case "Metal", "Metal_Light", "Metal_Solid" -> Materials.MAT_METAL;
            case "Metal_Large" -> Materials.MAT_METAL_HEAVY;
            case "Glass", "Glass_Light" -> Materials.MAT_GLASS;
            case "Plastic" -> Materials.MAT_PLASTIC;
            case "Ceramic" -> Materials.MAT_CERAMIC;
            case "Fabric" -> Materials.MAT_FABRIC;
            case "Stone", "Concrete" -> Materials.MAT_STONE;
            default -> type.startsWith("Metal") ? Materials.MAT_METAL : type.startsWith("Glass") ? Materials.MAT_GLASS : 0;
        };
    }

    private static int materialFromMaterial(String m) {
        if (m == null) return 0;
        return switch (m) {
            case "Wood" -> Materials.MAT_WOOD;
            case "Natural" -> Materials.MAT_NATURAL;
            case "MetalPlates", "MetalBars", "MetalWire", "MetalScrap", "SmallMetalPlates", "MetalPipe", "AluminumScrap", "Electric" -> Materials.MAT_METAL;
            case "Fridge" -> Materials.MAT_METAL_HEAVY;
            case "Plastic", "PlasticHard", "PlasticBag" -> Materials.MAT_PLASTIC;
            case "Fabric" -> Materials.MAT_FABRIC;
            case "Glass" -> Materials.MAT_GLASS;
            case "Ceramic", "Plumbing" -> Materials.MAT_CERAMIC;
            case "Stone", "Concrete" -> Materials.MAT_STONE;
            case "Brick" -> Materials.MAT_BRICK;
            default -> 0; // Nails, Screws, Door, Pipes...: say nothing about what the thing is made of
        };
    }

    private static int material(PropertyContainer p, String name) {
        if (p != null) {
            int m = materialFromType(p.get("MaterialType"));
            if (m != 0) return m;
            for (String key : new String[] {"Material", "Material2", "Material3"}) {
                m = materialFromMaterial(p.get(key));
                if (m != 0) return m;
            }
        }
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("brick")) return Materials.MAT_BRICK;
            if (n.contains("stone") || n.contains("concrete")) return Materials.MAT_STONE;
            if (n.contains("glass") || n.contains("window")) return Materials.MAT_GLASS;
            if (n.contains("metal") || n.contains("garage")) return Materials.MAT_METAL;
            if (n.contains("plaster")) return Materials.MAT_PLASTER;
        }
        return Materials.MAT_OTHER;
    }

    private static int floorClass(PropertyContainer p, String name) {
        if (p != null) {
            if (p.has(IsoFlagType.water)) return Materials.FLOOR_WATER;
            String fs = p.get("FootstepMaterial");
            if (fs != null) {
                switch (fs) {
                    case "Grass": return Materials.FLOOR_GRASS;
                    case "Dirt": return Materials.FLOOR_DIRT;
                    case "Sand": return Materials.FLOOR_SAND;
                    case "Gravel": return Materials.FLOOR_GRAVEL;
                    case "Concrete": return Materials.FLOOR_CONCRETE;
                    case "Wood": return Materials.FLOOR_WOOD;
                    case "Carpet": return Materials.FLOOR_CARPET;
                    case "Ceramic": return Materials.FLOOR_CERAMIC;
                    case "Metal": return Materials.FLOOR_METAL;
                    case "Snow": return Materials.FLOOR_SNOW;
                    default: break;
                }
            }
            String fm = p.get("FloorMaterial");
            if (fm != null) {
                if (fm.equals("Water")) return Materials.FLOOR_WATER;
                if (fm.startsWith("Road")) return Materials.FLOOR_ASPHALT;
                if (fm.startsWith("Grass")) return Materials.FLOOR_GRASS;
                if (fm.startsWith("Dirt")) return Materials.FLOOR_DIRT;
                if (fm.startsWith("Sand")) return Materials.FLOOR_SAND;
            }
        }
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("street")) return Materials.FLOOR_ASPHALT;
            if (n.contains("tilesandstone") || n.contains("concrete")) return Materials.FLOOR_CONCRETE;
            if (n.contains("grass")) return Materials.FLOOR_GRASS;
            if (n.contains("carpet")) return Materials.FLOOR_CARPET;
            if (n.contains("floors_interior")) return Materials.FLOOR_WOOD;
        }
        return Materials.FLOOR_OTHER;
    }

    /** Log type of a tree sprite such as {@code e_virginiapine_1_2} or {@code e_redmapleJUMBOXL_1_0}. */
    private static int species(String name) {
        if (name == null) return Materials.TREE_OTHER;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("pine") || n.contains("hemlock") || n.contains("spruce") || n.contains("fir") || n.contains("cedar")) return Materials.TREE_SPRUCE;
        if (n.contains("birch") || n.contains("aspen")) return Materials.TREE_BIRCH;
        if (n.contains("maple")) return Materials.TREE_MAPLE;
        if (n.contains("redbud") || n.contains("dogwood") || n.contains("hawthorn") || n.contains("silverbell") || n.contains("linden")
                || n.contains("holly") || n.contains("magnolia") || n.contains("cherry") || n.contains("yellowwood")) return Materials.TREE_FLOWERING;
        if (n.contains("oak") || n.contains("hickory") || n.contains("beech") || n.contains("elm") || n.contains("ash")) return Materials.TREE_OAK;
        return Materials.TREE_OTHER;
    }

    private static int orElse(int material, int fallback) {
        return material == Materials.MAT_OTHER || material == Materials.MAT_NONE ? fallback : material;
    }

    /** Edge values compete: a door or window beats a wall, a wall beats a frame or a fence. */
    private static int rank(int edge) {
        return switch (Materials.edgeKind(edge)) {
            case Materials.KIND_DOOR, Materials.KIND_WINDOW -> 3;
            case Materials.KIND_WALL -> 2;
            case Materials.KIND_NONE -> 0;
            default -> 1;
        };
    }

    private static int better(int current, int candidate) {
        return rank(candidate) > rank(current) ? candidate : current;
    }

    // ---- section scan ----

    /** What the tiles of one 8x8 column at one level are made of. Mirrors {@link WorldExporter#scan}'s tile loop. */
    static MaterialSection scan(IsoCell cell, int cx, int cy, int level) {
        MaterialSection s = new MaterialSection(cx, cy, level);
        for (int ty = 0; ty < CollisionSection.SIZE; ty++) {
            for (int tx = 0; tx < CollisionSection.SIZE; tx++) {
                int wx = cx * CollisionSection.SIZE + tx, wy = cy * CollisionSection.SIZE + ty;
                IsoGridSquare sq = cell.getGridSquare(wx, wy, level);
                if (sq == null) continue;
                fill(s, ty * CollisionSection.SIZE + tx, sq, cell, wx, wy, level);
            }
        }
        return s;
    }

    private static void fill(MaterialSection s, int idx, IsoGridSquare sq, IsoCell cell, int wx, int wy, int level) {
        int floor = Materials.FLOOR_NONE, solid = 0, edgeN = 0, edgeW = 0, flags = 0, treeSize = 0, treeKind = 0;
        var objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            if (o == null) continue;
            IsoSprite sprite = o.getSprite();
            if (sprite == null) continue;
            Info in = info(sprite);
            if (in.floor && floor == Materials.FLOOR_NONE) floor = in.floorClass;
            if (o instanceof IsoTree tree) {
                treeSize = Math.max(treeSize, Math.max(1, Math.min(8, tree.getSize())));
                treeKind = in.species;
                flags |= Materials.TILE_TREE;
            } else if (o instanceof IsoDoor door) {
                int e = Materials.edge(Materials.KIND_DOOR, orElse(in.material, Materials.MAT_WOOD));
                if (door.getNorth()) edgeN = better(edgeN, e); else edgeW = better(edgeW, e);
            } else if (o instanceof IsoWindow window) {
                int e = Materials.edge(Materials.KIND_WINDOW, Materials.MAT_GLASS);
                if (window.getNorth()) edgeN = better(edgeN, e); else edgeW = better(edgeW, e);
            } else if (o instanceof IsoThumpable t) {
                // Player-built doors, windows, walls and furniture.
                int m = orElse(in.material, Materials.MAT_WOOD);
                if (t.isDoor()) {
                    int e = Materials.edge(Materials.KIND_DOOR, m);
                    if (t.getNorth()) edgeN = better(edgeN, e); else edgeW = better(edgeW, e);
                } else if (t.isWindowN() || t.isWindowW()) {
                    int e = Materials.edge(Materials.KIND_WINDOW, Materials.MAT_GLASS);
                    if (t.isWindowN()) edgeN = better(edgeN, e); else edgeW = better(edgeW, e);
                } else if (in.solid || t.isBlockAllTheSquare()) {
                    if (solid == 0) solid = m;
                } else {
                    int e = Materials.edge(Materials.KIND_WALL, m);
                    if (t.getNorth()) edgeN = better(edgeN, e); else edgeW = better(edgeW, e);
                }
            } else if (o instanceof IsoWindowFrame frame) {
                int e = Materials.edge(Materials.KIND_WINDOW_FRAME, orElse(in.material, Materials.MAT_WOOD));
                if (frame.getNorth()) edgeN = better(edgeN, e); else edgeW = better(edgeW, e);
            } else {
                if (in.cutN && !o.isWallSE()) edgeN = better(edgeN, Materials.edge(Materials.KIND_WALL, orElse(in.material, Materials.MAT_WOOD)));
                if (in.cutW && !o.isWallSE()) edgeW = better(edgeW, Materials.edge(Materials.KIND_WALL, orElse(in.material, Materials.MAT_WOOD)));
                if (in.hoppableN) edgeN = better(edgeN, Materials.edge(Materials.KIND_FENCE, orElse(in.material, Materials.MAT_WOOD)));
                if (in.hoppableW) edgeW = better(edgeW, Materials.edge(Materials.KIND_FENCE, orElse(in.material, Materials.MAT_WOOD)));
                if (in.solid && !in.floor && solid == 0) solid = orElse(in.material, Materials.MAT_OTHER);
            }
        }
        if (floor == Materials.FLOOR_WATER) flags |= Materials.TILE_WATER;
        if (sq.getRoom() != null) flags |= Materials.TILE_INTERIOR;
        if (level == 0 && cell.getGridSquare(wx, wy, -1) != null) flags |= Materials.TILE_BASEMENT;
        s.floor[idx] = (byte) floor;
        s.flags[idx] = (byte) flags;
        s.solid[idx] = (byte) solid;
        s.edgeN[idx] = (byte) edgeN;
        s.edgeW[idx] = (byte) edgeW;
        s.treeSize[idx] = (byte) treeSize;
        s.treeKind[idx] = (byte) treeKind;
    }
}

