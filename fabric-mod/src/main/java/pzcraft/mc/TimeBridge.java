package pzcraft.mc;

import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.WorldClock;
import pzcraft.protocol.Wire;

/** PZ advances ordinary time; explicit vanilla commands and sleep change both clocks with acknowledgement. */
public final class TimeBridge {
    public static volatile pzcraft.protocol.WorldClock.State state;
    private static pzcraft.protocol.WorldClock.Change pending;
    private static long sequence = System.currentTimeMillis(), lastRetry;
    private static boolean syncing;
    private static int ticks;
    private static long lastControl;
    private TimeBridge() {}
    public static boolean syncing() { return syncing; }
    public static void changed(MinecraftServer server, Holder<WorldClock> clock, long before, long after) {
        if (!Session.pzConnected || syncing || before == after
                || !server.overworld().dimensionTypeRegistration().value().defaultClock().filter(clock::equals).isPresent()) return;
        // Multiple commands before PZ acknowledges must retain the original calendar reference.
        pending = new pzcraft.protocol.WorldClock.Change(++sequence, pending == null ? before : pending.before(), after);
        Session.send(Wire.MSG_CLOCK_CHANGE, pending.encode());
        lastRetry = System.currentTimeMillis();
    }
    static void tick(MinecraftServer server) {
        long nowMillis=System.currentTimeMillis();
        if(Session.pzConnected&&nowMillis-lastControl>=500){
            lastControl=nowMillis;
            server.overworld().dimensionTypeRegistration().value().defaultClock().ifPresent(clock->{
                var c=server.clockManager().getInstance(clock);
                boolean paused=c.isPaused()||!server.getGlobalGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME);
                Session.send(Wire.MSG_CLOCK_CONTROL,Wire.encodeFloats(c.rate(),paused?1:0));
            });
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
        server.overworld().dimensionTypeRegistration().value().defaultClock().ifPresent(clock -> {
            long now = server.clockManager().getInstance(clock).totalTicks();
            if (Math.abs(now - s.ticks()) > 150) {
                syncing = true;
                try { server.clockManager().setTotalTicks(clock, s.ticks()); }
                finally { syncing = false; }
            }
        });
    }
}

