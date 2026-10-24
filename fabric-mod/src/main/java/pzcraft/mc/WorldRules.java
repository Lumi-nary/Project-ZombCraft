package pzcraft.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
//#if MC_1_20
//$ import net.minecraft.world.level.GameRules;
//#else
import net.minecraft.world.level.gamerules.GameRules;
//#endif

/**
 * Minecraft's rules for a zombie world that PZ populates: real mob damage, but no Minecraft mobs of its own, and PZ's
 * weather. Set when the world's server starts: the integrated one in single player, the host's in multiplayer.
 */
public final class WorldRules {
    private WorldRules() {}

    public static void apply(MinecraftServer server) {
        server.setDifficulty(Difficulty.NORMAL, true);
        GameRules rules = server.getGameRules();
        //#if MC_1_20
        //$ // 1.20.1: one mob spawning rule for animals and monsters; phantoms come with insomnia
        //$ rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        //$ rules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, server);
        //$ rules.getRule(GameRules.RULE_DOINSOMNIA).set(false, server);
        //$ rules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, server);
        //$ rules.getRule(GameRules.RULE_DO_WARDEN_SPAWNING).set(false, server);
        //$ rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        //$ rules.getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(false, server);
        //$ rules.getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(false, server);
        //#else
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.SPAWN_MONSTERS, false, server);
        rules.set(GameRules.SPAWN_PATROLS, false, server);
        rules.set(GameRules.SPAWN_PHANTOMS, false, server);
        rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        rules.set(GameRules.SPAWN_WARDENS, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, server);
        rules.set(GameRules.SHOW_DEATH_MESSAGES, false, server);
        //#endif
        PzCraftMain.LOG.info("world rules: difficulty normal, no Minecraft mob spawning");
    }
}
