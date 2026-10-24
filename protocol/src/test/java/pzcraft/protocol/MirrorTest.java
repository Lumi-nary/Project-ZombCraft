package pzcraft.protocol;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MirrorTest {
    private static String apple() { return Mirror.key("Base.Apple", null, false, null, null, null); }
    private static String hammer() { return Mirror.key("Base.Hammer", 3, false, null, "My Hammer", null); }
    private static String bag() { return Mirror.key("Base.Bag_Schoolbag", null, false, null, null, 1234); }

    @Test void keyIgnoresAgeAndDistinguishesWhatMatters() {
        // age is not part of the key at all, so a ripening apple stays the same key
        assertEquals("Base.Apple|||||", apple());
        assertEquals("Base.Hammer|3||||", Mirror.key("Base.Hammer", 3, false, null, null, null));
        assertNotEquals(apple(), Mirror.key("Base.Apple", null, true, null, null, null));              // cooked
        assertNotEquals(hammer(), Mirror.key("Base.Hammer", 3, false, null, null, null));             // custom name
        assertNotEquals(bag(), Mirror.key("Base.Bag_Schoolbag", null, false, null, null, 1235));      // another bag
        assertEquals("Base.Lighter|||0.406||", Mirror.key("Base.Lighter", null, false, 0.4062, null, null));
    }

    @Test void minecraftOnlyKeysAreStableAndDistinct() {
        assertEquals(Mirror.mcKey("{id:\"minecraft:tnt\",count:1}"), Mirror.mcKey("{id:\"minecraft:tnt\",count:1}"));
        assertNotEquals(Mirror.mcKey("{id:\"minecraft:tnt\",count:1}"), Mirror.mcKey("{id:\"minecraft:gravel\",count:1}"));
        assertTrue(Mirror.isMc(Mirror.mcKey("x")));
        assertFalse(Mirror.isMc(apple()));
    }

    @Test void firstSyncIsAUnionNothingIsDestroyed() {
        var mc = Map.of("mc|aa", 59, apple(), 1);       // Steve's inventory
        var pz = Map.of(bag(), 1);                      // PZ's pockets: a bag
        var target = Mirror.target(mc, pz, Map.of());   // empty ledger
        assertEquals(Map.of("mc|aa", 59, apple(), 1, bag(), 1), target);
    }

    @Test void minecraftWinsWhenPzChangedNothing() {
        var ledger = Map.of(apple(), 2, bag(), 1);
        var mc = Map.of(apple(), 1, bag(), 1);          // Steve ate one
        assertEquals(mc, Mirror.target(mc, ledger, ledger));
    }

    @Test void pzChangesAreCarriedOverAndAddedToWhatMinecraftHolds() {
        var ledger = Map.of(apple(), 2);
        var pzNow = Map.of(apple(), 1, hammer(), 1);    // PZ ate/consumed one apple and crafted a hammer
        var mc = Map.of(apple(), 2, "mc|bb", 5);        // meanwhile Steve picked up stone
        assertEquals(Map.of(apple(), 1, hammer(), 1, "mc|bb", 5), Mirror.target(mc, pzNow, ledger));
    }

    @Test void removalsClampAndNeverGoNegative() {
        var ledger = Map.of(apple(), 3);
        var pzNow = Map.<String, Integer>of();          // PZ used all three
        var mc = Map.of(apple(), 1);                    // but Steve had already eaten two
        assertEquals(Map.of(), Mirror.target(mc, pzNow, ledger));
    }

    @Test void convergesAfterTheTargetIsApplied() {
        var ledger = Map.of(apple(), 2);
        var pzNow = Map.of(apple(), 2, hammer(), 1);
        var mc = Map.of(apple(), 2);
        var target = Mirror.target(mc, pzNow, ledger);
        // both sides now hold the target and the ledger is the target: a second round changes nothing
        assertEquals(target, Mirror.target(target, target, target));
        assertEquals(Map.of(), Mirror.delta(target, target));
    }

    @Test void deltaListsOnlyDifferencesWithSigns() {
        assertEquals(Map.of(apple(), -1, hammer(), 1), Mirror.delta(Map.of(apple(), 1, hammer(), 1), Map.of(apple(), 2)));
    }
}
