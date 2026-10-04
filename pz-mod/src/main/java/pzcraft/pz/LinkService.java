package pzcraft.pz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import pzcraft.protocol.PlayerState;
import pzcraft.protocol.Ring;
import pzcraft.protocol.SharedLink;

/** Owns the PZ half of the shared link: heartbeat, ping/pong latency probe, and the latest Minecraft pose. */
public final class LinkService {
    private static volatile SharedLink link;
    private static volatile boolean started;
    private static boolean gameplayEvents = true;
    /** The tx ring is single-producer: pings (link thread) and game-thread messages take turns under this lock. */
    private static final Object TX = new Object();

    private static final PlayerState latest = new PlayerState();
    private static volatile boolean haveState;
    private static volatile double rttMs = -1;
    private static volatile boolean minecraftReady;
    private static volatile boolean placementRequested;
    /** Bumped whenever Minecraft (re)connects or asks to be placed: it has lost everything we sent, so resend it. */
    private static volatile int epoch;

    private LinkService() {}

    public static synchronized void start() {
        if (started) return;
        started = true;
        try {
            link = SharedLink.open(SharedLink.Role.PZ);
        } catch (Exception e) {
            Log.error("could not open shared link", e);
            return;
        }
        Log.info("shared link open at " + SharedLink.defaultPath());
        Thread t = new Thread(LinkService::loop, "pzcraft-link");
        t.setDaemon(true);
        t.start();
    }

    /** The standalone physics harness has no PZ classes on its classpath and must discard gameplay events. */
    static synchronized void startHarness() {
        if (started) throw new IllegalStateException("link already started");
        gameplayEvents = false;
        start();
    }

    private static void loop() {
        SharedLink l = link;
        boolean wasAlive = false;
        long lastBeat = 0, lastPing = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (now - lastBeat >= 100) {
                l.heartbeat();
                lastBeat = now;
                boolean alive = l.peerAlive();
                if (alive != wasAlive) {
                    Log.info(alive ? "Minecraft connected (pid " + l.peerPidOrZero() + ")" : "Minecraft disconnected");
                    wasAlive = alive;
                    if (!alive) { haveState = false; rttMs = -1; minecraftReady = false; }
                    else epoch++;
                }
                if (alive && now - lastPing >= 1000) {
                    synchronized (TX) { l.sendPing(); }
                    lastPing = now;
                }
            }
            Ring.Message m;
            while ((m = l.rx().poll()) != null) {
                if (!gameplayEvents && m.type() >= pzcraft.protocol.Wire.MSG_ACTORS) continue;
                if (m.type() == SharedLink.MSG_PONG) {
                    long sent = ByteBuffer.wrap(m.payload()).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    rttMs = (System.nanoTime() - sent) / 1e6;
                } else if (m.type() == pzcraft.protocol.Wire.MSG_GUI_STATE) {
                    GuiInput.onState(m.payload());
                } else if (m.type() == pzcraft.protocol.Wire.MSG_INTERACT) {
                    var b=m.buffer();InteractionBridge.receive(b.getInt(),b.remaining()>=4?b.getInt():0);
                } else if (m.type() == pzcraft.protocol.Wire.MSG_LIGHTS) {
                    LightBridge.receive(pzcraft.protocol.WorldObjects.lights(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_PASSENGERS) {
                    PassengerBridge.receive(pzcraft.protocol.Passengers.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_ACTOR_GROUND) {
                    ActorTerrain.receive(pzcraft.protocol.ActorGround.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_WEATHER_COMMAND) {
                    WeatherBridge.receive(pzcraft.protocol.Weather.Command.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_CLOCK_CHANGE) {
                    ClockBridge.receive(pzcraft.protocol.WorldClock.Change.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_CLOCK_CONTROL) {
                    float[] values=pzcraft.protocol.Wire.decodeFloats(m.payload());
                    if(values.length==2)ClockBridge.control(values[0],values[1]!=0);
                } else if (m.type() == pzcraft.protocol.Wire.MSG_RESPAWN_TARGET) {
                    PuppetDriver.receiveRespawn(pzcraft.protocol.RespawnTarget.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_HIT_ACTOR) {
                    Gameplay.onHit(pzcraft.protocol.Wire.decodeHit(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_WEAPON_USED) {
                    Gameplay.onWeaponUsed(new String(m.payload(), java.nio.charset.StandardCharsets.UTF_8));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_MELEE_HIT) {
                    Gameplay.onMeleeHit(pzcraft.protocol.Wire.decodeMeleeHit(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_GUN_ACTION) {
                    GunBridge.receive(pzcraft.protocol.GunAction.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_BLOCK) {
                    BlockBridge.onBlock(pzcraft.protocol.Wire.decodeBlock(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_BLOCKS_RESET) {
                    BlockBridge.onReset();
                } else if (m.type() == pzcraft.protocol.Wire.MSG_CONTAINER_OPEN) {
                    ContainerBridge.receiveOpen(m.buffer().getInt());
                } else if (m.type() == pzcraft.protocol.Wire.MSG_CONTAINER_COMMIT) {
                    ContainerBridge.receiveCommit(new String(m.payload(), java.nio.charset.StandardCharsets.UTF_8));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_MIRROR_STATE) {
                    MirrorBridge.receiveState(new String(m.payload(), java.nio.charset.StandardCharsets.UTF_8));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_GROUND_EDIT) {
                    GroundBridge.receive(pzcraft.protocol.Wire.decodeGroundEdit(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_PZ_EXPLOSION) {
                    ExplosionBridge.receive(pzcraft.protocol.PzExplosion.decode(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_TREE_FELLED) {
                    TreeBridge.onFelled(pzcraft.protocol.Wire.decodeTile(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_ATE) {
                    Gameplay.onAte(pzcraft.protocol.Wire.decodeTime(m.payload()));
                } else if (m.type() == pzcraft.protocol.Wire.MSG_STEVE_HEALTH) {
                    float[] v = pzcraft.protocol.Wire.decodeFloats(m.payload());
                    HealthLink.onSteveHealth(v[0], v[1]);
                } else if (m.type() == pzcraft.protocol.Wire.MSG_STEVE_DIED) {
                    HealthLink.onSteveDied();
                } else if (m.type() == SharedLink.MSG_NEED_PLACEMENT) {
                    placementRequested = true;
                    minecraftReady = false;
                    epoch++;
                } else if (m.type() == SharedLink.MSG_READY) {
                    minecraftReady = true;
                    Log.info("Minecraft world ready, Steve is released");
                }
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Called once per rendered frame on PZ's render thread. Returns the freshest Minecraft pose, or null. */
    public static PlayerState pullState() {
        SharedLink l = link;
        if (l == null || !l.peerAlive()) return null;
        if (!l.readPlayerState(latest)) return null;
        haveState = true;
        return latest;
    }

    /**
     * Queues a message for Minecraft; false if the ring is full. Safe from any thread.
     */
    public static boolean send(int type, byte[] payload) {
        SharedLink l = link;
        if (l == null) return false;
        synchronized (TX) { return l.tx().write(type, payload); }
    }

    public static void sendInput(pzcraft.protocol.InputState s) {
        SharedLink l = link;
        if (l != null) l.writeInputState(s);
    }

    public static boolean minecraftReady() { return minecraftReady; }

    /** Changes each time Minecraft has (re)started or respawned and so needs the world data again. */
    public static int epoch() { return epoch; }

    /** Call after sending MSG_TELEPORT: Steve's old pose is stale until Minecraft announces MSG_READY again. */
    public static void expectReady() { minecraftReady = false; }
    static void respawnStreaming() { minecraftReady = false; epoch++; }

    /** True once after Minecraft asks to be placed (new Steve); the game thread should answer with MSG_TELEPORT. */
    public static boolean takePlacementRequest() {
        boolean r = placementRequested;
        placementRequested = false;
        return r;
    }

    /** Like {@link #pullState} but into a caller-owned object, so different threads never share a scratch copy. */
    public static boolean pullInto(PlayerState out) {
        SharedLink l = link;
        return l != null && l.peerAlive() && l.readPlayerState(out);
    }

    public static long peerPid() { SharedLink l = link; return l == null ? 0 : l.peerPidOrZero(); }

    public static boolean connected() { return link != null && link.peerAlive(); }
    static void nativeWorldRendered(boolean active) { SharedLink l = link; if (l != null) l.nativeWorldRendered(active); }
    public static double rttMs() { return rttMs; }
    public static boolean haveState() { return haveState; }
}

