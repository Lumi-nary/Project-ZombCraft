package pzcraft.pz;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.Moodles.Moodle;

/** PZ survival pauses while Steve drives. The pre-Steve state is saved with the character. */
public final class SteveVitals {
    private static final String KEY = "PzCraftSteveVitals";
    private static IsoPlayer owner;
    private static Field moodleOwner, moodleLevel, uiOwner;
    private static boolean reflectionFailed;
    private static volatile long hiddenFrames;
    private SteveVitals() {}

    static void update(IsoPlayer player, boolean drives) {
        if (owner != null && owner != player) { restore(owner); owner = null; }
        if (player == null) return;
        if (drives) {
            if (!(player.getModData().rawget(KEY) instanceof KahluaTable)) snapshot(player);
            owner = player;
            neutral(player);
        } else {
            restore(player); // also recovers a persisted snapshot after a crash
            owner = null;
        }
    }

    private static void snapshot(IsoPlayer p) {
        KahluaTable saved = LuaManager.platform.newTable();
        for (CharacterStat s : CharacterStat.REGISTRY.values()) saved.rawset(s.getId(), (double) p.getStats().get(s));
        var b = p.getBodyDamage();
        saved.rawset("hasCold", b.isHasACold());
        saved.rawset("coldStrength", (double) b.getColdStrength());
        saved.rawset("catchCold", (double) b.getCatchACold());
        saved.rawset("sneezeTimer", (double) b.getTimeToSneezeOrCough());
        saved.rawset("coldDamageStage", (double) b.getColdDamageStage());
        p.getModData().rawset(KEY, saved);
        Log.info("PZ survival saved; Minecraft now owns Steve's hunger and saturation");
    }

    private static void restore(IsoPlayer p) {
        if (!(p.getModData().rawget(KEY) instanceof KahluaTable saved)) return;
        for (CharacterStat s : CharacterStat.REGISTRY.values())
            if (saved.rawget(s.getId()) instanceof Number n) p.getStats().set(s, n.floatValue());
        var b = p.getBodyDamage();
        b.setHasACold(Boolean.TRUE.equals(saved.rawget("hasCold")));
        if (saved.rawget("coldStrength") instanceof Number n) b.setColdStrength(n.floatValue());
        if (saved.rawget("catchCold") instanceof Number n) b.setCatchACold(n.floatValue());
        if (saved.rawget("sneezeTimer") instanceof Number n) b.setTimeToSneezeOrCough(n.floatValue());
        if (saved.rawget("coldDamageStage") instanceof Number n) b.setColdDamageStage(n.floatValue());
        p.getModData().rawset(KEY, null);
        p.getMoodles().Update();
        p.getMoodles().setMoodlesStateChanged(true);
        Log.info("PZ survival restored after leaving Steve mode");
    }

    /** Before PZ's body update and every bridge frame; physical injuries still go through HealthLink. */
    public static void neutral(IsoGameCharacter p) {
        if (!SteveControl.ownsSurvival(p) || !(p.getModData().rawget(KEY) instanceof KahluaTable)) return;
        for (CharacterStat s : CharacterStat.REGISTRY.values()) p.getStats().reset(s);
        var b = p.getBodyDamage();
        b.setHasACold(false); b.setColdStrength(0); b.setCatchACold(0); b.setTimeToSneezeOrCough(-1);
        b.setColdDamageStage(0);
    }

    public static boolean owns(zombie.characters.Stats stats) {
        IsoPlayer p = IsoPlayer.getInstance();
        return SteveControl.ownsSurvival(p) && p.getStats() == stats && p.getModData().rawget(KEY) instanceof KahluaTable;
    }

    /** Freeze the internal thermal nodes too, so leaving Steve mode resumes their prior state. */
    public static boolean owns(zombie.characters.BodyDamage.Thermoregulator thermo) {
        IsoPlayer p = IsoPlayer.getInstance();
        return SteveControl.ownsSurvival(p) && p.getBodyDamage().getThermoregulator() == thermo
                && p.getModData().rawget(KEY) instanceof KahluaTable;
    }

    private static synchronized boolean resolve() {
        if (reflectionFailed) return false;
        if (moodleOwner != null) return true;
        try {
            moodleOwner = Moodle.class.getDeclaredField("isoGameCharacter"); moodleOwner.setAccessible(true);
            moodleLevel = Moodle.class.getDeclaredField("moodleLevel"); moodleLevel.setAccessible(true);
            uiOwner = zombie.ui.MoodlesUI.class.getDeclaredField("isoGameCharacter"); uiOwner.setAccessible(true);
            return true;
        } catch (ReflectiveOperationException e) { reflectionFailed = true; Log.error("moodle fields changed", e); return false; }
    }

    public static boolean suppressMoodle(Moodle moodle) {
        if (!resolve()) return false;
        try {
            IsoGameCharacter p = (IsoGameCharacter) moodleOwner.get(moodle);
            if (!SteveControl.ownsSurvival(p)) return false;
            moodleLevel.setInt(moodle, 0);
            moodle.setChevron(0, true, zombie.core.Color.white);
            return true;
        } catch (IllegalAccessException e) { return false; }
    }

    public static boolean hideUI(Object ui, boolean rendering) {
        if (!SteveControl.ownsSurvival(IsoPlayer.getInstance()) || !resolve()) return false;
        try {
            Object p = uiOwner.get(ui);
            if (p != null && p != IsoPlayer.getInstance()) return false;
            if (rendering) hiddenFrames++;
            return true;
        } catch (IllegalAccessException e) { return false; }
    }

    static Map<String, Object> stats() {
        var result = new LinkedHashMap<String, Object>();
        IsoPlayer p = IsoPlayer.getInstance();
        result.put("active", SteveControl.ownsSurvival(p)); result.put("hiddenFrames", hiddenFrames);
        result.put("reflectionFailed", reflectionFailed);
        result.put("saved", p != null && p.getModData().rawget(KEY) instanceof KahluaTable);
        if (p != null) {
            var stats = new LinkedHashMap<String, Object>();
            for (CharacterStat s : CharacterStat.ORDERED_STATS) stats.put(s.getId(), p.getStats().get(s));
            result.put("stats", stats);
            result.put("hasCold", p.getBodyDamage().isHasACold());
            result.put("coldDamageStage", p.getBodyDamage().getColdDamageStage());
        }
        return result;
    }
}

