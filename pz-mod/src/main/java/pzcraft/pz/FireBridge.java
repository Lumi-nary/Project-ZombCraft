package pzcraft.pz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.Fire;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;

/**
 * Fire between the two worlds, PZ's half ({@link Fire}). Minecraft fire on a PZ zombie burns it the PZ way (PZ's burn
 * damage, its panic, its flames: Minecraft draws them from {@code FLAG_BURNING}); Minecraft fire at a PZ square starts
 * PZ's own fire there, which spreads through buildings, grass and trees by PZ's rules and its sandbox fire setting; PZ's
 * burning squares near Steve go back to Minecraft. A burning zombie Minecraft has a stand-in for gets Minecraft's flames
 * only: PZ's own flame sprite on it is taken off (it was a second fire on the same zombie, B22). Single player (PZ only
 * starts fires on the server). Game thread, except {@link #receive}.
 */
final class FireBridge {
    private static final int RANGE = 32, RESEND_MS = 5000;
    private static final ConcurrentLinkedQueue<Integer> actors = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Fire.Ignite> ignites = new ConcurrentLinkedQueue<>();
    private static List<Fire.Spot> sent = List.of();
    private static long lastScan, lastSent;
    private static volatile int actorsLit, squaresLit, refused, burning, flamesHidden;

    private FireBridge() {}

    /** Link thread. */
    static void receive(int type, byte[] payload) {
        if (type == Fire.MSG_IGNITE_ACTOR) {
            if (actors.size() < 64) actors.add(ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else if (ignites.size() < 64) {
            ignites.add(Fire.Ignite.decode(payload));
        }
    }

    /** Game thread, once per frame. */
    static void tick(IsoPlayer player) {
        if (player == null || !LinkService.connected() || Multiplayer.client()) { actors.clear(); ignites.clear(); return; }
        Integer id;
        while ((id = actors.poll()) != null) {
            IsoGameCharacter c = Gameplay.actor(id);
            if (c == null || c.isDead() || c.isOnFire() || c.getVehicle() != null) continue;
            c.SetOnFire();
            actorsLit++;
        }
        for (IsoGameCharacter c : Gameplay.exportedActors()) {
            // PZ's SetOnFire attaches its flame as the character's only attached sprite (StopBurning clears the whole list)
            if (c.isOnFire() && c.attachedAnimSprite != null && !c.attachedAnimSprite.isEmpty()) {
                c.attachedAnimSprite.clear();
                flamesHidden++;
            }
        }
        IsoCell cell = IsoWorld.instance.getCell();
        Fire.Ignite ignite;
        while ((ignite = ignites.poll()) != null) {
            Fire.Spot s = ignite.spot();
            IsoGridSquare sq = cell == null ? null : cell.getGridSquare(s.x(), s.y(), s.level());
            if (sq == null || Math.hypot(s.x() + .5 - player.getX(), s.y() + .5 - player.getY()) > RANGE
                    || !IsoFire.CanAddFire(sq, ignite.anywhere())) {
                refused++;
                continue;
            }
            IsoFireManager.StartFire(cell, sq, ignite.anywhere(), ignite.energy());
            squaresLit++;
        }
        long now = System.currentTimeMillis();
        if (now - lastScan >= 500) {
            lastScan = now;
            sendFires(player, now);
        }
    }

    private static void sendFires(IsoPlayer player, long now) {
        List<Fire.Spot> out = new ArrayList<>();
        for (IsoFire f : IsoFireManager.FireStack) {
            IsoGridSquare sq = f == null || f.smoke ? null : f.getSquare();
            if (sq == null || Math.abs(sq.getZ() - player.getZ()) > 3
                    || Math.hypot(sq.getX() + .5 - player.getX(), sq.getY() + .5 - player.getY()) > RANGE) continue;
            out.add(new Fire.Spot(sq.getX(), sq.getY(), sq.getZ()));
        }
        out.sort(Comparator.comparingInt(Fire.Spot::level).thenComparingInt(Fire.Spot::y).thenComparingInt(Fire.Spot::x));
        if (out.size() > Fire.MAX_FIRES) out = out.subList(0, Fire.MAX_FIRES);
        burning = out.size();
        if (out.equals(sent) && now - lastSent < RESEND_MS) return;
        if (LinkService.send(Fire.MSG_FIRES, Fire.encodeSpots(out))) {
            sent = List.copyOf(out);
            lastSent = now;
        }
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actorsLit", actorsLit);
        m.put("squaresLit", squaresLit);
        m.put("refused", refused);
        m.put("burningNearSteve", burning);
        m.put("pzFlamesHidden", flamesHidden);
        return m;
    }
}
