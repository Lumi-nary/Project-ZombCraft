package pzcraft.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Project Zomboid in Minecraft's creative menu: tabs after vanilla's (Fabric puts them on the next page) with every PZ
 * item PZ exported, sorted the way PZ sorts them (its item type and DisplayCategory), plus PZ's furniture, walls and
 * fences ({@link PzTiles}). The stacks are the same ones {@code /pzgive} makes, so they mirror into PZ like any other.
 * The tabs fill themselves again when PZ exports again (a new game version, or the first run).
 */
public final class PzCreativeTabs {
    enum Tab {
        WALLS("walls", "Zomboid Walls", "Base.Hammer", Items.BRICKS),
        FENCES("fences", "Zomboid Fences", "Base.Nails", Items.OAK_FENCE),
        FURNITURE("furniture", "Zomboid Furniture", null, Items.CHEST),
        GUNS("guns", "Zomboid Guns", "Base.Pistol", Items.CROSSBOW),
        MELEE("melee", "Zomboid Melee", "Base.Axe", Items.IRON_AXE),
        ATTACHMENTS("attachments", "Zomboid Attachments", "Base.x4Scope", Items.SPYGLASS),
        AMMO("ammo", "Zomboid Ammo & Magazines", "Base.9mmClip", Items.ARROW),
        FOOD("food", "Zomboid Food", "Base.TinnedBeans", Items.BREAD),
        CLOTHING("clothing", "Zomboid Clothing", "Base.Hat_BaseballCap", Items.LEATHER_HELMET),
        BAGS("bags", "Zomboid Bags & Containers", "Base.Bag_Schoolbag", Items.BUNDLE),
        MEDICAL("medical", "Zomboid Medical", "Base.Bandage", Items.GLISTERING_MELON_SLICE),
        TOOLS("tools", "Zomboid Tools & Materials", "Base.Screwdriver", Items.IRON_PICKAXE),
        LITERATURE("literature", "Zomboid Literature", "Base.Book", Items.BOOK),
        ITEMS("items", "Zomboid Items", "Base.Lighter", Items.PAPER),
        VEHICLES("vehicles", "Zomboid Vehicles", null, Items.MINECART);

        final String id, title, iconType;
        final Item fallback;

        Tab(String id, String title, String iconType, Item fallback) {
            this.id = id; this.title = title; this.iconType = iconType; this.fallback = fallback;
        }
    }

    private static final Set<String> MEDICAL = Set.of("firstaid", "bandage", "wound");
    private static final Set<String> TOOLS = Set.of("tool", "material", "electronics", "gardening", "cooking", "camping", "fishing",
            "trapping", "vehiclemaintenance", "household", "paint", "explosives", "lightsource", "communications", "security",
            "recipe resource", "reciperesource", "watercontainer", "sports", "cartography");
    private static final Set<String> NEVER = Set.of("hidden", "zeddmg", "furniture");

    private PzCreativeTabs() {}

    /** Main entrypoint, before registries freeze (both sides: the tabs are registry entries). */
    static void init() {
        for (Tab tab : Tab.values()) {
            Supplier<ItemStack> icon = () -> icon(tab);
            CreativeModeTab built = FabricCreativeModeTab.builder()
                    .title(Component.literal(tab.title))
                    .icon(icon)
                    .displayItems((parameters, output) -> {
                        Set<String> seen = new HashSet<>();
                        for (ItemStack s : contents(tab)) {
                            String key = String.valueOf(s.getItem()) + s.getComponentsPatch();
                            if (s.isEmpty() || !seen.add(key)) continue; // creative tabs refuse the same stack twice
                            try { output.accept(s); } catch (RuntimeException e) { PzCraftMain.LOG.debug("creative tab {}: {}", tab.id, e.toString()); }
                        }
                    })
                    .build();
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz_" + tab.id), built);
        }
    }

    private static ItemStack icon(Tab tab) {
        if (tab == Tab.FURNITURE || tab == Tab.WALLS || tab == Tab.FENCES) {
            String kind = tab == Tab.FURNITURE ? "furniture" : tab == Tab.WALLS ? "wall" : "fence";
            for (PzTiles.Tile t : PzTiles.all()) {
                if (!kind.equals(t.kind())) continue;
                ItemStack s = PzTiles.stack(t);
                if (!s.isEmpty()) return s;
            }
        } else if (tab.iconType != null) {
            ItemStack s = PzItems.stack(tab.iconType, 1);
            if (!s.isEmpty()) return s;
        }
        return new ItemStack(tab.fallback);
    }

    /** The tab a PZ item belongs in, by its item type and DisplayCategory; null for none (hidden, furniture, zombie damage). */
    static Tab tabOf(PzItemCatalog.Entry e) {
        if (e.hidden()) return null;
        String category = e.category() == null ? "" : e.category().toLowerCase(Locale.ROOT);
        String type = e.type() == null ? "" : e.type().toLowerCase(Locale.ROOT);
        if (NEVER.contains(category) || type.equals("moveable")) return null;
        if (type.equals("weapon")) return e.weapon() != null && e.weapon().has("ranged") && e.weapon().get("ranged").getAsBoolean() ? Tab.GUNS : Tab.MELEE;
        if (type.equals("weaponpart") || category.equals("weaponpart")) return Tab.ATTACHMENTS;
        if (category.equals("ammo")) return Tab.AMMO;
        if (type.equals("food")) return Tab.FOOD;
        if (type.contains("clothing")) return Tab.CLOTHING;
        if (type.equals("container")) return Tab.BAGS;
        if (MEDICAL.contains(category)) return Tab.MEDICAL;
        if (type.equals("literature") || type.equals("map")) return Tab.LITERATURE;
        if (TOOLS.contains(category)) return Tab.TOOLS;
        return Tab.ITEMS;
    }

    static List<ItemStack> contents(Tab tab) {
        List<ItemStack> out = new ArrayList<>();
        switch (tab) {
            case VEHICLES -> out.addAll(PzSpawners.contents());
            case WALLS, FENCES, FURNITURE -> {
                String kind = tab == Tab.FURNITURE ? "furniture" : tab == Tab.WALLS ? "wall" : "fence";
                List<PzTiles.Tile> list = new ArrayList<>(PzTiles.all());
                list.removeIf(t -> !kind.equals(t.kind()));
                list.sort(Comparator.comparing(t -> t.name() == null ? "" : t.name()));
                for (PzTiles.Tile t : list) out.add(PzTiles.stack(t));
            }
            default -> {
                List<PzItemCatalog.Entry> entries = new ArrayList<>();
                for (String id : PzItemCatalog.ids()) {
                    PzItemCatalog.Entry e = PzItemCatalog.get(id);
                    if (e != null && tabOf(e) == tab) entries.add(e);
                }
                entries.sort(Comparator.comparing(PzItemCatalog.Entry::name));
                for (PzItemCatalog.Entry e : entries) out.add(PzItems.stack(e.id(), 1));
            }
        }
        return out;
    }

    private static long builtFor = Long.MIN_VALUE;

    /**
     * Client tick: when PZ has exported again since the tabs were filled, have Minecraft fill them again the next time the
     * creative menu opens (it only refills by itself when the world or the player's permissions change).
     */
    static void refreshIfExported() {
        long stamp = PzTiles.stamp() * 31 + PzItemCatalog.size();
        if (stamp == builtFor) return;
        builtFor = stamp;
        pzcraft.mc.mixin.CreativeTabsAccessor.pzcraft$setCachedParameters(null);
    }
}
