package pzcraft.pz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import pzcraft.protocol.Coords;
import pzcraft.protocol.PlayerState;
import pzcraft.protocol.SharedLink;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;

/**
 * Minecraft is authoritative for where Steve is; the PZ player is a puppet that follows. Viewpoint's camera is built
 * from the PZ player's position, so moving the puppet moves the camera.
 */
final class PuppetDriver {
    private static final PlayerState scratch = new PlayerState();
    private static boolean placedOnce;
    private static IsoPlayer lastPlayer;
    private static boolean viewWasEnabled;
    private static long lastPlacementAttempt;
    private static volatile pzcraft.protocol.RespawnTarget respawn;
    private static long appliedRespawn;
    static void receiveRespawn(pzcraft.protocol.RespawnTarget target) { respawn = target; }
    static void finishRespawn() {
        var target = respawn;
        if (target == null || appliedRespawn != target.sequence() || !WorldExporter.readyAt(target.x(), target.y(), target.z())) return;
        LinkService.send(pzcraft.protocol.Wire.MSG_RESPAWN_READY,
                ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(target.sequence()).array());
        if (LinkService.minecraftReady()) respawn = null;
    }

    private PuppetDriver() {}

    /** Ask for Steve to be re-placed at the PZ player's position on the next frame (after PZ teleports its player). */
    static void requestReplace() {
        placedOnce = false;
        lastPlacementAttempt = 0;
        LinkService.expectReady(); // until Steve is re-placed, do not overwrite the PZ player with his old pose
    }

    /** The PZ player the camera follows, or null if there is none yet. */
    static IsoPlayer localPlayer() {
        IsoPlayer p = IsoPlayer.getInstance();
        return p != null && !p.isDead() ? p : null;
    }

    /** Tells Minecraft where to put Steve: at the PZ player. Called on connect and whenever Minecraft asks. */
    static void maintainPlacement() {
        var target = respawn;
        if (target != null) {
            var player = localPlayer();
            if (player != null && appliedRespawn != target.sequence()) {
                appliedRespawn = target.sequence();
                LinkService.respawnStreaming();
                player.teleportTo((float) target.x(), (float) target.z(), (int) Math.floor(Coords.pzZ(target.y())));
                player.setForceX((float) target.x()); player.setForceY((float) target.z()); player.setZ((float) Coords.pzZ(target.y()));
                placedOnce = true;
                Log.info("PZ following Minecraft respawn " + target);
            }
            return;
        }
        boolean requested = LinkService.takePlacementRequest();
        if (requested) placedOnce = false;
        IsoPlayer current = localPlayer();
        if (current != null && current != lastPlayer) {
            // A different PZ character (first load, or a new one after the old one died): Steve starts over there.
            if (lastPlayer != null) {
                Log.info("PZ player changed; re-placing Steve");
                requestReplace();
            }
            lastPlayer = current;
        }
        // Only play as Steve in Viewpoint's 3D view; in the normal isometric view PZ keeps control of its player.
        boolean view = ViewpointBridge.viewEnabled();
        if (view && !viewWasEnabled) placedOnce = false; // switching 3D on: start Steve where the PZ player is
        viewWasEnabled = view;
        if (!view || placedOnce || !LinkService.connected()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlacementAttempt < 500) return;
        lastPlacementAttempt = now;

        IsoPlayer p = localPlayer();
        if (p == null) return;
        // A saved elytra pose has no PZ square at its height. The loaded column, including its ground, is sufficient.
        if (p.getCurrentSquare() == null && !WorldExporter.readyAt(p.getX(),Coords.mcY(p.getZ()),p.getY())) return;
        // A touch above the floor so he settles onto it rather than starting inside the slab.
        double y = Coords.mcY(p.getZ()) + 0.02;
        ByteBuffer b = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        b.putDouble(Coords.mcX(p.getX())).putDouble(y).putDouble(Coords.mcZ(p.getY()));
        b.putFloat(Coords.pzRadToMcYawDeg(ViewpointBridge.yaw())).putFloat(Coords.pzPitchRadToMcDeg(ViewpointBridge.pitch()));
        if (LinkService.send(SharedLink.MSG_TELEPORT, b.array())) {
            placedOnce = true;
            LinkService.expectReady();
            Log.info(String.format("placing Steve at PZ (%.2f, %.2f, %.3f)", p.getX(), p.getY(), p.getZ()));
        }
    }

    /**
     * Runs at the end of IsoPlayer.postupdate, i.e. after PZ has done its own movement and collision for the frame,
     * so what the renderer sees is Minecraft's pose, not PZ's.
     */
    static void apply(IsoPlayer player) {
        if (player != IsoPlayer.getInstance() || !LinkService.minecraftReady() || !ViewpointBridge.viewEnabled()) return;
        if (!LinkService.pullInto(scratch)) return;
        PlayerState s = scratch;

        float x = (float) Coords.pzX(s.x);
        float y = (float) Coords.pzY(s.z);
        float z = (float) Coords.pzZ(s.y);
        player.setForceX(x);   // also resets the next/last positions PZ interpolates with
        player.setForceY(y);
        player.setZ(z);        // also resets last z
        IsoGridSquare sq = player.findCurrentGridSquare();
        if (sq != null) {
            player.setCurrent(sq);
            player.setMovingSquare(sq);
        }
    }
}

