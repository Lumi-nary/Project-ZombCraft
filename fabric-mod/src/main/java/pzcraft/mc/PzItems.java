package pzcraft.mc;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * Every Project Zomboid item type is one Minecraft item, {@code pzcraft:pz_item}, told apart by its stack's data: the PZ type
 * (in {@code custom_data}), its name, its icon (the item model PZ's icon pack provides) and a short tooltip. How a PZ item
 * behaves comes from its category: food eats, weapons hit, clothing wears.
 */
public final class PzItems {
    public static Item PZ_ITEM;
    private static Item M9_ITEM;
    private static boolean m9Visuals;

    private PzItems() {}

    /** Main entrypoint, with the registries still open. */
    public static void init() {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz_item"));
        PZ_ITEM = Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key).stacksTo(64)));
        // Keep the saved item identity even if the optional private visuals are unavailable later.
        m9Visuals = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("geckolib") && M9Assets.available();
        ResourceKey<Item> gunKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz_m9"));
        Item.Properties gunProperties = new Item.Properties().setId(gunKey).stacksTo(1);
        M9_ITEM = Registry.register(BuiltInRegistries.ITEM, gunKey, m9Visuals ? new M9Item(gunProperties) : new Item(gunProperties));
        // stacks made before a weapon had its model carry the sprite model: point them at the new item definition
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 40 != 0) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                var inventory = player.getInventory();
                for (int i = 0; i < inventory.getContainerSize(); i++) PzWeaponModels.retarget(inventory.getItem(i));
                PzWeaponModels.retarget(player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND));
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
                Commands.literal("pzoffhand").then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PzItemCatalog.ids().stream()
                                .filter(id -> id.toLowerCase(Locale.ROOT).contains(builder.getRemainingLowerCase())).limit(200), builder))
                        .executes(ctx -> giveOffhand(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "item"), 1, ctx.getSource()))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> giveOffhand(
                                ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "item"),
                                IntegerArgumentType.getInteger(ctx, "count"), ctx.getSource()))))));
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
                Commands.literal("pzgive").then(Commands.argument("item", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PzItemCatalog.ids().stream()
                                .filter(id -> id.toLowerCase(Locale.ROOT).contains(builder.getRemainingLowerCase())).limit(200), builder))
                        .executes(ctx -> give(ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "item"), 1, ctx.getSource()))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64)).executes(ctx -> give(
                                ctx.getSource().getPlayerOrException(), StringArgumentType.getString(ctx, "item"),
                                IntegerArgumentType.getInteger(ctx, "count"), ctx.getSource()))))));
    }

    /** {@code /pzoffhand <item> [count]}: the stack goes into Steve's off-hand (which must be empty), for testing and play. */
    private static int giveOffhand(ServerPlayer player, String id, int count, net.minecraft.commands.CommandSourceStack source) {
        if (!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).isEmpty()) {
            source.sendFailure(Component.literal("The off-hand is not empty"));
            return 0;
        }
        ItemStack stack = stack(id, count);
        if (stack.isEmpty()) { source.sendFailure(Component.literal("Unknown PZ item " + id)); return 0; }
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, stack);
        source.sendSuccess(() -> Component.literal("Off-hand: " + count + " x " + id), true);
        return count;
    }

    private static int give(ServerPlayer player, String id, int count, net.minecraft.commands.CommandSourceStack source) {
        ItemStack stack = stack(id, count);
        if (stack.isEmpty()) {
            source.sendFailure(Component.literal("Unknown PZ item " + id + (PzItemCatalog.size() == 0 ? " (PZ has not exported its items yet)" : "")));
            return 0;
        }
        String name = stack.getHoverName().getString();
        if (!player.getInventory().add(stack) && !stack.isEmpty()) player.spawnAtLocation(player.level() instanceof net.minecraft.server.level.ServerLevel sl ? sl : null, stack);
        source.sendSuccess(() -> Component.literal("Gave " + count + " x " + name + " (" + id + ")"), true);
        return count;
    }

    /** The PZ type a stack stands for, or null for any other item. */
    public static String typeOf(ItemStack stack) {
        if (stack.isEmpty() || (stack.getItem() != PZ_ITEM && stack.getItem() != M9_ITEM)) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag pz = data.copyTag().getCompoundOrEmpty("pz");
        return pz.getString("type").orElse(null);
    }

    /** A stack of a PZ item type, or {@link ItemStack#EMPTY} when PZ has no such type (or has not exported its items yet). */
    public static ItemStack stack(String fullType, int count) { return stack(fullType, count, null, -1); }

    /**
     * A stack with PZ's state for the item: {@code c} condition, {@code a} age in days, {@code k} cooked, {@code u} uses left,
     * {@code nm} a custom name, and {@code d}, the PZ world day it left PZ's world (so it can age while carried).
     */
    public static ItemStack stack(String fullType, int count, com.google.gson.JsonObject state, double worldDays) {
        PzItemCatalog.Entry e = PzItemCatalog.get(fullType);
        if (e == null || PZ_ITEM == null) return ItemStack.EMPTY;
        boolean m9 = M9_ITEM != null && "Base.Pistol".equals(fullType);
        ItemStack s = new ItemStack(m9 ? M9_ITEM : PZ_ITEM, Math.max(1, count));
        CompoundTag pz = new CompoundTag();
        pz.putString("type", e.id());
        String name = e.name();
        if (state != null) {
            if (state.has("c")) pz.putInt("c", state.get("c").getAsInt());
            if (state.has("a")) {
                pz.putDouble("a", state.get("a").getAsDouble());
                if (worldDays >= 0) pz.putDouble("d", worldDays); // when it left PZ, so it keeps ageing while carried
            }
            if (state.has("k")) pz.putInt("k", 1);
            if (state.has("u")) pz.putDouble("u", state.get("u").getAsDouble());
            if (state.has("nm")) { pz.putString("nm", state.get("nm").getAsString()); name = state.get("nm").getAsString(); }
            if (state.has("b")) pz.putString("b", state.get("b").getAsString()); // PZ saved the whole item: only PZ reads this
            if (state.has("i")) pz.putInt("i", state.get("i").getAsInt());
            if (state.has("g")) pz.putString("g", state.get("g").toString());
        }
        CompoundTag root = new CompoundTag();
        root.put("pz", pz);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        s.set(DataComponents.ITEM_NAME, Component.literal(name));
        if (e.icon() != null) s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz/" + PzItemCatalog.iconKey(e.icon())));
        Identifier weaponModel = m9 ? null : PzWeaponModels.modelOf(e.id());
        if (weaponModel != null) s.set(DataComponents.ITEM_MODEL, weaponModel);   // PZ's own mesh in the hand, its sprite elsewhere
        if (m9 && m9Visuals) {
            s.set(DataComponents.ITEM_MODEL, Identifier.parse("pzcraft:m9"));
            M9Item.identify(s, state);
        }
        s.set(DataComponents.MAX_STACK_SIZE, state != null && state.has("b") ? 1 : maxStack(e));
        List<Component> lines = lore(e);
        if (state != null) {
            if (state.has("c")) lines.add(Component.literal("Condition " + state.get("c").getAsInt()).withStyle(ChatFormatting.GRAY));
            if (state.has("a") && state.get("a").getAsDouble() >= 0.05) lines.add(Component.literal(String.format(Locale.ROOT, "Age %.1f days", state.get("a").getAsDouble())).withStyle(ChatFormatting.GRAY));
            if (state.has("k")) lines.add(Component.literal("Cooked").withStyle(ChatFormatting.GRAY));
            if (state.has("u")) lines.add(Component.literal(String.format(Locale.ROOT, "%.0f%% left", state.get("u").getAsDouble() * 100)).withStyle(ChatFormatting.GRAY));
        }
        if (e.food() != null) food(s, e, state, lines);
        if (e.clothing() != null) wear(s, e, lines);
        if (e.weapon() != null) PzWeapons.apply(s, e, state, lines);
        if (state != null && state.has("g")) {
            var gun = state.getAsJsonObject("g");
            if (!gun.has("chamber")) {   // a magazine
                lines.add(Component.literal("Rounds " + gun.get("ammo").getAsInt() + " / " + gun.get("capacity").getAsInt()).withStyle(ChatFormatting.GRAY));
            } else lines.add(Component.literal("Ammo " + gun.get("ammo").getAsInt()
                    + (gun.get("chamber").getAsBoolean() ? "+1" : "") + " / " + gun.get("capacity").getAsInt()
                    + (gun.get("jammed").getAsBoolean() ? "  Jammed" : "")
                    + (!gun.get("clip").getAsBoolean() ? "  No magazine" : "")).withStyle(ChatFormatting.GRAY));
        }
        s.set(DataComponents.LORE, new ItemLore(lines));
        return s;
    }

    static void tickVisuals(net.minecraft.client.Minecraft mc) { if (m9Visuals) M9Item.tick(mc); }
    static void gunClip(net.minecraft.client.Minecraft mc, String clip, double pace) { if (m9Visuals) M9Item.clip(mc, clip, pace); }

    // ---- what an item does in Minecraft, from what PZ says it is ----

    /** PZ hunger points (a whole bar is 100) per Minecraft food point (a whole bar is 20): an apple, 16 in PZ, is 4. */
    private static final double HUNGER_PER_FOOD_POINT = 4.5;

    /**
     * Food fills Steve's Minecraft hunger. Stale food fills less, rotten food hardly at all and makes him sick, a drink
     * fills a little and can be had on a full stomach. What a tin or a packet leaves behind comes back as that item.
     */
    private static void food(ItemStack s, PzItemCatalog.Entry e, com.google.gson.JsonObject state, List<Component> lines) {
        com.google.gson.JsonObject f = e.food();
        double hunger = -f.get("hunger").getAsDouble(), thirst = -f.get("thirst").getAsDouble();
        double age = state != null && state.has("a") ? state.get("a").getAsDouble() : 0;
        int fresh = f.get("daysFresh").getAsInt(), rotten = f.get("daysRotten").getAsInt();
        boolean perishable = rotten < 1_000_000;
        boolean isRotten = perishable && age >= rotten, isStale = perishable && fresh < 1_000_000 && age >= fresh;
        boolean drink = hunger < 1 && thirst > 0;
        double fill = drink ? thirst / 2 : hunger;
        int nutrition = fill <= 0 ? 0 : (int) Math.max(1, Math.min(20, Math.round(fill / HUNGER_PER_FOOD_POINT)));
        if (isStale) nutrition = Math.max(nutrition > 0 ? 1 : 0, nutrition / 2);
        if (isRotten) nutrition = Math.min(nutrition, 1);
        boolean cooked = state != null && state.has("k");
        s.set(DataComponents.FOOD, new FoodProperties(nutrition, (float) (nutrition * (cooked ? 1.0 : 0.6)), drink));
        Consumable.Builder use = Consumable.builder().consumeSeconds(drink ? 1.2f : 1.6f)
                .animation(drink ? ItemUseAnimation.DRINK : ItemUseAnimation.EAT)
                .sound(drink ? SoundEvents.GENERIC_DRINK : SoundEvents.GENERIC_EAT);
        if (isRotten) {
            use.onConsume(new ApplyStatusEffectsConsumeEffect(List.of(new MobEffectInstance(MobEffects.HUNGER, 600),
                    new MobEffectInstance(MobEffects.NAUSEA, 200)), 1.0f));
        }
        s.set(DataComponents.CONSUMABLE, use.build());
        lines.add(Component.literal(isRotten ? "Rotten" : isStale ? "Stale" : drink ? "Drink" : "Food")
                .withStyle(isRotten ? ChatFormatting.DARK_RED : isStale ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
        if (e.replaceOnUse() != null) {
            String full = e.replaceOnUse().contains(".") ? e.replaceOnUse() : e.id().substring(0, e.id().indexOf('.') + 1) + e.replaceOnUse();
            if (!full.equals(e.id())) {
                ItemStack left = stack(full, 1);
                if (!left.isEmpty()) s.set(DataComponents.USE_REMAINDER, new UseRemainder(ItemStackTemplate.fromNonEmptyStack(left)));
            }
        }
    }

    /** Armor points for a fully protective piece, by where it is worn (the same as diamond armor). */
    private static int armorMax(EquipmentSlot slot) {
        return switch (slot) { case HEAD -> 3; case CHEST -> 8; case LEGS -> 6; default -> 3; };
    }

    /** The Minecraft slot a PZ body location is worn in, or null for the ones with no equivalent (gloves, jewellery, wounds). */
    public static EquipmentSlot slotFor(String bodyLocation) {
        if (bodyLocation == null) return null;
        String l = bodyLocation.substring(bodyLocation.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return switch (l) {
            case "hat", "fullhat", "jackethat", "mask", "maskfull", "maskeyes", "eyes" -> EquipmentSlot.HEAD;
            case "tshirt", "shirt", "shortsleeveshirt", "tanktop", "sweater", "jersey", "jacket", "jacket_bulky", "jacket_down",
                 "jacketsuit", "boilersuit", "dress", "longdress", "torsoextra", "torsoextravest", "torsoextravestbullet",
                 "cuirass", "torso1legs1", "underweartop", "shoulderpadleft", "shoulderpadright", "gorget", "scarf" -> EquipmentSlot.CHEST;
            case "pants", "pants_skinny", "shortpants", "shortsshort", "skirt", "longskirt", "underwearbottom", "legs1",
                 "thigh_left", "thigh_right", "knee_left", "knee_right", "calf_left", "calf_right" -> EquipmentSlot.LEGS;
            case "shoes", "socks" -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    /** Clothes are worn like armor: the slot from where PZ wears them, the points from how well they stop a bite or a bullet. */
    private static void wear(ItemStack s, PzItemCatalog.Entry e, List<Component> lines) {
        EquipmentSlot slot = slotFor(e.bodyLocation());
        if (slot == null) return;
        com.google.gson.JsonObject c = e.clothing();
        double bite = c.get("bite").getAsDouble(), scratch = c.get("scratch").getAsDouble(), bullet = c.get("bullet").getAsDouble();
        int armor = (int) Math.round(Math.max(bite, Math.max(scratch, bullet)) / 100.0 * armorMax(slot));
        s.set(DataComponents.EQUIPPABLE, Equippable.builder(slot).setDamageOnHurt(false).build());
        if (armor > 0) {
            Identifier id = Identifier.fromNamespaceAndPath(PzBlocks.NAMESPACE, "pz_clothing");
            s.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                    .add(Attributes.ARMOR, new AttributeModifier(id, armor, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.bySlot(slot)).build());
        }
        lines.add(Component.literal(String.format(Locale.ROOT, "Worn on the %s%s", slot.getName(),
                bite + scratch + bullet > 0 ? String.format(Locale.ROOT, "   Bite %.0f  Scratch %.0f  Bullet %.0f", bite, scratch, bullet) : "")).withStyle(ChatFormatting.GRAY));
    }

    /** A short text for logs and the playtester: {@code 3 pz:Base.Apple a=0.40}, or the plain stack text for any other item. */
    public static String describe(ItemStack stack) {
        String type = typeOf(stack);
        if (type == null) return stack.toString();
        StringBuilder b = new StringBuilder().append(stack.getCount()).append(" pz:").append(type);
        com.google.gson.JsonObject s = stateOf(stack);
        if (s.has("c")) b.append(" c=").append(s.get("c").getAsInt());
        if (s.has("a")) b.append(String.format(Locale.ROOT, " a=%.2f", s.get("a").getAsDouble()));
        if (s.has("k")) b.append(" cooked");
        if (s.has("u")) b.append(String.format(Locale.ROOT, " u=%.3f", s.get("u").getAsDouble()));
        if (s.has("nm")) b.append(" nm=").append(s.get("nm").getAsString());
        if (s.has("b")) b.append(" whole");
        if (s.has("g")) {   // rounds in a magazine or gun, as the mirror last delivered them
            var g = s.getAsJsonObject("g");
            b.append(" r=").append(g.get("ammo").getAsInt()).append(g.has("chamber") && g.get("chamber").getAsBoolean() ? "+1" : "");
        }
        return b.toString();
    }

    /** The PZ state stored in a stack ({@code c, a, k, u, nm, d}), as JSON; empty for a stack with no state. */
    public static com.google.gson.JsonObject stateOf(ItemStack stack) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return o;
        CompoundTag pz = data.copyTag().getCompoundOrEmpty("pz");
        pz.getInt("c").ifPresent(v -> o.addProperty("c", v));
        pz.getDouble("a").ifPresent(v -> o.addProperty("a", v));
        pz.getInt("k").ifPresent(v -> o.addProperty("k", 1));
        pz.getDouble("u").ifPresent(v -> o.addProperty("u", v));
        pz.getString("nm").ifPresent(v -> o.addProperty("nm", v));
        pz.getDouble("d").ifPresent(v -> o.addProperty("d", v));
        pz.getString("b").ifPresent(v -> o.addProperty("b", v));
        pz.getInt("i").ifPresent(v -> o.addProperty("i", v));
        pz.getString("g").ifPresent(v -> o.add("g", com.google.gson.JsonParser.parseString(v)));
        return o;
    }

    /** Must agree with PZ's grouping: type plus the rounded state in a fixed order. */
    public static String stateKey(String type, com.google.gson.JsonObject s) {
        return type + "|" + (s.has("c") ? String.valueOf(s.get("c").getAsInt()) : "") + "|"
                + (s.has("a") ? String.format(Locale.ROOT, "%.2f", s.get("a").getAsDouble()) : "") + "|" + (s.has("k") ? "1" : "") + "|"
                + (s.has("u") ? String.format(Locale.ROOT, "%.3f", s.get("u").getAsDouble()) : "") + "|" + (s.has("nm") ? s.get("nm").getAsString() : "")
                + "|" + (s.has("i") ? String.valueOf(s.get("i").getAsInt()) : "");
    }

    private static int maxStack(PzItemCatalog.Entry e) {
        String t = e.type().toLowerCase(Locale.ROOT);
        if (pzcraft.protocol.ItemRules.single(t)) return 1;
        if (e.tags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains("magazine"))) return 1;   // each carries its own rounds
        return t.equals("food") ? 16 : 64;
    }

    private static List<Component> lore(PzItemCatalog.Entry e) {
        List<Component> lines = new ArrayList<>();
        if (!e.category().isEmpty()) lines.add(Component.literal(e.category()).withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal(String.format(Locale.ROOT, "Weight %.2f", e.weight())).withStyle(ChatFormatting.GRAY));
        if (e.weapon() != null) {
            var w = e.weapon();
            lines.add(Component.literal(String.format(Locale.ROOT, "Damage %.1f - %.1f   Range %.1f", w.get("minDamage").getAsFloat(),
                    w.get("maxDamage").getAsFloat(), w.get("maxRange").getAsFloat())).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }
}

