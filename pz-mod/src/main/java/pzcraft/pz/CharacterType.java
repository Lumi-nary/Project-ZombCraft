package pzcraft.pz;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/**
 * The kind of the PZ character, chosen before it was made (ChoiceScreen) and kept in its mod data ({@code PzCraftType}):
 * a Zomboid survivor ("pz": PZ's rules, first person only, never Steve) or Steve ("mc": Minecraft's inventory and rules,
 * nothing of PZ's in the pockets at the start). Both stay in Viewpoint's first person: it is switched on by itself once
 * the world is shown, and its key cannot switch it off. A character from before has no kind and works as it always did.
 */
public final class CharacterType {
    static final String KEY = "PzCraftType";

    private static volatile String pending;
    private static IsoPlayer stripFor;
    private static long stripUntil, nextViewTry;

    private CharacterType() {}

    /** The choice for the character about to be made (ChoiceScreen). */
    static void choose(String type) { pending = type; }

    /** Lua OnNewGame (PzCraft_Start.lua): the new character gets the chosen kind. */
    static void onNewCharacter(IsoPlayer p) {
        String type = pending;
        pending = null;
        if (p == null || type == null) return;
        p.getModData().rawset(KEY, type);
        Log.info("new character is a " + (ChoiceScreen.STEVE.equals(type) ? "Steve" : "Zomboid survivor"));
        if (ChoiceScreen.STEVE.equals(type)) {
            strip(p);
            // PZ's own OnNewGame handlers (SpawnItems) may give items after ours: take them away again on the first frames,
            // but only until Steve drives: from then on the inventory mirror fills the pockets, and taking its items away
            // would read as Steve throwing them away (removed in Minecraft too)
            stripFor = p;
            stripUntil = System.currentTimeMillis() + 3000;
        }
    }

    /** "pz", "mc", or null for a character made before the choice existed. Game thread. */
    static String of(IsoGameCharacter c) {
        if (c == null || !c.hasModData()) return null;
        Object v = c.getModData().rawget(KEY);
        return v instanceof String s ? s : null;
    }

    static boolean survivor(IsoGameCharacter c) { return ChoiceScreen.SURVIVOR.equals(of(c)); }

    /** Steve may take this character (everything but a Zomboid survivor). */
    static boolean allowsSteve(IsoGameCharacter c) { return !survivor(c); }

    /** The local player is of a kind, so Viewpoint's first person stays on and its view keys are off. */
    public static boolean locksView() {
        try {
            return of(IsoPlayer.getInstance()) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Game thread, every frame (FrameTick). */
    static void tick(IsoPlayer p) {
        if (p == null || !(zombie.GameWindow.states != null && zombie.GameWindow.states.current instanceof zombie.gameStates.IngameState)) return;
        if (stripFor != null) {
            if (stripFor == p && System.currentTimeMillis() < stripUntil && !SteveControl.drives(p)) strip(p);
            else stripFor = null;
        }
        String type = of(p);
        if (type == null || p.isDead()) return;
        long now = System.currentTimeMillis();
        if (!ViewpointBridge.viewEnabled() && ViewpointBridge.available() && now >= nextViewTry) {
            nextViewTry = now + 2000;
            if (ViewpointBridge.setupBlocks()) {
                if (!ViewpointBridge.setupShown()) ViewpointBridge.openSetup();
            } else {
                ViewpointBridge.enableView("for this " + (ChoiceScreen.STEVE.equals(type) ? "Steve" : "Zomboid survivor") + " (first person only)");
            }
        }
        if (ViewpointBridge.thirdPerson() && ChoiceScreen.SURVIVOR.equals(type)) ViewpointBridge.setThirdPerson(false);
    }

    /** Nothing of PZ's on a Steve: Minecraft's starter kit is his inventory (the mirror then fills the pockets from it). */
    private static void strip(IsoPlayer p) {
        if (p.getInventory().getItems().isEmpty() && p.getWornItems().isEmpty()) return;
        p.setPrimaryHandItem(null);
        p.setSecondaryHandItem(null);
        p.clearWornItems();
        p.getInventory().removeAllItems();
        Log.info("Steve starts with nothing of PZ's: PZ's starting items removed");
    }

}
