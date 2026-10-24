package pzcraft.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 1.20.1 port of the shared PzWeaponArms: PZ hand tracks drive Minecraft's native arm models at the fallback weapon's grip
 * points. Same math; the player model's arm and sleeve are drawn directly in their rest pose.
 */
final class PzWeaponArms {
    private static final net.minecraft.util.RandomSource RANDOM = net.minecraft.util.RandomSource.create(0);
    private PzWeaponArms() {}

    static void submit(PoseStack weapon, MultiBufferSource source, int light, float forward) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerRenderer renderer)) return;
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
            ModelPart model = arm == HumanoidArm.RIGHT ? renderer.getModel().rightArm : renderer.getModel().leftArm;
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
            pose.mulPose(orientation);
            pose.scale(across, stretch, across);
            pose.translate(-nativeShoulder.x,-nativeShoulder.y,-nativeShoulder.z);
            // Drawn here rather than with renderRightHand/renderLeftHand: those run setupAnim, which leaves the gun's
            // crossbow-hold yaw on the arm. The math above expects the rest pose with the hand's small roll.
            ModelPart sleeve = arm == HumanoidArm.RIGHT ? renderer.getModel().rightSleeve : renderer.getModel().leftSleeve;
            sleeve.resetPose();
            model.zRot = sleeve.zRot = angle;
            int lit = light == 0 ? 15728880 : light;
            var skin = mc.player.getSkinTextureLocation();
            model.render(pose, source.getBuffer(RenderType.entitySolid(skin)), lit, OverlayTexture.NO_OVERLAY);
            if (mc.player.isModelPartShown(arm == HumanoidArm.RIGHT ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE))
                sleeve.render(pose, source.getBuffer(RenderType.entityTranslucent(skin)), lit, OverlayTexture.NO_OVERLAY);
            model.resetPose(); sleeve.resetPose();
        }
    }
}
