package pzcraft.mc;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;

/**
 * How a PZ melee weapon behaves in Minecraft: its script numbers become the attack damage, attack speed and tree-felling
 * speed of the stack, and its condition shows as the durability bar. Minecraft's own durability loss is deliberately not
 * enabled (no weapon component): PZ owns the item's condition, and wear is applied on the PZ side.
 *
 * <p>The attack attribute is a rough equivalent for Minecraft entities and the tooltip. PZ actors receive an identity-only
 * melee contact: PZ rolls the real item's damage and critical chance and calls native Hit with the character's skills.
 * Ranged weapons are not touched here.
 */
final class PzWeapons {
    /** Minecraft hit points -> PZ zombie health: must equal pz Gameplay.HIT_SCALE. */
    static final double HIT_SCALE = 0.3;
    private static final double MAX_DAMAGE = 60;

    private PzWeapons() {}

    /** The Minecraft attack damage of a melee weapon with these PZ numbers. */
    static double damage(JsonObject w) {
        double avg = (w.get("minDamage").getAsDouble() + w.get("maxDamage").getAsDouble()) / 2;
        return Math.max(1.0, Math.min(MAX_DAMAGE, avg * 2 / HIT_SCALE));
    }

    /** Minecraft's attack speed attribute value: heavy two-handed weapons swing slower. */
    static double speed(JsonObject w) { return w.get("twoHanded").getAsBoolean() ? 1.2 : 1.8; }

    /** Mining speed on PZ trees: an axe (tree damage 35) fells like an iron axe, anything below 2 is no tool at all. */
    static float treeSpeed(int treeDamage) { return 1f + treeDamage * 0.15f; }

    /**
     * Which Better Combat swing a PZ melee weapon gets (its preset: animation, arc, reach), chosen from PZ's own weapon
     * categories, hand use and swing animation. Null for things that are not swung (thrown items, bare hands without a script,
     * weapons PZ gives no category). Improvised weapons use the category they imitate.
     */
    static String preset(String id, JsonObject w) {
        if (w.has("maxDamage") && w.get("maxDamage").getAsDouble() <= 0) return null;
        var categories = new java.util.HashSet<String>();
        if (w.has("categories")) w.getAsJsonArray("categories").forEach(c -> categories.add(c.getAsString()));
        boolean two = w.get("twoHanded").getAsBoolean();
        boolean heavy = w.has("swingAnim") && !w.get("swingAnim").isJsonNull() && "Heavy".equals(w.get("swingAnim").getAsString());
        if (id.endsWith(".Katana") || id.contains(".Katana_")) return "katana";
        if (categories.contains("longblade")) return two ? "claymore" : "sword";
        if (categories.contains("axe")) return two ? "heavy_axe" : "axe";
        if (categories.contains("spear")) return "spear";
        if (categories.contains("smallblade")) return "dagger";
        if (categories.contains("blunt")) return heavy ? "hammer" : two ? "staff" : "mace";
        if (categories.contains("smallblunt")) return "mace";
        if (categories.contains("unarmed")) return "fist";
        return null;
    }

    private static final Identifier PRESET_COMPONENT = Identifier.parse("bettercombat:preset_id");
    private static final java.util.Map<String, Object> PRESET_VALUES = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean presetWarned;

    /** Better Combat is optional: without it (or with another codec shape) the weapon keeps working with plain vanilla swings. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setPreset(ItemStack s, String name) {
        DataComponentType type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(PRESET_COMPONENT);
        if (type == null || type.codec() == null) return;
        Object value = PRESET_VALUES.computeIfAbsent(name, n -> {
            var parsed = type.codec().parse(NbtOps.INSTANCE, StringTag.valueOf("bettercombat:" + n));
            return parsed.result().orElse(null);
        });
        if (value == null) {
            if (!presetWarned) { presetWarned = true; PzCraftClient.LOG.warn("Better Combat's preset component did not accept preset '{}'", name); }
            return;
        }
        s.set(type, value);
    }

    /** The Better Combat preset a stack carries, or "" (what the test state reports). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static String presetOf(ItemStack s) {
        DataComponentType type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(PRESET_COMPONENT);
        Object value = type == null ? null : s.get(type);
        return value == null ? "" : String.valueOf(value);
    }

    // ---- one swing, several targets ----

    private static final class Swing { long tick = Long.MIN_VALUE; int count; }
    private record Forward(long tick, String type, int itemId, int targetIndex) {}
    private static final java.util.Map<java.util.UUID, Swing> SWINGS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Forward> FORWARD = new java.util.concurrent.ConcurrentHashMap<>();

    /** The PZ melee numbers of what the player holds, or null (bare hands, other items, guns, throwables). */
    static JsonObject heldMelee(net.minecraft.world.entity.player.Player player) {
        String type = PzItems.typeOf(player.getMainHandItem());
        PzItemCatalog.Entry e = type == null ? null : PzItemCatalog.get(type);
        JsonObject w = e == null ? null : e.weapon();
        return w == null || w.get("ranged").getAsBoolean() || w.get("maxDamage").getAsDouble() <= 0 ? null : w;
    }

    /**
     * Called before a melee hit by the player lands on a PZ actor. PZ lets one swing hit at most the weapon's
     * {@code maxHitCount} targets (false from here cancels the hit) and the n-th target takes the first target's damage
     * divided by n (CombatManager: {@code damage / (split++ * 0.5)}); what Minecraft deals (Better Combat's per-attack
     * multipliers, its sweeping penalty, the attack cooldown) is replaced by PZ's native hit calculation.
     * Targets of one swing arrive in the same server tick, nearest first.
     */
    static boolean allowMeleeHit(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.entity.LivingEntity target) {
        JsonObject w = heldMelee(player);
        if (w == null) return true;
        long tick = player.level().getGameTime();
        Swing swing = SWINGS.computeIfAbsent(player.getUUID(), k -> new Swing());
        if (swing.tick != tick) { swing.tick = tick; swing.count = 0; }
        if (swing.count >= Math.max(1, w.get("maxHitCount").getAsInt())) return false;
        swing.count++;
        var state = PzItems.stateOf(player.getMainHandItem());
        FORWARD.put(target.getUUID(), new Forward(tick, PzItems.typeOf(player.getMainHandItem()),
                state.has("i") ? state.get("i").getAsInt() : -1, swing.count));
        return true;
    }

    /** Cheap server guard; PZ rechecks the real item's range and skill modifier before doing damage or wear. */
    static boolean inReach(net.minecraft.world.entity.player.Player player, net.minecraft.world.entity.LivingEntity target) {
        JsonObject w = heldMelee(player);
        if (w == null) return true;
        double range = pzcraft.protocol.MeleeReach.blocks(w.get("maxRange").getAsDouble()) + 0.4;   // eye to box edge vs centre to centre
        return player.position().subtract(target.position()).horizontalDistanceSqr() <= range * range;
    }

    /** One message owns both native damage and wear, so a rejected contact cannot wear a weapon independently. */
    static boolean forwardMelee(net.minecraft.world.entity.LivingEntity target, int actorId) {
        Forward f = FORWARD.remove(target.getUUID());
        if (f == null || f.tick != target.level().getGameTime()) return false;
        Session.send(pzcraft.protocol.Wire.MSG_MELEE_HIT, pzcraft.protocol.Wire.encodeMeleeHit(
                new pzcraft.protocol.Wire.MeleeHit(actorId, f.itemId, f.targetIndex, f.type)));
        return true;
    }

    static void apply(ItemStack s, PzItemCatalog.Entry e, JsonObject state, List<Component> lines) {
        JsonObject w = e.weapon();
        if (w == null || w.get("ranged").getAsBoolean()) return;
        String preset = preset(e.id(), w);
        if (preset != null) setPreset(s, preset);
        double damage = damage(w), speed = speed(w);
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, damage - 1, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, speed - 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                .build());
        int tree = w.get("treeDamage").getAsInt();
        if (tree >= 2) {
            s.set(DataComponents.TOOL, new Tool(List.of(Tool.Rule.minesAndDrops(BuiltInRegistries.BLOCK.getOrThrow(BlockTags.MINEABLE_WITH_AXE), treeSpeed(tree))), 1.0f, 0, true));
        }
        int max = w.get("conditionMax").getAsInt();
        if (max > 0) {
            int condition = state != null && state.has("c") ? state.get("c").getAsInt() : max;
            s.set(DataComponents.MAX_DAMAGE, max);
            s.set(DataComponents.DAMAGE, Math.max(0, Math.min(max - 1, max - condition)));
        }
        lines.add(Component.literal(String.format(Locale.ROOT, "Hit %.0f (PZ %.1f-%.1f)%s", damage, w.get("minDamage").getAsFloat(),
                w.get("maxDamage").getAsFloat(), tree >= 2 ? String.format(Locale.ROOT, "   Fells trees x%.1f", treeSpeed(tree)) : "")).withStyle(ChatFormatting.GRAY));
    }
}

