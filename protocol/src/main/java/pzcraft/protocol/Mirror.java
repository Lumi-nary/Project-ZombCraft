package pzcraft.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What both games must agree on for the inventory mirror: how a carried item is named (its key) and how counts of keys
 * combine. Minecraft's inventory is the canonical one while Steve drives; PZ holds a hidden copy of it plus a ledger of the
 * state it last conformed to. Whatever PZ changed since (crafting, keys, picking things up in PZ-only play) is the difference
 * between its pockets and that ledger, and is added to what Minecraft holds:
 * <pre>target = max(0, minecraft + pzNow - ledger)</pre>
 * so a crash on either side rolls back consistently instead of duplicating or destroying anything.
 */
public final class Mirror {
    private Mirror() {}

    /**
     * The key of a PZ item: its type and the state that makes it a different item (condition, cooked, uses left, custom name)
     * or the same one (its PZ item id, for items that travel as the bytes PZ saves them as). Age is left out on purpose:
     * food ages by itself in both games and that is not a change to carry across.
     */
    public static String key(String type, Integer condition, boolean cooked, Double usesLeft, String customName, Integer itemId) {
        return type + "|" + (condition == null ? "" : String.valueOf(condition)) + "|" + (cooked ? "1" : "") + "|"
                + (usesLeft == null ? "" : String.format(Locale.ROOT, "%.3f", usesLeft)) + "|"
                + (customName == null ? "" : customName) + "|" + (itemId == null ? "" : String.valueOf(itemId));
    }

    /** The key of a Minecraft-only stack, from its text form (SNBT) at a count of one. */
    public static String mcKey(String stackAtCountOne) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(stackAtCountOne.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("mc|");
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isMc(String key) { return key.startsWith("mc|"); }

    /** now - base per key, only the keys that differ (positive: more than before). */
    public static Map<String, Integer> delta(Map<String, Integer> now, Map<String, Integer> base) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(now.keySet());
        keys.addAll(base.keySet());
        for (String k : keys) {
            int d = now.getOrDefault(k, 0) - base.getOrDefault(k, 0);
            if (d != 0) out.put(k, d);
        }
        return out;
    }

    /** base + delta per key, never below zero, zero counts dropped. */
    public static Map<String, Integer> apply(Map<String, Integer> base, Map<String, Integer> delta) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>(base.keySet());
        keys.addAll(delta.keySet());
        for (String k : keys) {
            int n = Math.max(0, base.getOrDefault(k, 0) + delta.getOrDefault(k, 0));
            if (n > 0) out.put(k, n);
        }
        return out;
    }

    /** What both games should hold: Minecraft's state plus what PZ changed since the ledger. */
    public static Map<String, Integer> target(Map<String, Integer> minecraft, Map<String, Integer> pzNow, Map<String, Integer> ledger) {
        return apply(minecraft, delta(pzNow, ledger));
    }
}
