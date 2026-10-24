package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Exposer.LuaClass;
import pzcraft.protocol.McStatus;
import zombie.characters.IsoPlayer;
import zombie.characters.SurvivorDesc;

/**
 * What PzCraft_Start.lua calls (Lua: {@code PzCraft.Start.*}): it joins the Zomboid-survivor-or-Steve choice to PZ's own
 * menu flow. Public on purpose: ZombieBuddy exposes this class to Lua.
 */
@LuaClass(name = "PzCraft.Start")
public final class StartLua {
    private StartLua() {}

    /** The choice is asked before every new character in single player (not on a server: PzCraft stays off there). */
    public static boolean wanted() { return !Multiplayer.active(); }

    /** Shows the choice screen instead of PZ's character creation. */
    public static void ask() { ChoiceScreen.INSTANCE.ask(); }

    /** "pz", "mc", "back", or nil while the player has not chosen. Front-end tick. */
    public static String take() { return ChoiceScreen.INSTANCE.take(); }

    /**
     * Steve has no character creation: the character PZ makes is unemployed, without traits, named after the player's
     * Minecraft name (Steve if Minecraft does not say).
     */
    public static void prepareSteve(SurvivorDesc desc) {
        if (desc == null) return;
        var unemployed = zombie.scripting.objects.CharacterProfession.UNEMPLOYED;
        var definition = zombie.characters.professions.CharacterProfessionDefinition.getCharacterProfessionDefinition(unemployed);
        if (definition != null) desc.setProfessionSkills(definition);
        desc.setCharacterProfession(unemployed);
        McStatus s = LinkService.minecraftStatus();
        desc.setForename(s == null || s.user().isBlank() ? "Steve" : s.user());
        desc.setSurname("");
    }

    /** Lua OnNewGame: the new character gets the kind chosen for it. */
    public static void newCharacter(IsoPlayer player) { CharacterType.onNewCharacter(player); }
}
