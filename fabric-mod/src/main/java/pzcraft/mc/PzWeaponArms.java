package pzcraft.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** PZ hand tracks drive Minecraft's native arm models at the fallback weapon's actual grip points. */
final class PzWeaponArms {
    private static final net.minecraft.util.RandomSource RANDOM = net.minecraft.util.RandomSource.create(0);
    private PzWeaponArms() {}

    static void submit(PoseStack weapon, SubmitNodeCollector collector, int light, float forward) {
        float[] delta = GunMeshAnim.hands();
        for (int side = 0; side < 2; side++) {
            int offset = side * 3;
            Vector3f palm = new Vector3f(delta[offset], (side == 0 ? 0 : forward) + delta[offset + 1],
                    (float) Tuning.get(side == 0 ? "pzgun.gripHeight" : "pzgun.supportHeight", side == 0 ? -.055 : -.035) + delta[offset + 2]);
            weapon.last().pose().transformPosition(palm);
            Vector3f shoulder = new Vector3f(side == 0 ? .43f : -.43f, -1.05f, -.35f);
            Vector3f direction = new Vector3f(palm).sub(shoulder);
            float length = direction.length();
            if (length < .05f || !Float.isFinite(length)) continue;
            direction.div(length);
            var arm = side == 0 ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
            var model = EntitySceneExporter.armModel(arm);
            if (model == null) continue;
            model.resetPose();
            var cube = model.getRandomCube(RANDOM);
            float angle = side == 0 ? .1f : -.1f;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            float cx = (cube.minX + cube.maxX) / 2, cy = cube.minY;
            Vector3f nativeShoulder = new Vector3f(model.x + cx*cos - cy*sin,
                    model.y + cx*sin + cy*cos, model.z + (cube.minZ + cube.maxZ) / 2).div(16);
            float span = (cube.maxY - cube.minY - 1.25f) / 16;
            float across = (float) Tuning.get("pzgun.armWidth", .36);
            float dx = -sin * span * across;
            float stretch = (float) Math.sqrt(Math.max(.0001f, length*length-dx*dx)) / (cos*span);
            Vector3f sourceDirection = new Vector3f(dx, cos*span*stretch, 0).normalize();
            Quaternionf orientation = new Quaternionf().rotationAxis((float) Math.toRadians(Tuning.get("pzgun.armRoll",180)),
                    direction.x,direction.y,direction.z).mul(new Quaternionf().rotationTo(sourceDirection,direction));
            PoseStack pose = new PoseStack();
            pose.translate(shoulder.x, shoulder.y, shoulder.z);
            pose.mulPose(new Matrix4f().rotation(orientation));
            pose.scale(across, stretch, across);
            pose.translate(-nativeShoulder.x,-nativeShoulder.y,-nativeShoulder.z);
            EntitySceneExporter.drawArm(pose, collector, light == 0 ? 15728880 : light, arm);
        }
    }
}
