package pzcraft.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import pzcraft.protocol.Wire;

/**
 * 1.20.1 version of the shared TimeBridge. PZ advances ordinary time; explicit vanilla commands and sleep change both
 * clocks with acknowledgement. 1.20.1 has no world clocks: its one clock is the overworld's day time (all levels share
 * it, as /time set does), its rate is always 1 and it stops with the doDaylightCycle rule.
 */
public final class TimeBridge {
    public static volatile pzcraft.protocol.WorldClock.State state;
    private static pzcraft.protocol.WorldClock.Change pending;
    private static long sequence = System.currentTimeMillis(), lastRetry;
    private static boolean syncing;
    private static int ticks;
    private static long lastControl;
    private TimeBridge() {}
    public static boolean syncing() { return syncing; }
    /** A command or sleep moved the day time (the 1.20.1 ClockMixin, once ported, reports it here). */
    public static void changed(MinecraftServer server, long before, long after) {
        if (!Session.pzConnected || syncing || before == after) return;
        // Multiple commands before PZ acknowledges must retain the original calendar reference.
        pending = new pzcraft.protocol.WorldClock.Change(++sequence, pending == null ? before : pending.before(), after);
        Session.send(Wire.MSG_CLOCK_CHANGE, pending.encode());
        lastRetry = System.currentTimeMillis();
    }
    static void tick(MinecraftServer server) {
        long nowMillis=System.currentTimeMillis();
        if(Session.pzConnected&&nowMillis-lastControl>=500){
            lastControl=nowMillis;
            boolean paused=!server.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
            Session.send(Wire.MSG_CLOCK_CONTROL,Wire.encodeFloats(1f,paused?1:0));
        }
        var s = state;
        if (pending != null) {
            if (s != null && s.acknowledged() >= pending.sequence()) pending = null;
            else {
                if (System.currentTimeMillis() - lastRetry >= 500) {
                    lastRetry = System.currentTimeMillis();
                    Session.send(Wire.MSG_CLOCK_CHANGE, pending.encode());
                }
                return;
            }
        }
        if (s == null || ++ticks % 20 != 0) return;
        long now = server.overworld().getDayTime();
        if (Math.abs(now - s.ticks()) > 150) {
            syncing = true;
            try { for (ServerLevel level : server.getAllLevels()) level.setDayTime(s.ticks()); }
            finally { syncing = false; }
        }
    }
}
