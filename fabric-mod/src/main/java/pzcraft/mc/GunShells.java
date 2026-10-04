package pzcraft.mc;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Visual casings for confirmed native shots; the private reference's bone, model and short local flight. */
final class GunShells {
    private static final long STEP = 5_000_000L, LIFE = 30 * STEP;
    private static final Identifier MODEL = Identifier.parse("pzcraft:entity/shell_casing/pistol_shell");
    private static final Identifier TEXTURE = Identifier.parse("pzcraft:textures/entity/shell_casing/ar_shell.png");
    private static final List<Shell> SHELLS = new ArrayList<>();
    private static long spawned;

    private GunShells() {}

    private static final class Shell {
        final long born = System.nanoTime();
        long updated = born, remainder;
        Vec3 position = Vec3.ZERO, previous = Vec3.ZERO;
        Vec3 velocity = new Vec3(random(1.1, .15) * .14, random(.35, .1) * 1.75 * .14, 0);
        final Vector3f angular = new Vector3f(spin(18, 8), spin(26, 10), spin(34, 12));
        final Quaternionf rotation = new Quaternionf().rotateY((float)Math.PI), previousRotation = new Quaternionf(rotation);

        void update(long now) {
            remainder += Math.max(0, now - updated);
            updated = now;
            int steps = (int)Math.min(8, remainder / STEP);
            remainder %= STEP;
            for (int i = 0; i < steps; i++) {
                previous = position;
                previousRotation.set(rotation);
                position = position.add(velocity);
                velocity = velocity.add(0, -.004, 0);
                rotation.rotateLocalX((float)Math.toRadians(angular.x)).rotateLocalY((float)Math.toRadians(angular.y))
                        .rotateLocalZ((float)Math.toRadians(angular.z)).normalize();
                angular.mul(.96f);
            }
        }
    }

    private static double random(double centre, double spread) { return centre + ThreadLocalRandom.current().nextDouble(-spread, spread); }
    private static float spin(double centre, double spread) { return (float)random(centre, spread) * (ThreadLocalRandom.current().nextBoolean() ? 1 : -1); }

    static void fired() {
        if (!Minecraft.getInstance().options.getCameraType().isFirstPerson()) return;
        SHELLS.add(new Shell());
        spawned++;
    }

    static void frame() {
        long now = System.nanoTime();
        SHELLS.removeIf(shell -> now - shell.born >= LIFE);
        for (Shell shell : SHELLS) shell.update(now);
    }

    static java.util.Map<String, Object> stats() { return java.util.Map.of("active", SHELLS.size(), "spawned", spawned); }

    static void submit(RenderPassInfo<GeoRenderState> pass, EntityCapture capture) {
        if (SHELLS.isEmpty()) return;
        var model = GeckoLibResources.getBakedModels().getModel(MODEL);
        if (model == null) return;
        long now = System.nanoTime();
        for (Shell shell : SHELLS) {
            float interpolation = (float)shell.remainder / STEP;
            Vec3 position = shell.previous.lerp(shell.position, interpolation);
            var rotation = new Quaternionf(shell.previousRotation).slerp(shell.rotation, interpolation);
            float progress = (float)(now - shell.born) / LIFE;
            float scale = Math.max(0, Math.min(1, (1 - progress) / .08f));
            var pose = pass.poseStack();
            pose.pushPose();
            pose.translate(position.x, position.y, position.z);
            pose.rotate(rotation);
            pose.scale(scale, scale, scale);
            capture.submitCustomGeometry(pose, RenderTypes.entityCutout(TEXTURE), (matrix, buffer) -> {
                var shellPose = new PoseStack();
                shellPose.mulPose(matrix.pose());
                for (GeoBone bone : model.topLevelBones()) renderBone(bone, pass, shellPose, buffer);
            });
            pose.popPose();
        }
    }

    private static void renderBone(GeoBone bone, RenderPassInfo<GeoRenderState> pass, PoseStack pose, VertexConsumer buffer) {
        pose.pushPose();
        RenderUtil.prepMatrixForBone(pose, bone);
        bone.render(pass, pose, buffer, pass.packedLight(), pass.packedOverlay(), pass.renderColor());
        for (GeoBone child : bone.children()) renderBone(child, pass, pose, buffer);
        pose.popPose();
    }
}

