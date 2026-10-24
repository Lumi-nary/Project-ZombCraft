package pzcraft.pz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import zombie.core.properties.PropertyContainer;
import zombie.core.textures.Texture;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Writes {@code tiles.json} for Minecraft's creative tabs, with an icon per entry cut from PZ's own sprite (32 px):
 * <ul>
 *   <li>furniture: one entry per piece PZ can pick up (its group and name, one facing, the multi-tile anchor), with the
 *       item type PZ makes for it and PZ's own name, so a creative stack is the same item PZ's "Pick up" gives;</li>
 *   <li>walls and fences: one entry per style (its west-edge sprite; Minecraft places the north one when the wall runs
 *       the other way, PZ's sprites come in W/N pairs), placed by {@link TileBridge}.</li>
 * </ul>
 */
final class TileExporter {
    static final int ICON = 32;
    private static volatile int furniture, walls, fences, vehicles;

    private TileExporter() {}

    private record Entry(String kind, String sprite, String type, String name, String icon) {}

    /** Game thread, from {@link ItemExporter#export}. */
    static String export() throws IOException {
        furniture = walls = fences = vehicles = 0;
        List<Entry> entries = new ArrayList<>();
        Map<String, Texture> icons = new TreeMap<>();
        Map<String, IsoSprite> furnitureGroups = new TreeMap<>();
        List<IsoSprite> sprites = new ArrayList<>(IsoSpriteManager.instance.namedMap.values());
        sprites.sort(java.util.Comparator.comparing(IsoSprite::getName, java.util.Comparator.nullsLast(String::compareTo)));
        for (IsoSprite s : sprites) {
            String name = s.getName();
            PropertyContainer p = s.getProperties();
            if (name == null || p == null || s.tilesetName == null) continue;
            if (isFurniture(p)) {
                String group = s.tilesetName + "|" + p.get("GroupName") + "|" + p.get("CustomName");
                IsoSprite have = furnitureGroups.get(group);
                if (have == null || better(s, have)) furnitureGroups.put(group, s);
            } else if (isWall(s, p)) {
                entries.add(new Entry("wall", name, null, label(s, "wall"), "tile_" + name));
                walls++;
            } else if (isFence(s, p)) {
                entries.add(new Entry("fence", name, null, label(s, "fence"), "tile_" + name));
                fences++;
            }
        }
        for (IsoSprite s : furnitureGroups.values()) {
            InventoryItem item = InventoryItemFactory.CreateItem("Moveables." + s.getName());
            if (item == null) continue;
            entries.add(new Entry("furniture", s.getName(), item.getFullType(), item.getDisplayName(), "tile_" + s.getName()));
            furniture++;
        }
        // PZ's vehicles for the creative "Zomboid Vehicles" tab (spawned whole, full tank); wrecks are left out
        for (zombie.scripting.objects.VehicleScript v : zombie.scripting.ScriptManager.instance.getAllVehicleScripts()) {
            String name = v == null ? null : v.getName();
            if (name == null || v.getFullName() == null || name.contains("Burnt") || name.contains("Smashed")) continue;
            String label = zombie.core.Translator.getTextOrNull("IGUI_VehicleName" + name);
            entries.add(new Entry("vehicle", null, v.getFullName(), label != null ? label : name, null));
            vehicles++;
        }
        for (Entry e : entries) {
            if (e.sprite() == null || e.icon() == null) continue;
            Texture t = Texture.getSharedTexture(e.sprite());
            if (t != null) icons.put(e.icon(), t);
        }
        ItemExporter.exportIcons(icons, ICON);
        StringBuilder j = new StringBuilder(1 << 18).append("{\"version\":1,\"tiles\":[");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) j.append(',');
            j.append("{\"kind\":").append(str(e.kind())).append(",\"sprite\":").append(str(e.sprite()))
                    .append(",\"name\":").append(str(e.name())).append(",\"icon\":").append(e.icon() != null && icons.containsKey(e.icon()) ? str(e.icon()) : "null");
            if (e.type() != null) j.append(",\"type\":").append(str(e.type()));
            j.append('}');
        }
        Files.writeString(ItemExporter.DIR.resolve("tiles.json"), j.append("]}"), StandardCharsets.UTF_8);
        return furniture + " furniture, " + walls + " walls, " + fences + " fences, " + vehicles + " vehicles";
    }

    private static Map<String, String> groupIcons;

    /**
     * The icon of a furniture sprite's piece ("tile_" + its representative sprite, as exported), or null for no piece.
     * Built from PZ's sprites once, so it also works when the export ran in an earlier session.
     */
    static synchronized String iconFor(String sprite) {
        if (sprite == null) return null;
        if (groupIcons == null) {
            Map<String, IsoSprite> best = new java.util.HashMap<>();
            Map<String, String> groupOf = new java.util.HashMap<>();
            for (IsoSprite s : IsoSpriteManager.instance.namedMap.values()) {
                PropertyContainer p = s.getProperties();
                if (s.getName() == null || p == null || s.tilesetName == null || !isFurniture(p)) continue;
                String group = s.tilesetName + "|" + p.get("GroupName") + "|" + p.get("CustomName");
                groupOf.put(s.getName(), group);
                IsoSprite have = best.get(group);
                if (have == null || better(s, have) || !better(have, s) && s.getName().compareTo(have.getName()) < 0) best.put(group, s);
            }
            Map<String, String> icons = new java.util.HashMap<>();
            groupOf.forEach((name, group) -> icons.put(name, "tile_" + best.get(group).getName()));
            groupIcons = icons;
        }
        return groupIcons.get(sprite);
    }

    /** As {@link MineBridge}'s furniture: IsMoveAble, standing on the tile (not on a wall, a floor, a window). */
    static boolean isFurniture(PropertyContainer p) {
        if (!p.has("IsMoveAble")) return false;
        String moveType = p.get("MoveType");
        if ("WallObject".equals(moveType) || "Window".equals(moveType) || "WindowObject".equals(moveType) || "FloorTile".equals(moveType)) return false;
        for (IsoFlagType f : new IsoFlagType[] {IsoFlagType.attachedN, IsoFlagType.attachedW, IsoFlagType.attachedS, IsoFlagType.attachedE,
                IsoFlagType.attachedFloor, IsoFlagType.solidfloor, IsoFlagType.HoppableN, IsoFlagType.HoppableW,
                IsoFlagType.TallHoppableN, IsoFlagType.TallHoppableW, IsoFlagType.WallN, IsoFlagType.WallW, IsoFlagType.WallNW,
                IsoFlagType.WallNTrans, IsoFlagType.WallWTrans, IsoFlagType.windowN, IsoFlagType.windowW, IsoFlagType.WindowN, IsoFlagType.WindowW}) {
            if (p.has(f)) return false;
        }
        return true;
    }

    /** One facing per piece (PZ's own south one when there is one) and the anchor of a multi-tile piece. */
    private static boolean better(IsoSprite s, IsoSprite have) {
        int a = score(s), b = score(have);
        return a > b;
    }

    private static int score(IsoSprite s) {
        PropertyContainer p = s.getProperties();
        String grid = p.get("SpriteGridPos");
        int score = grid == null || "0,0".equals(grid) ? 4 : 0;
        if ("S".equals(p.get("Facing"))) score += 2;
        return score;
    }

    /** A straight wall on the west edge (its north twin is the next sprite): not a door frame, a window wall or a corner. */
    static boolean isWall(IsoSprite s, PropertyContainer p) {
        if (p.has("IsMoveAble") || !(p.has(IsoFlagType.WallW) || p.has(IsoFlagType.WallWTrans))) return false;
        if (p.has(IsoFlagType.DoorWallW) || p.has(IsoFlagType.doorW) || p.has(IsoFlagType.WindowW) || p.has(IsoFlagType.windowW)
                || p.has(IsoFlagType.WallNW) || p.has(IsoFlagType.WallSE)) return false;
        String set = s.tilesetName.toLowerCase(Locale.ROOT);
        return (set.startsWith("walls_") || set.startsWith("carpentry_") || set.startsWith("location_")) && !set.contains("roof") && !set.contains("damaged");
    }

    /** A fence on the west edge (low or tall), not a damaged one. */
    static boolean isFence(IsoSprite s, PropertyContainer p) {
        return !p.has("IsMoveAble") && (p.has(IsoFlagType.HoppableW) || p.has(IsoFlagType.TallHoppableW))
                && !p.has(IsoFlagType.HoppableN) && !p.has(IsoFlagType.TallHoppableN) // a corner post
                && !s.tilesetName.toLowerCase(Locale.ROOT).contains("damaged");
    }

    /** "walls_exterior_house_01_12" -> "Exterior House 01 Wall 12". */
    private static String label(IsoSprite s, String kind) {
        String set = s.tilesetName.replaceFirst("^(walls|fencing|fixtures|carpentry|location)_", "");
        StringBuilder b = new StringBuilder();
        for (String word : set.split("_")) {
            if (word.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        String name = s.getName();
        String index = name.substring(name.lastIndexOf('_') + 1);
        return b + " " + Character.toUpperCase(kind.charAt(0)) + kind.substring(1) + " " + index;
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("furniture", furniture);
        m.put("walls", walls);
        m.put("fences", fences);
        return m;
    }
}
