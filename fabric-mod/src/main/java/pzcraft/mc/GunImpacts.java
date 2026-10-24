package pzcraft.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What a confirmed shot does where it ends, after the weapon effects: a living target hit by PZ's roll sprays blood (a 2x2
 * sprite sheet, gravity 2, half a second of fade-out per two) and a kill adds blood mist (bounding-box sized, three seconds);
 * hitting a surface puffs vanilla smoke (five particles), with block chips from a Minecraft block or dirt debris from a PZ
 * surface. The blood and debris are sprites of our own particle list drawn as billboards in the entity pass; smoke and chips are
 * vanilla particles, which that pass already captures. Numbers: {@code impact.*} in {@code ~/.pzcraft/tuning.json}.
 */
final class GunImpacts {
    private static final Identifier BLOOD = Identifier.parse(PzBlocks.NAMESPACE + ":textures/effect/blood.png");
    private static final Identifier MIST = Identifier.parse(PzBlocks.NAMESPACE + ":textures/effect/blood2.png");
    private static final Identifier DEBRIS = Identifier.parse(PzBlocks.NAMESPACE + ":textures/effect/dirt3x3.png");
    private static final Random RANDOM = new Random();
    private static final int MAX_SPRITES = 400;

    /** One sprite particle; units as in vanilla's particle engine: blocks per 50 ms tick, gravity as a multiplier of 0.04. */
    private static final class Sprite {
        Identifier texture; int cols, rows, frame;
        double x, y, z, vx, vy, vz, floorY = Double.NEGATIVE_INFINITY;
        float gravity, friction = 0.96f, halfSize, roll, rollSpeed, maxAlpha = 1f, lifeSeconds, fadeIn = 0.01f, fadeOut = 0.5f;
        float age;
    }

    private static final List<Sprite> SPRITES = new ArrayList<>();
    private static float tickClock;
    static volatile int bloodSprites, mistSprites, debrisSprites, smokePuffs, chips;

    private GunImpacts() {}

    // ---- spawning (client thread, when PZ confirms a shot) ----

    /**
     * {@code result}: PZ's answer to the shot. {@code hits} lists the actors PZ's own roll hurt (id, damage, killed); a body in the
     * ray's way that PZ did not hurt gets nothing (the bullet missed it). Every surface a pellet ended on gets smoke and chips.
     */
    static void impact(PzGuns.Shot shot, com.google.gson.JsonObject result) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // the actors PZ hurt: id -> {damage, killed}
        var hurt = new java.util.HashMap<Integer, float[]>();
        if (result != null && result.has("hits")) {
            for (var e : result.getAsJsonArray("hits")) {
                var h = e.getAsJsonObject();
                hurt.put(h.get("actor").getAsInt(), new float[] {h.get("damage").getAsFloat(), h.get("killed").getAsBoolean() ? 1f : 0f});
            }
        } else if (result != null && result.has("hit") && result.get("hit").getAsBoolean() && shot.target() != null) {
            // an older answer without the list: the one target of the one ray
            hurt.put(-2, new float[] {result.has("damage") ? result.get("damage").getAsFloat() : 1f,
                    result.has("killed") && result.get("killed").getAsBoolean() ? 1f : 0f});
        }
        int surfaces = 0;
        for (var i : shot.impacts()) if (i.target() == null) surfaces++;
        var done = new java.util.HashSet<Integer>();
        for (var i : shot.impacts()) {
            if (i.target() != null) {
                var h = hurt.getOrDefault(i.actor(), hurt.get(-2));
                if (h == null || !done.add(i.actor())) continue;   // PZ rolled a miss, or this body already sprayed for an earlier pellet
                // Particle count follows the confirmed damage; PZ's pistol damage
                // is about 1, so scale it to the same range.
                blood(i.at(), i.target().minY, Mth.clamp(Math.round(9f * Math.max(0.2f, h[0]) / 1.2f), 5, 30));
                if (h[1] > 0f) mist(i.target().getCenter(), i.target().getSize());
            } else if (i.normal() != null) {
                int puffs = surfaces <= 1 ? 5 : 2;      // nine pellets in a wall would be a fog bank
                smoke(mc, i.at(), i.normal(), puffs);
                if (i.block() != null) chips(mc, i.at(), i.normal(), i.block(), surfaces <= 1 ? 6 : 2);
                else debris(i.at(), i.normal(), surfaces <= 1 ? 6 : 2);
            }
        }
    }

    private static void smoke(Minecraft mc, Vec3 at, Vec3 n, int count) {
        // Five smoke particles at the hit point
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(ParticleTypes.SMOKE, at.x + n.x * 0.05, at.y + n.y * 0.05, at.z + n.z * 0.05,
                    n.x * 0.02 + (RANDOM.nextDouble() - .5) * 0.03, n.y * 0.02 + 0.01 + (RANDOM.nextDouble() - .5) * 0.02,
                    n.z * 0.02 + (RANDOM.nextDouble() - .5) * 0.03);
            smokePuffs++;
        }
    }

    private static void chips(Minecraft mc, Vec3 at, Vec3 n, net.minecraft.core.BlockPos pos, int count) {
        var state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x + n.x * 0.05, at.y + n.y * 0.05, at.z + n.z * 0.05,
                    n.x * 0.12 + (RANDOM.nextDouble() - .5) * 0.12, n.y * 0.12 + (RANDOM.nextDouble() - .3) * 0.12,
                    n.z * 0.12 + (RANDOM.nextDouble() - .5) * 0.12);
            chips++;
        }
    }

    /** Dirt flecks off a PZ wall or floor (the original debris sprites, a 3x3 sheet), thrown along the surface normal. */
    private static void debris(Vec3 at, Vec3 n, int count) {
        double scale = Tuning.get("impact.debrisScale", 1.0);
        synchronized (SPRITES) {
            for (int i = 0; i < count && SPRITES.size() < MAX_SPRITES; i++) {
                var s = sprite(DEBRIS, 3, 3, RANDOM.nextInt(9));
                s.x = at.x + n.x * 0.03; s.y = at.y + n.y * 0.03; s.z = at.z + n.z * 0.03;
                double speed = 0.06 + RANDOM.nextDouble() * 0.1;
                s.vx = n.x * speed + (RANDOM.nextDouble() - .5) * 0.08; s.vy = n.y * speed + (RANDOM.nextDouble() - .3) * 0.08;
                s.vz = n.z * speed + (RANDOM.nextDouble() - .5) * 0.08;
                s.gravity = 1f; s.halfSize = (float) (0.05 * scale); s.lifeSeconds = 0.7f + RANDOM.nextFloat() * 0.4f;
                s.rollSpeed = (RANDOM.nextFloat() - .5f) * 6f; s.maxAlpha = 0.9f;
                SPRITES.add(s);
                debrisSprites++;
            }
        }
    }

    /** Blood.png: random one of four splats, thrown into the hemisphere above the hit (speed 0.25 blocks/tick, gravity 2). */
    private static void blood(Vec3 at, double floorY, int count) {
        double scale = Tuning.get("impact.bloodSize", 1.0);
        float alpha = (float) Tuning.get("impact.bloodAlpha", 1.0);
        synchronized (SPRITES) {
            for (int i = 0; i < count && SPRITES.size() < MAX_SPRITES; i++) {
                var s = sprite(BLOOD, 2, 2, RANDOM.nextInt(4));
                s.x = at.x + RANDOM.nextFloat() * 0.01; s.y = at.y + RANDOM.nextFloat() * 0.01; s.z = at.z + RANDOM.nextFloat() * 0.01;
                double speed = Mth.clamp((RANDOM.nextGaussian() * 0.5 + 0.25) * 0.3, 0.25, 2.0);
                double theta = Math.PI * 2 * RANDOM.nextDouble(), phi = Math.acos(RANDOM.nextDouble());
                s.vx = speed * Math.sin(phi) * Math.cos(theta) + (RANDOM.nextDouble() - .5) * 0.04;
                s.vy = speed * Math.cos(phi) + (RANDOM.nextDouble() - .5) * 0.04;
                s.vz = speed * Math.sin(phi) * Math.sin(theta) + (RANDOM.nextDouble() - .5) * 0.04;
                s.floorY = floorY + 0.02;
                s.gravity = 2f; s.halfSize = (float) (0.15 * scale); s.lifeSeconds = 2f; s.maxAlpha = alpha;
                SPRITES.add(s);
                bloodSprites++;
            }
        }
    }

    /** Blood2.png: slow drifting clouds sized by the target's bounding box (one to nine, three seconds). */
    private static void mist(Vec3 centre, double boxSize) {
        double scale = Tuning.get("impact.mistSize", 1.0);
        float alpha = (float) Tuning.get("impact.mistAlpha", 0.85);
        int count = 1 + RANDOM.nextInt(9);
        synchronized (SPRITES) {
            for (int i = 0; i < count && SPRITES.size() < MAX_SPRITES; i++) {
                var s = sprite(MIST, 1, 1, 0);
                s.x = centre.x + RANDOM.nextFloat() * 0.01; s.y = centre.y + RANDOM.nextFloat() * 0.01; s.z = centre.z + RANDOM.nextFloat() * 0.01;
                double theta = Math.PI * 2 * RANDOM.nextDouble(), phi = Math.acos(2 * RANDOM.nextDouble() - 1);
                s.vx = 0.03 * Math.sin(phi) * Math.cos(theta); s.vy = 0.03 * Math.cos(phi); s.vz = 0.03 * Math.sin(phi) * Math.sin(theta);
                s.gravity = 0.01f;
                s.halfSize = (float) (Mth.clamp(1.0 * boxSize / 1.5, 0.0, 1.0) * scale);
                s.lifeSeconds = 3f; s.rollSpeed = 0.01f * (float) (Math.PI * 2) / 3f; s.maxAlpha = alpha;
                SPRITES.add(s);
                mistSprites++;
            }
        }
    }

    private static Sprite sprite(Identifier texture, int cols, int rows, int frame) {
        var s = new Sprite();
        s.texture = texture; s.cols = cols; s.rows = rows; s.frame = frame;
        s.roll = RANDOM.nextFloat() * (float) (Math.PI * 2);
        return s;
    }

    // ---- per frame ----

    /** Fixed 50 ms steps, like the particle engine the numbers come from. */
    static void frame(float dt) {
        synchronized (SPRITES) {
            if (SPRITES.isEmpty()) { tickClock = 0; return; }
            tickClock += dt;
            while (tickClock >= 0.05f) {
                tickClock -= 0.05f;
                for (var s : SPRITES) {
                    s.age += 0.05f;
                    s.vy -= 0.04 * s.gravity;
                    s.x += s.vx; s.y += s.vy; s.z += s.vz;
                    s.vx *= s.friction; s.vy *= s.friction; s.vz *= s.friction;
                    s.roll += s.rollSpeed * 0.05f;
                    if (s.y < s.floorY) { s.y = s.floorY; s.vy = 0; s.vx *= 0.7; s.vz *= 0.7; }
                }
            }
            SPRITES.removeIf(s -> s.age >= s.lifeSeconds);
        }
    }

    /** Sprites as camera-facing billboards; positions are relative to the export origin ({@code ox, oy, oz}) like the tracers. */
    static void submit(EntityCapture capture, Vec3 camera, int ox, int oy, int oz) {
        synchronized (SPRITES) {
            if (SPRITES.isEmpty()) return;
            Vec3 origin = new Vec3(ox, oy, oz), cam = camera.subtract(origin);
            for (var s : SPRITES) {
                float progress = Mth.clamp(s.age / s.lifeSeconds, 0f, 1f);
                float alpha = s.maxAlpha * fade(progress, s.fadeIn, s.fadeOut);
                if (alpha <= 0.003f) continue;
                float u0 = (s.frame % s.cols) / (float) s.cols, v0 = (s.frame / s.cols) / (float) s.rows;
                capture.billboard(s.texture, new Vec3(s.x - ox, s.y - oy, s.z - oz), cam, s.halfSize, s.roll,
                        u0, u0 + 1f / s.cols, v0, v0 + 1f / s.rows, alpha, false, 0f);
            }
        }
    }

    /** Envelope: sine in over {@code in}, flat, cosine out after {@code out}. */
    private static float fade(float progress, float in, float out) {
        float factor;
        if (progress < in) factor = Mth.sin((float) (Math.PI / 2) * progress / in);
        else if (progress > out) factor = Mth.cos((float) (Math.PI / 2) * (progress - out) / (1f - out));
        else factor = 1f;
        return Mth.clamp(factor, 0f, 1f);
    }

    static java.util.Map<String, Object> stats() {
        var m = new java.util.LinkedHashMap<String, Object>();
        synchronized (SPRITES) { m.put("sprites", SPRITES.size()); }
        m.put("blood", bloodSprites); m.put("mist", mistSprites); m.put("debris", debrisSprites);
        m.put("smoke", smokePuffs); m.put("chips", chips);
        return m;
    }
}
