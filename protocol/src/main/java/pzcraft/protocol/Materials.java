package pzcraft.protocol;

/**
 * What PZ's objects are made of, in terms both bridges share: PZ classifies its sprites into these classes, Minecraft
 * turns them into blast resistance, mining hardness and ground blocks. The numbers follow vanilla Minecraft so that a
 * PZ wall resists TNT about as much as the Minecraft block it resembles.
 */
public final class Materials {
    private Materials() {}

    // ---- floors: PZ's FootstepMaterial / FloorMaterial of the floor sprite ----
    public static final int FLOOR_NONE = 0, FLOOR_GRASS = 1, FLOOR_DIRT = 2, FLOOR_SAND = 3, FLOOR_GRAVEL = 4,
            FLOOR_CONCRETE = 5, FLOOR_ASPHALT = 6, FLOOR_WOOD = 7, FLOOR_CARPET = 8, FLOOR_CERAMIC = 9,
            FLOOR_METAL = 10, FLOOR_SNOW = 11, FLOOR_WATER = 12, FLOOR_OTHER = 13;
    public static final int FLOOR_CLASSES = 14;

    // ---- what a wall, door, window, fence or piece of furniture is made of: PZ's MaterialType / Material ----
    public static final int MAT_NONE = 0, MAT_WOOD = 1, MAT_BRICK = 2, MAT_PLASTER = 3, MAT_METAL = 4, MAT_METAL_HEAVY = 5,
            MAT_GLASS = 6, MAT_STONE = 7, MAT_CERAMIC = 8, MAT_PLASTIC = 9, MAT_FABRIC = 10, MAT_NATURAL = 11, MAT_OTHER = 12;
    public static final int MATERIAL_CLASSES = 13;

    // ---- what stands on a tile edge ----
    public static final int KIND_NONE = 0, KIND_WALL = 1, KIND_DOOR = 2, KIND_WINDOW = 3, KIND_FENCE = 4, KIND_BARRICADE = 5,
            KIND_WINDOW_FRAME = 6;

    // ---- tile flags ----
    /** A tree stands on the tile (see treeSize / treeKind). */
    public static final int TILE_TREE = 1;
    /** PZ squares exist below this tile (a basement): there is no natural ground to dig into. */
    public static final int TILE_BASEMENT = 1 << 1;
    public static final int TILE_WATER = 1 << 2;
    /** Inside a building (a room covers the tile). */
    public static final int TILE_INTERIOR = 1 << 3;

    // ---- tree species (log type) ----
    public static final int TREE_OAK = 0, TREE_SPRUCE = 1, TREE_BIRCH = 2, TREE_MAPLE = 3, TREE_FLOWERING = 4, TREE_OTHER = 5;
    public static final int TREE_KINDS = 6;

    public static int edge(int kind, int material) { return (kind & 15) << 4 | (material & 15); }
    public static int edgeKind(int edge) { return (edge >> 4) & 15; }
    public static int edgeMaterial(int edge) { return edge & 15; }

    /**
     * Minecraft explosion resistance of a material. A ray pays {@code (resistance + 0.3) * 0.3} to cross one sample step
     * of it and destroys it if power is left afterwards, exactly as vanilla does for blocks.
     */
    public static float resistance(int material) {
        return switch (material) {
            case MAT_WOOD -> 3.0f;
            case MAT_BRICK -> 6.0f;
            case MAT_PLASTER -> 2.0f;
            case MAT_METAL -> 6.0f;
            case MAT_METAL_HEAVY -> 12.0f;
            case MAT_GLASS -> 0.3f;
            case MAT_STONE -> 6.0f;
            case MAT_CERAMIC -> 2.0f;
            case MAT_PLASTIC -> 1.5f;
            case MAT_FABRIC -> 0.8f;
            case MAT_NATURAL -> 0.2f;
            default -> 3.0f;
        };
    }

    /** Resistance of a floor surface. */
    public static float floorResistance(int floor) {
        return switch (floor) {
            case FLOOR_GRASS, FLOOR_DIRT, FLOOR_SAND -> 0.5f;
            case FLOOR_GRAVEL -> 0.6f;
            case FLOOR_CONCRETE, FLOOR_ASPHALT, FLOOR_METAL -> 6.0f;
            case FLOOR_WOOD -> 3.0f;
            case FLOOR_CARPET -> 1.0f;
            case FLOOR_CERAMIC -> 2.0f;
            case FLOOR_SNOW -> 0.2f;
            default -> 3.0f;
        };
    }

    /** How many ray samples (0.3 blocks each) a thing of this kind soaks up. Thin edges count once, bulky objects thrice. */
    public static int thicknessSamples(int kind) {
        return switch (kind) {
            case KIND_WINDOW, KIND_WINDOW_FRAME, KIND_FENCE -> 1;
            case KIND_BARRICADE -> 1;
            default -> 2;
        };
    }

    public static String floorName(int floor) {
        return switch (floor) {
            case FLOOR_NONE -> "none"; case FLOOR_GRASS -> "grass"; case FLOOR_DIRT -> "dirt"; case FLOOR_SAND -> "sand";
            case FLOOR_GRAVEL -> "gravel"; case FLOOR_CONCRETE -> "concrete"; case FLOOR_ASPHALT -> "asphalt";
            case FLOOR_WOOD -> "wood"; case FLOOR_CARPET -> "carpet"; case FLOOR_CERAMIC -> "ceramic";
            case FLOOR_METAL -> "metal"; case FLOOR_SNOW -> "snow"; case FLOOR_WATER -> "water"; default -> "other";
        };
    }

    public static String materialName(int material) {
        return switch (material) {
            case MAT_NONE -> "none"; case MAT_WOOD -> "wood"; case MAT_BRICK -> "brick"; case MAT_PLASTER -> "plaster";
            case MAT_METAL -> "metal"; case MAT_METAL_HEAVY -> "metal-heavy"; case MAT_GLASS -> "glass";
            case MAT_STONE -> "stone"; case MAT_CERAMIC -> "ceramic"; case MAT_PLASTIC -> "plastic";
            case MAT_FABRIC -> "fabric"; case MAT_NATURAL -> "natural"; default -> "other";
        };
    }

    public static String kindName(int kind) {
        return switch (kind) {
            case KIND_NONE -> "none"; case KIND_WALL -> "wall"; case KIND_DOOR -> "door"; case KIND_WINDOW -> "window";
            case KIND_FENCE -> "fence"; case KIND_BARRICADE -> "barricade"; case KIND_WINDOW_FRAME -> "window-frame";
            default -> "other";
        };
    }
}
