package pzcraft.pz;

import java.util.IdentityHashMap;
import java.util.Map;
import pzcraft.protocol.Coords;
import zombie.characters.IsoZombie;
import zombie.iso.IsoWorld;

/** Native blast motion, with Minecraft supplying the landing height when its crater has no PZ physics floor. */
final class BlastRagdolls {
    private static final class Flight {
        float age;
        double support;
        boolean sampled;
        int stable;
        Flight(double y) { support = y; }
    }
    private static final Map<IsoZombie, Flight> flying = new IdentityHashMap<>();
    private static int landed;
    private BlastRagdolls() {}
    static boolean tracks(Object z) { return flying.containsKey(z); }
    static void start(IsoZombie z) { flying.putIfAbsent(z, new Flight(ActorTerrain.mcY(z))); }
    static void support(IsoZombie z, double y) {
        Flight f = flying.get(z);
        if (f != null) {
            f.stable = Math.abs(f.support - y) < .01 ? f.stable + 1 : 0;
            f.support = y; f.sampled = f.stable >= 2;
        }
    }
    static void tick() {
        if (PauseFreeze.pzPaused()) return;
        var it = flying.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next(); var z = entry.getKey(); var f = entry.getValue();
            if (z.isOnDeathDone()) { it.remove(); continue; }
            f.age += zombie.GameTime.getInstance().getTimeDelta();
            // Allow native tumbling. If its floor is absent, stop at the actual MC support instead of falling forever.
            if (f.age < .35f || (!f.sampled && f.age < 5f)) continue;
            if (Coords.mcY(z.getZ()) >= f.support - .45 && f.age < 4f) continue;
            var cell = IsoWorld.instance.getCell();
            var sq = cell.getGridSquare((int) Math.floor(z.getX()), (int) Math.floor(z.getY()), 0);
            if (sq == null) sq = z.getCurrentSquare();
            if (sq == null) continue;
            z.setCurrent(sq); z.setMovingSquare(sq);
            z.setZ((float) Coords.pzZ(f.support));
            if (z.getRagdollController() != null) {
                z.setRagdollFall(true);
                z.getRagdollController().getRagdollStateData().isSimulating = false;
                z.getRagdollController().setActive(false);
            }
            var skin = z.getAnimationPlayer() == null ? null : z.getAnimationPlayer().getSkinningData();
            z.addOnDiedListener((character, body) -> {
                // Bullet's captured pose may be below its character origin after falling into a missing PZ floor.
                // The saved bones are LOCAL transforms. Measure their complete hierarchy, then move the whole body.
                // Shifting each local bone would add the offset repeatedly down a limb and stretch the skeleton.
                var bones = body.getDiedBoneTransforms();
                if (bones == null || bones.length == 0 || skin == null) return;
                var position = new org.lwjgl.util.vector.Vector3f();
                var model = new org.lwjgl.util.vector.Matrix4f();
                var local = new org.lwjgl.util.vector.Matrix4f();
                float min = Float.POSITIVE_INFINITY;
                var parents = skin.skeletonHierarchy;
                for (int i = 0; i < bones.length && i < parents.size(); i++) {
                    if (parents.get(i) < 0) continue; // dummy origin has no body surface
                    model.setIdentity();
                    for (int j = i, depth = 0; j >= 0 && j < bones.length && depth < bones.length; j = parents.get(j), depth++) {
                        bones[j].getMatrix(local);
                        org.lwjgl.util.vector.Matrix4f.mul(model, local, model);
                    }
                    zombie.core.skinnedmodel.HelperFunctions.getPosition(model, position);
                    min = Math.min(min, position.y);
                }
                if (!Float.isFinite(min)) return;
                double lift = .12 - min * 1.5; // Viewpoint's humanoid model scale
                body.setZ((float) (body.getZ() + Coords.pzZ(lift)));
                body.getModData().rawset("PzCraftBlastPoseWorldLift", lift);
            }, true);
            z.die(); // keeps PZ's native inventory, corpse and captured ragdoll bones
            landed++; it.remove();
        }
    }
    static Map<String, Object> stats() { return Map.of("flying", flying.size(), "landed", landed); }
}
