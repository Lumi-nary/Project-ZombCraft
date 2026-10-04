package pzcraft.pz;

import java.time.LocalDate;
import pzcraft.protocol.Wire;
import pzcraft.protocol.WorldClock;
import zombie.GameTime;
import zombie.characters.IsoPlayer;

/** Applies explicit MC time changes only on PZ's owning thread. Offset is persisted with the PZ player. */
public final class ClockBridge {
    private static volatile WorldClock.Change pending;
    private static long acknowledged, lastSent;
    private static double offset;
    private static IsoPlayer previous;
    private static volatile float rate=1;
    private static volatile long controlAt;
    private static final ThreadLocal<Boolean> advancing=ThreadLocal.withInitial(()->false);
    static void control(float speed,boolean paused){
        if(!Float.isFinite(speed)||speed<=0||speed>1000)return;
        rate=paused?0:speed;controlAt=System.nanoTime();
    }
    public static void beginAdvance(){advancing.set(true);}
    public static void endAdvance(){advancing.set(false);}
    public static float minutes(float original){
        if(!advancing.get()||!LinkService.connected()||System.nanoTime()-controlAt>3_000_000_000L)return original;
        return rate==0?Float.POSITIVE_INFINITY:original/rate;
    }
    static void receive(WorldClock.Change change) { pending = change; }
    static void tick(IsoPlayer player) {
        if (player == null) return;
        var time = GameTime.getInstance();
        if (player != previous) {
            previous = player;
            Object saved = player.getModData().rawget("PzCraftClockOffset");
            offset = saved instanceof Number n ? n.doubleValue() : 0;
        }
        WorldClock.Change change = pending;
        if (change != null && change.sequence() > acknowledged) {
            LocalDate date = LocalDate.of(time.getYear(), time.getMonth() + 1, time.getDay() + 1)
                    .plusDays(WorldClock.calendarDays(change.before(), change.after()));
            time.setYear(date.getYear()); time.setMonth(date.getMonthValue() - 1); time.setDay(date.getDayOfMonth() - 1);
            time.setNightsSurvived(Math.max(0, time.getNightsSurvived() + (int) WorldClock.nights(change.before(), change.after())));
            float hour = WorldClock.hour(change.after());
            time.setTimeOfDay(hour); time.setLastTimeOfDay(hour); time.lastLastTimeOfDay = hour;
            time.updateCalendar(date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth() - 1, (int) hour, (int) (hour % 1 * 60));
            if (WorldClock.calendarDays(change.before(), change.after()) != 0) zombie.Lua.LuaEventManager.triggerEvent("EveryDays");
            offset = change.after() - canonical(time);
            player.getModData().rawset("PzCraftClockOffset", offset);
            acknowledged = change.sequence();
            lastSent = 0;
            Log.info("Minecraft changed PZ clock to " + date + " " + hour + " (ticks=" + change.after() + ")");
        }
        long now = System.currentTimeMillis();
        if (now - lastSent >= 100) {
            lastSent = now;
            LinkService.send(Wire.MSG_CLOCK_STATE, new WorldClock.State(acknowledged, Math.round(canonical(time) + offset)).encode());
        }
    }
    private static double canonical(GameTime time) { return (time.getWorldAgeHours() + 1) * 1000; }
}

