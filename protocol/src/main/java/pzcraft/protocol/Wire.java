package pzcraft.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Encoders/decoders for the gameplay messages that ride on the SharedLink rings. All fields little-endian. */
public final class Wire {
    private Wire() {}

    /** Message types (PZ -> MC unless noted). */
    public static final int MSG_ACTORS = 30;       // PZ zombies near the player, ~20 Hz
    public static final int MSG_HIT_ACTOR = 31;    // MC -> PZ: Steve hit an actor
    public static final int MSG_VITALS = 32;       // PZ's hunger (health01 is unused since Minecraft owns health)
    public static final int MSG_PLAYER_HURT = 33;  // retired: Minecraft owns Steve's health now
    public static final int MSG_TIME = 34;         // PZ's time of day
    public static final int MSG_ATE = 35;          // MC -> PZ: Steve ate; payload = food levels restored (float, 20 = a full bar)
    /** A PZ zombie's attack connected with the player: Minecraft runs it as that zombie's melee attack on Steve. */
    public static final int MSG_ZOMBIE_ATTACK = 36;
    /** PZ hurt the player some other way (fire, bleeding, starvation...): Minecraft hit points for Steve to lose. */
    public static final int MSG_PZ_DAMAGE = 37;
    /** MC -> PZ: Steve's health, which PZ's own health now mirrors (health, maxHealth). */
    public static final int MSG_STEVE_HEALTH = 38;
    /** MC -> PZ: Steve died; keep the camera puppet alive for Minecraft's respawn screen. */
    public static final int MSG_STEVE_DIED = 39;
    public static final int MSG_BLOCK = 40;        // MC -> PZ: a block became solid / stopped being solid (x, y, z, solid)
    public static final int MSG_BLOCKS_RESET = 41; // MC -> PZ: forget all blocks; a fresh scan follows
    public static final int MSG_VEHICLES = 42;     // PZ -> MC: full snapshot of moving collision bodies
    public static final int MSG_GUI_EVENT = 46;    // PZ -> MC: ordered keyboard/text/mouse event
    public static final int MSG_GUI_STATE = 47;    // MC -> PZ: screen ownership, event acknowledgement and diagnostics (JSON)
    public static final int MSG_RESPAWN_TARGET = 48; // MC -> PZ: server-selected destination, retry until ready
    public static final int MSG_RESPAWN_READY = 49;  // PZ -> MC: streamed destination sequence (long)
    public static final int MSG_CLOCK_STATE = 50;    // PZ -> MC: ticks and acknowledged change sequence
    public static final int MSG_CLOCK_CHANGE = 51;   // MC -> PZ: explicit time command or bed skip
    public static final int MSG_OBJECTS = 52;        // PZ -> MC: nearby interactable door/window faces
    public static final int MSG_INTERACT = 53;       // MC -> PZ: object id (int), vanilla PZ interaction
    public static final int MSG_LIGHTS = 54;         // MC -> PZ: full nearby block-light snapshot
    public static final int MSG_PASSENGERS = 55;     // MC -> PZ: proxy passengers and final dismount pose
    public static final int MSG_CLOCK_CONTROL = 56;  // MC -> PZ: rate and paused flag, periodically refreshed
    public static final int MSG_WEATHER_COMMAND = 57; // MC -> PZ: a /weather command for PZ's weather (Weather.Command)
    public static final int MSG_WEATHER_STATE = 58;   // PZ -> MC: the weather PZ shows, about once a second (Weather.State)
    public static final int MSG_MATERIALS = 59;       // PZ -> MC: MaterialSection of one 8x8 column and level (floors, edges, objects, trees)
    public static final int MSG_TREE_FELLED = 60;     // MC -> PZ: the tree on tile (x, y, level) was broken in Minecraft; PZ topples it
    public static final int MSG_PZ_EXPLOSION = 61;    // MC -> PZ: an explosion and the PZ objects it destroys (PzExplosion.Blast)
    public static final int MSG_CONTAINER_OPEN = 63;     // MC -> PZ: Steve opened the container object with this runtime id (int)
    public static final int MSG_CONTAINER_CONTENTS = 64; // PZ -> MC: that object's containers and their items (UTF-8 JSON)
    public static final int MSG_CONTAINER_COMMIT = 65;   // MC -> PZ: what Steve took out and put in (UTF-8 JSON, a difference)
    public static final int MSG_GROUND_EDIT = 62;     // MC -> PZ: the ground under tile (x, y) was dug away (PZ removes its floor) or refilled (PZ restores it)
    public static final int MSG_MIRROR_STATE = 66;    // MC -> PZ: Steve's whole inventory as groups of identical items, plus the PZ transactions applied (UTF-8 JSON)
    public static final int MSG_MIRROR_TX = 67;       // PZ -> MC: what PZ changed in its copy since the last agreed state: add / rem / rep (UTF-8 JSON)
    public static final int MSG_MIRROR_HELLO = 68;    // PZ -> MC: PZ (re)connected and knows nothing of what was sent: send everything again
    public static final int MSG_WEAPON_USED = 69;     // MC -> PZ: Steve's held melee weapon hit a target: PZ item type and item id (UTF-8 "type|id"); PZ rolls the wear
    public static final int MSG_MELEE_HIT = 70;       // MC -> PZ: landed PZ melee hit; native item/skills own damage, crit and wear
    public static final int MSG_GUN_ACTION = 71;      // MC server -> PZ: identity + server ray; native gun gameplay
    public static final int MSG_GUN_RESULT = 72;      // PZ -> MC: authoritative result/animation (UTF-8 JSON)
    public static final int MSG_ACTOR_GROUND = 73;    // MC -> PZ: ground-proxy collision and physical height

    /** One PZ actor mirrored into Minecraft as an invisible, hittable entity. Coordinates are Minecraft's. */
    public record Actor(int id, double x, double y, double z, float yawDeg, float health01, int flags) {
        public static final int FLAG_CRAWLING = 1;
        public static final int FLAG_ANIMAL = 2;
        public static final int FLAG_SMALL_ANIMAL = 4;
        public static final int FLAG_GROUND_PHYSICS = 8; // native ground-level zombie; y carries saved physical height
    }

    private static final int ACTOR_BYTES = 4 + 8 * 3 + 4 + 4 + 4;

    public static byte[] encodeActors(List<Actor> actors) {
        ByteBuffer b = ByteBuffer.allocate(4 + actors.size() * ACTOR_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(actors.size());
        for (Actor a : actors) {
            b.putInt(a.id).putDouble(a.x).putDouble(a.y).putDouble(a.z).putFloat(a.yawDeg).putFloat(a.health01).putInt(a.flags);
        }
        return b.array();
    }

    public static List<Actor> decodeActors(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int n = b.getInt();
        List<Actor> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Actor(b.getInt(), b.getDouble(), b.getDouble(), b.getDouble(), b.getFloat(), b.getFloat(), b.getInt()));
        }
        return out;
    }

    public record Hit(int actorId, float damage, float knockX, float knockZ) {}

    public static byte[] encodeHit(Hit h) {
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(h.actorId).putFloat(h.damage).putFloat(h.knockX).putFloat(h.knockZ).array();
    }

    public static Hit decodeHit(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new Hit(b.getInt(), b.getFloat(), b.getFloat(), b.getFloat());
    }

    /** A landed melee contact, with the exact hidden-inventory item and the target's order in the swing. */
    public record MeleeHit(int actorId, int itemId, int targetIndex, String weaponType) {}

    public static byte[] encodeMeleeHit(MeleeHit h) {
        byte[] type = h.weaponType.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (type.length == 0 || type.length > 512 || h.targetIndex < 1 || h.targetIndex > 128)
            throw new IllegalArgumentException("Invalid melee contact");
        return ByteBuffer.allocate(16 + type.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(h.actorId).putInt(h.itemId).putInt(h.targetIndex).putInt(type.length).put(type).array();
    }

    public static MeleeHit decodeMeleeHit(byte[] data) {
        if (data.length < 17 || data.length > 528) throw new IllegalArgumentException("Invalid melee payload length");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int actor = b.getInt(), item = b.getInt(), target = b.getInt(), length = b.getInt();
        if (length != b.remaining() || target < 1 || target > 128) throw new IllegalArgumentException("Invalid melee contact");
        byte[] type = new byte[length]; b.get(type);
        return new MeleeHit(actor, item, target, new String(type, java.nio.charset.StandardCharsets.UTF_8));
    }

    /** health01: 1 = full health. food01: 1 = fully fed (PZ hunger 0). */
    public record Vitals(float health01, float food01) {}

    public static byte[] encodeVitals(Vitals v) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(v.health01).putFloat(v.food01).array();
    }

    public static Vitals decodeVitals(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new Vitals(b.getFloat(), b.getFloat());
    }

    /** Minecraft hit points Steve lost, to be applied to the PZ player. */
    public static byte[] encodeHurt(float mcHitPoints) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(mcHitPoints).array();
    }

    public static float decodeHurt(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    public static byte[] encodeTime(float hour) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(hour).array();
    }

    public static float decodeTime(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    /** A PZ zombie (id) at Minecraft coordinates (x, y, z) landed an attack on the player. */
    public record ZombieAttack(int zombieId, double x, double y, double z) {}

    public static byte[] encodeZombieAttack(ZombieAttack a) {
        return ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putInt(a.zombieId).putDouble(a.x).putDouble(a.y).putDouble(a.z).array();
    }

    public static ZombieAttack decodeZombieAttack(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new ZombieAttack(b.getInt(), b.getDouble(), b.getDouble(), b.getDouble());
    }

    public static byte[] encodeFloats(float... v) {
        ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v) b.putFloat(f);
        return b.array();
    }

    public static float[] decodeFloats(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[data.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = b.getFloat();
        return out;
    }

    public static byte[] encodeBlock(int x, int y, int z, boolean solid) {
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).putInt(z).put((byte) (solid ? 1 : 0)).array();
    }

    /** {x, y, z, solid(0/1)} */
    public static int[] decodeBlock(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new int[] {b.getInt(), b.getInt(), b.getInt(), b.get()};
    }

    /** The top ground block under PZ tile (x, y) was dug away ({@code dug}) or put back. */
    public static byte[] encodeGroundEdit(int x, int y, boolean dug) {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).put((byte) (dug ? 1 : 0)).array();
    }
    /** {x, y, dug (1/0)} */
    public static int[] decodeGroundEdit(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new int[] {b.getInt(), b.getInt(), b.get()};
    }

    /** A PZ tile as (x, y, level): PZ coordinates, not Minecraft's. */
    public static byte[] encodeTile(int x, int y, int level) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(x).putInt(y).putInt(level).array();
    }
    public static int[] decodeTile(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new int[] {b.getInt(), b.getInt(), b.getInt()};
    }
}

