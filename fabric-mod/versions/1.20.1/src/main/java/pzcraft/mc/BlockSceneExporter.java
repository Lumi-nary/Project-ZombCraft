package pzcraft.mc;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import pzcraft.protocol.SceneLink;
import pzcraft.protocol.VertexLight;

/**
 * 1.20.1 version of the shared BlockSceneExporter: exports the baked block models around Steve for PZ to draw natively
 * (with depth, so dug holes and blocks below PZ's floor show). Same mesh format and rules as 26.x; 1.20.1 differences:
 * BakedModel/BakedQuad (packed vertex ints) instead of model parts, the atlas read back with glGetTexImage, and
 * LiquidBlockRenderer through FluidCapture. 1.20.1 does not export entities yet: see its EntitySceneExporter.
 */
public final class BlockSceneExporter {
    private static SceneLink link;
    private static AbstractTexture capturedAtlas;
    private static volatile long atlasRevision;
    private static final AtomicLong changes = new AtomicLong();
    private static long exportedChange = -1, nextScan;
    private static int followUps;
    private static int lastChunkX = Integer.MIN_VALUE, lastChunkZ;
    private static ClientLevel lastLevel;
    private static boolean failed;
    private static long nextAtlasRefresh;
    private static boolean animatedMesh;
    private static int capturedGlId;
    private static ByteBuffer atlasPixels, meshVertices;
    private static final java.util.Map<net.minecraft.client.renderer.texture.SpriteContents,Boolean> animations=new java.util.IdentityHashMap<>();
    private static final int RADIUS = 2;
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

    private BlockSceneExporter() {}
    public static void changed() { changes.incrementAndGet(); }

    static void tick(Minecraft mc) {
        if (failed || !Session.pzConnected || mc.player == null || mc.level == null) return;
        try {
            if (link == null) link = SceneLink.open(true);
            long now = System.nanoTime();
            selection(mc);
            AbstractTexture texture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (texture == null || texture.getId() <= 0) return;
            // animated textures (water, lava, fire) change the atlas: refresh it, as 26.x does
            if (texture != capturedAtlas || texture.getId()!=capturedGlId || animatedMesh && now >= nextAtlasRefresh) {
                boolean layoutChanged = texture != capturedAtlas || texture.getId()!=capturedGlId;
                captureAtlas(texture);
                nextAtlasRefresh = now + 250_000_000L;
                if (layoutChanged) { changed(); return; }
            }
            if (atlasRevision == 0) return;
            int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
            long change = changes.get();
            if (lastLevel == mc.level && cx == lastChunkX && cz == lastChunkZ
                    && change == exportedChange && now < nextScan) return;
            export(mc, cx, cz);
            lastLevel = mc.level; lastChunkX = cx; lastChunkZ = cz;
            if (change != exportedChange) followUps = 3;
            exportedChange = change;
            if (followUps > 0) {
                followUps--;
                nextScan = now + 200_000_000L;
            } else {
                nextScan = now + 1_000_000_000L;
            }
        } catch (Throwable t) {
            failed = true;
            if (link != null) link.invalidate();
            PzCraftMain.LOG.error("Native block export disabled; keeping the video overlay", t);
        }
    }

    private static void selection(Minecraft mc) {
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            var state = mc.level.getBlockState(pos);
            var shape = state.getShape(mc.level, pos);
            if (!state.isAir() && !shape.isEmpty()) {
                var box = shape.bounds().inflate(.003);
                link.writeSelection(true, pos.getX(), pos.getY(), pos.getZ(), (float)box.minX, (float)box.minY,
                        (float)box.minZ, (float)box.maxX, (float)box.maxY, (float)box.maxZ);
                return;
            }
        }
        link.writeSelection(false, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Synchronous read of the block atlas' top mip level (render thread, between frames). */
    private static void captureAtlas(AbstractTexture texture) {
        RenderSystem.assertOnRenderThread();
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._bindTexture(texture.getId());
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) return;
        if (w > SceneLink.MAX_ATLAS_SIDE || h > SceneLink.MAX_ATLAS_SIDE)
            throw new IllegalStateException("Block atlas exceeds scene transport limit: " + w + "x" + h);
        int bytes=w*h*4;
        if(atlasPixels==null || atlasPixels.capacity()!=bytes) {
            if(atlasPixels!=null)MemoryUtil.memFree(atlasPixels);
            atlasPixels=MemoryUtil.memAlloc(bytes);
        }
        ByteBuffer pixels=atlasPixels;pixels.clear();
        try {
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            boolean layoutChanged = capturedAtlas != texture;
            atlasRevision = link.writeAtlas(w, h, pixels);
            capturedAtlas = texture;capturedGlId=texture.getId();
            if (layoutChanged) PzCraftMain.LOG.info("Block atlas exported: {}x{}", w, h);
        } finally {
            GlStateManager._bindTexture(previous);
        }
    }

    private static void export(Minecraft mc, int cx, int cz) {
        if(meshVertices==null)meshVertices=MemoryUtil.memAlloc(SceneLink.MAX_VERTICES*SceneLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer vertices=meshVertices;vertices.clear();animatedMesh=false;
        var random = RandomSource.create();
        var blocks = mc.getBlockRenderer();
        int ox = cx << 4, oy = 0, oz = cz << 4;
        try {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                var chunk = mc.level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                var sections = chunk.getSections();
                for (int sy = 0; sy < sections.length; sy++) {
                    var section = sections[sy];
                    if (section.hasOnlyAir()) continue;
                    int baseY = mc.level.getSectionYFromSectionIndex(sy) << 4;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        var pos = new BlockPos(((cx + dx) << 4) + x, baseY + y, ((cz + dz) << 4) + z);
                        if (!state.getFluidState().isEmpty()) {
                            animatedMesh=true;
                            var fluid = new FluidCapture(vertices, (pos.getX() & ~15) - ox, (pos.getY() & ~15) - oy, (pos.getZ() & ~15) - oz,
                                    state.getLightEmission() / 15f);
                            blocks.renderLiquid(pos, mc.level, fluid, state, state.getFluidState());
                            fluid.finish();
                        }
                        if (state.getRenderShape() != RenderShape.MODEL) continue;   // invisible, or drawn by a block entity renderer
                        var model = blocks.getBlockModel(state);
                        long seed = state.getSeed(pos);
                        if (!((net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel) model).isVanillaAdapter()) {
                            emitted(mc, blocks, model, state, pos, seed, random, vertices, ox, oy, oz);
                            continue;
                        }
                        random.setSeed(seed);
                        for (var q : model.getQuads(state, null, random)) quad(mc, pos, null, q, vertices, ox, oy, oz);
                        for (var face : Direction.values()) {
                            var neighborPos = pos.relative(face);
                            var neighbor = mc.level.getBlockState(neighborPos);
                            if (state.skipRendering(neighbor, face) || neighbor.isSolidRender(mc.level, neighborPos)) continue;
                            if (pos.getY() < 0 && (GroundBridge.virtualSolid(neighborPos.getX(), neighborPos.getY(), neighborPos.getZ())
                                    || face == Direction.UP && pos.getY() == -1 && GroundBridge.floorIntact(pos.getX(), pos.getZ()))) continue;
                            random.setSeed(seed);
                            for (var q : model.getQuads(state, face, random)) quad(mc, pos, face, q, vertices, ox, oy, oz);
                        }
                    }
                }
            }
            vertices.flip();
            link.writeMesh(atlasRevision, ox, oy, oz, vertices);
        } finally { vertices.clear(); }
    }

    /**
     * A model that emits its own geometry through the Fabric renderer API (Create's bracketed shafts and cogwheels,
     * connected textures, ...): its getQuads is only a fallback. Create's cogwheel returns the whole cog there, while the
     * chunk renderer draws just the bracket and the cog turns as a block entity, so reading getQuads gave a second,
     * still cog. Render it as the chunk renderer does (Indigo's tesselateBlock hook) and capture what it emits; light
     * comes from the vertices, as for fluids.
     */
    private static void emitted(Minecraft mc, net.minecraft.client.renderer.block.BlockRenderDispatcher blocks,
                                net.minecraft.client.resources.model.BakedModel model,
                                net.minecraft.world.level.block.state.BlockState state, BlockPos pos, long seed,
                                RandomSource random, ByteBuffer vertices, int ox, int oy, int oz) {
        var capture = new FluidCapture(vertices, pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz, state.getLightEmission() / 15f);
        blocks.getModelRenderer().tesselateBlock(mc.level, model, state, pos, new com.mojang.blaze3d.vertex.PoseStack(), capture,
                true, random, seed, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        capture.finish();
    }

    private static void quad(Minecraft mc, BlockPos pos, Direction face, BakedQuad q, ByteBuffer out, int ox, int oy, int oz) {
        if (out.remaining() < 6 * SceneLink.VERTEX_BYTES) throw new IllegalStateException("Too many native block vertices");
        animatedMesh |= animations.computeIfAbsent(q.getSprite().contents(), c->c.getUniqueFrames().limit(2).count()>1);
        var state = mc.level.getBlockState(pos);
        int light = lightCoords(mc.level, pos, face, state);
        int tint = q.isTinted() ? mc.getBlockColors().getColor(state, mc.level, pos, q.getTintIndex()) : -1;
        if (tint == -1) tint = 0xFFFFFF;
        int[] v = q.getVertices();   // DefaultVertexFormat.BLOCK: x y z colour u v light normal, 8 ints a vertex
        var p0 = new org.joml.Vector3f(f(v, 0, 0), f(v, 0, 1), f(v, 0, 2));
        var n = new org.joml.Vector3f(f(v, 1, 0), f(v, 1, 1), f(v, 1, 2)).sub(p0)
                .cross(new org.joml.Vector3f(f(v, 2, 0), f(v, 2, 1), f(v, 2, 2)).sub(p0));
        if (n.lengthSquared() < 1e-8f) n.set(q.getDirection().getStepX(), q.getDirection().getStepY(), q.getDirection().getStepZ());
        else n.normalize();
        float emission = state.getLightEmission() / 15f;
        for (int i : TRIANGLES) {
            out.putFloat(pos.getX() - ox + f(v, i, 0)).putFloat(pos.getY() - oy + f(v, i, 1)).putFloat(pos.getZ() - oz + f(v, i, 2));
            out.putFloat(n.x()).putFloat(n.y()).putFloat(n.z());
            out.putFloat(f(v, i, 4)).putFloat(f(v, i, 5));
            out.putFloat((tint >> 16 & 255) / 255f).putFloat((tint >> 8 & 255) / 255f).putFloat((tint & 255) / 255f);
            out.putFloat(VertexLight.packCoords(emission, light));
        }
    }

    private static float f(int[] v, int vertex, int element) { return Float.intBitsToFloat(v[vertex * 8 + element]); }

    /** Minecraft's light on a face, as light coordinates (block light << 4, sky light << 20), as on 26.x. */
    static int lightCoords(net.minecraft.world.level.Level level, BlockPos pos, Direction face,
                           net.minecraft.world.level.block.state.BlockState state) {
        BlockPos at = face == null ? pos : pos.relative(face);
        int block = level.getBrightness(LightLayer.BLOCK, at), sky = level.getBrightness(LightLayer.SKY, at);
        if (face == null && state.isSolidRender(level, pos)) {
            for (Direction d : Direction.values()) {
                BlockPos p = pos.relative(d);
                block = Math.max(block, level.getBrightness(LightLayer.BLOCK, p));
                sky = Math.max(sky, level.getBrightness(LightLayer.SKY, p));
            }
        }
        return sky << 20 | block << 4;
    }
}
