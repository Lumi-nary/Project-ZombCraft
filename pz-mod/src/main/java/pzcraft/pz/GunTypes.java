package pzcraft.pz;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import zombie.inventory.InventoryItem;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/** Which PZ item types are firearms and magazines, from PZ's own scripts (cached; the scripts do not change while the game runs). */
final class GunTypes {
    private static final Map<String, Boolean> FIREARMS = new ConcurrentHashMap<>();
    private static volatile Set<String> magazines;

    private GunTypes() {}

    /** A real firearm: ranged and aimed (every PZ pistol, revolver, rifle and shotgun, and the toy cap guns). */
    static boolean firearm(String fullType) {
        if (fullType == null) return false;
        return FIREARMS.computeIfAbsent(fullType, type -> {
            Item script = ScriptManager.instance.getItem(type);
            return script != null && script.isRanged() && script.isAimedFirearm;
        });
    }

    static boolean firearm(InventoryItem item) {
        return item instanceof HandWeapon w && w.isRanged() && w.isAimedFirearm();
    }

    /** A magazine some firearm takes ({@code Base.9mmClip}, {@code Base.556Clip}...). */
    static boolean magazine(String fullType) {
        if (fullType == null) return false;
        Set<String> set = magazines;
        if (set == null) {
            set = new HashSet<>();
            for (Item script : new java.util.ArrayList<>(ScriptManager.instance.getAllItems())) {
                if (script == null || !script.isRanged() || !script.isAimedFirearm) continue;
                InventoryItem sample = InventoryItemFactory.CreateItem(script.getFullName());
                if (sample instanceof HandWeapon w && w.getMagazineType() != null) set.add(w.getMagazineType());
            }
            magazines = set;
        }
        return set.contains(fullType);
    }
}
