package pzcraft.mc;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import pzcraft.protocol.EntityLink;

/**
 * 1.20.1: the mining cracks of native blocks, sent to PZ as decals on the block's own quads (26.x does this in
 * EntityCapture.submitBreakingBlockModel; Crumbling120Mixin keeps vanilla from drawing them into the empty video layer).
 * The ten breaking stages are one 10x1 strip texture, so cracks take a single slot of the native entity transport.
 */
final class BlockCracks120 {
    static final ResourceLocation STRIP = new ResourceLocation(PzBlocks.NAMESPACE, "dynamic/mining_cracks");
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static boolean made, failed;
    private static final RandomSource random = RandomSource.create();
    private BlockCracks120() {}

    /** The ten destroy_stage textures of the active resource packs side by side (their first frame, square). */
    private static boolean strip(Minecraft mc) {
        if (made || failed) return made;
        NativeImage strip = null;
        try {
            int size = 0;
            for (int stage = 0; stage < 10; stage++) {
                var resource = mc.getResourceManager().getResourceOrThrow(new ResourceLocation("textures/block/destroy_stage_" + stage + ".png"));
                try (var in = resource.open(); var image = NativeImage.read(in)) {
                    if (strip == null) { size = image.getWidth(); strip = new NativeImage(size * 10, size, true); }
                    if (image.getWidth() != size || image.getHeight() < size)
                        throw new IllegalStateException("destroy_stage_" + stage + " is " + image.getWidth() + "x" + image.getHeight());
                    for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) strip.setPixelRGBA(stage * size + x, y, image.getPixelRGBA(x, y));
                }
            }
            mc.getTextureManager().register(STRIP, new DynamicTexture(strip));
            made = true;
        } catch (Exception e) {
            failed = true;
            if (strip != null) strip.close();
            PzCraftMain.LOG.warn("Mining cracks unavailable for native blocks: {}", e.toString());
        }
        return made;
    }

    static void submit(Minecraft mc, EntityCapture capture, int ox, int oy, int oz) {
        var progress = mc.levelRenderer.destructionProgress;
        if (progress.isEmpty() || mc.player == null || mc.level == null || !strip(mc)) return;
        int flags = capture.flags;
        capture.flags = EntityLink.FLAG_NO_SHADOW | EntityLink.FLAG_DECAL;
        VertexConsumer out = null;
        for (var entry : progress.long2ObjectEntrySet()) {
            var stages = entry.getValue();
            if (stages == null || stages.isEmpty()) continue;
            BlockPos pos = BlockPos.of(entry.getLongKey());
            if (pos.distSqr(mc.player.blockPosition()) > 48 * 48) continue;
            int stage = stages.last().getProgress();
            var state = mc.level.getBlockState(pos);
            if (stage < 0 || stage > 9 || state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
            var model = mc.getBlockRenderer().getBlockModel(state);
            if (out == null) out = capture.raw(STRIP);
            for (Direction side : SIDES) {
                random.setSeed(state.getSeed(pos));
                for (BakedQuad quad : model.getQuads(state, side, random))
                    quad(out, quad, stage, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, LevelRenderer.getLightColor(mc.level, pos.relative(quad.getDirection())));
            }
        }
        capture.flags = flags;
    }

    private static void quad(VertexConsumer out, BakedQuad quad, int stage, float bx, float by, float bz, int light) {
        int[] data = quad.getVertices();
        int stride = data.length / 4;
        var dir = quad.getDirection();
        float nx = dir.getStepX(), ny = dir.getStepY(), nz = dir.getStepZ();
        for (int i = 0; i < 4; i++) {
            float x = Float.intBitsToFloat(data[i * stride]), y = Float.intBitsToFloat(data[i * stride + 1]), z = Float.intBitsToFloat(data[i * stride + 2]);
            float u, v;
            switch (dir.getAxis()) {
                case X -> { u = z; v = 1 - y; }
                case Y -> { u = x; v = z; }
                default -> { u = x; v = 1 - y; }
            }
            // the strip holds the stages left to right; the small lift off the face keeps the decal above its block
            out.vertex(bx + x + nx * .002f, by + y + ny * .002f, bz + z + nz * .002f).color(255, 255, 255, 255)
                    .uv((stage + Mth.clamp(u, 0, 1)) / 10f, Mth.clamp(v, 0, 1)).overlayCoords(0, 10)
                    .uv2(light & 0xffff, light >> 16 & 0xffff).normal(nx, ny, nz).endVertex();
        }
    }
}
