package pzcraft.mc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import pzcraft.protocol.Coords;
import pzcraft.protocol.Spawns;

/**
 * Creative spawners for PZ things ("Zomboid Vehicles" tab): one item per PZ vehicle script (PZ exports the list with its tiles,
 * {@link PzTiles}) that puts that car where Steve right-clicks, repaired, with a full tank and its key in his inventory, and
 * the Zomboid spawn egg, which puts a PZ zombie there. PZ makes them with its own code (SpawnBridge, PzCraft_Vehicles.lua).
 */
public final class PzSpawners {
    public static Item VEHICLE, ZOMBIE_EGG;
    private static int lastUsePress = Integer.MIN_VALUE;

    private PzSpawners() {}

    /** Main entrypoint, before registries freeze (both sides). */
    static void init() {
        // their models and names: assets/project-zombcraft/items/*.json (vanilla's minecart and zombie egg) and the lang file
        VEHICLE = register("pz_vehicle");
        ZOMBIE_EGG = register("pz_zombie_spawn_egg");
    }

    private static Item register(String id) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, id));
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(64)));
    }

    /** The spawn egg first, then every PZ vehicle by name. */
    static List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        if (ZOMBIE_EGG != null) out.add(new ItemStack(ZOMBIE_EGG));
        List<PzTiles.Tile> cars = new ArrayList<>(PzTiles.all());
        cars.removeIf(t -> !"vehicle".equals(t.kind()) || t.type() == null);
        cars.sort(Comparator.comparing(t -> t.name() == null ? t.type() : t.name()));
        for (PzTiles.Tile t : cars) out.add(vehicle(t.type(), t.name()));
        return out;
    }

    static ItemStack vehicle(String script, String name) {
        ItemStack s = new ItemStack(VEHICLE);
        CompoundTag tag = new CompoundTag();
        tag.putString("pz_vehicle", script);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.ITEM_NAME, Component.literal(name != null ? name : script));
        return s;
    }

    private static String scriptOf(ItemStack s) {
        CustomData data = s.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag().getString("pz_vehicle").orElse(null);
    }

    /** Minecraft.startUseItem: true when the click belonged to a spawner. */
    public static boolean use(Minecraft mc) {
        if (mc.player == null || !Session.pzConnected || !Session.released) return false;
        ItemStack held = mc.player.getMainHandItem();
        boolean car = held.is(VEHICLE), egg = held.is(ZOMBIE_EGG);
        if (!car && !egg) return false;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return true;
        var input = Session.readInput();
        if (input == null || input.usePresses == lastUsePress) return true; // one spawn per click
        lastUsePress = input.usePresses;
        Vec3 at = hit.getLocation();
        int level = (int) Math.floor(Coords.pzZ(at.y + 0.05));
        if (car) {
            String script = scriptOf(held);
            if (script == null) { mc.player.sendOverlayMessage(Component.literal("Pick a vehicle from the Zomboid Vehicles tab")); return true; }
            Session.send(Spawns.MSG_SPAWN_VEHICLE, new Spawns.Vehicle(script, (int) Math.floor(at.x), (int) Math.floor(at.z), level,
                    mc.player.getYRot()).encode());
        } else {
            Session.send(Spawns.MSG_SPAWN_ZOMBIE, new Spawns.Zombie(at.x, at.z, level).encode());
        }
        //#if MC_26_1
        //$ mc.player.swing(InteractionHand.MAIN_HAND, false);
        //#else
        mc.player.swing(InteractionHand.MAIN_HAND, held.getAttackAnimation(), false);
        //#endif
        return true;
    }
}
