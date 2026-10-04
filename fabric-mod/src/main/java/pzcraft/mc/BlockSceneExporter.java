package pzcraft.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.system.MemoryUtil;
import pzcraft.protocol.SceneLink;

/** Exports actual baked block models. Meshes update on block changes; PZ supplies the current camera every frame. */
public final class BlockSceneExporter {
    private static SceneLink link;
    private static GpuTexture capturedAtlas;
    private static boolean atlasPending;
    private static volatile long atlasRevision;
    private static final AtomicLong changes = new AtomicLong();
    private static long exportedChange = -1, nextScan;
    private static int lastChunkX = Integer.MIN_VALUE, lastChunkZ;
    private static ClientLevel lastLevel;
    private static boolean failed;
    private static long nextAtlasRefresh;
    private static final int RADIUS = 2;
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

    private BlockSceneExporter() {}
    public static void changed() { changes.incrementAndGet(); }

    static void tick(Minecraft mc) {
        if (failed || !Session.pzConnected || mc.player == null || mc.level == null) return;
        try {
            if (link == null) link = SceneLink.open(true);
            selection(mc);
            GpuTexture texture = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture();
            if (texture == null) return;
            if (texture != capturedAtlas) {
                if (!atlasPending) captureAtlas(texture);
                return;
            }
            if (atlasRevision == 0) return;
            if (!atlasPending && System.nanoTime() >= nextAtlasRefresh) {
                nextAtlasRefresh=System.nanoTime()+250_000_000L;
                captureAtlas(texture);
            }
            int cx = mc.player.blockPosition().getX() >> 4, cz = mc.player.blockPosition().getZ() >> 4;
            long change = changes.get(), now = System.nanoTime();
            if (lastLevel == mc.level && cx == lastChunkX && cz == lastChunkZ
                    && change == exportedChange && now < nextScan) return;
            // The periodic rescan also catches arriving chunks and resource reloads without relying on their internals.
            export(mc, cx, cz);
            lastLevel = mc.level; lastChunkX = cx; lastChunkZ = cz;
            exportedChange = change; nextScan = now + 1_000_000_000L;
        } catch (Throwable t) {
            failed = true;
            if (link != null) link.invalidate();
            PzCraftClient.LOG.error("Native block export disabled; keeping the video overlay", t);
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

    private static void captureAtlas(GpuTexture texture) {
        int w = texture.getWidth(0), h = texture.getHeight(0);
        if (w > SceneLink.MAX_ATLAS_SIDE || h > SceneLink.MAX_ATLAS_SIDE)
            throw new IllegalStateException("Block atlas exceeds scene transport limit: " + w + "x" + h);
        atlasPending = true;
        var readback = RenderSystem.getDevice().createBuffer(() -> "pzcraft block atlas",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, readback, 0L, () -> {
            try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
                boolean layoutChanged=capturedAtlas!=texture;
                atlasRevision = link.writeAtlas(w, h, view.data());
                capturedAtlas = texture;
                if(layoutChanged){changed();PzCraftClient.LOG.info("Block atlas exported: {}x{}", w, h);}
            } catch (Throwable t) {
                failed = true;
                link.invalidate();
                PzCraftClient.LOG.error("Block atlas export failed", t);
            } finally {
                readback.close(); atlasPending = false;
            }
        }, 0);
    }

    private static void export(Minecraft mc, int cx, int cz) {
        ByteBuffer vertices = MemoryUtil.memAlloc(SceneLink.MAX_VERTICES * SceneLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var parts = new ArrayList<BlockStateModelPart>();
        var random = RandomSource.create();
        var models = mc.getModelManager().getBlockStateModelSet();
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
                        if(!state.getFluidState().isEmpty()) {
                            var fluid=new FluidCapture(vertices,(pos.getX()&~15)-ox,(pos.getY()&~15)-oy,(pos.getZ()&~15)-oz,
                                    state.getLightEmission()/15f);
                            new net.minecraft.client.renderer.block.FluidRenderer(mc.getModelManager().getFluidStateModelSet())
                                    .tesselate(mc.level,pos,layer->fluid,state,state.getFluidState());
                            fluid.finish();
                        }
                        if(state.getRenderShape()==RenderShape.INVISIBLE||state.hasBlockEntity()&&state.getRenderShape()!=RenderShape.MODEL)continue;
                        if(state.getRenderShape()!=RenderShape.MODEL)throw new IllegalStateException("Unsupported block renderer at "+pos);
                        parts.clear(); random.setSeed(state.getSeed(pos));
                        models.get(state).collectParts(random, parts);
                        for (var part : parts) {
                            for (var q : part.getQuads(null)) quad(mc, pos, q, vertices, ox, oy, oz);
                            for (var face : Direction.values()) {
                                var neighborPos = pos.relative(face);
                                var neighbor = mc.level.getBlockState(neighborPos);
                                if (state.skipRendering(neighbor, face) || neighbor.isSolidRender()) continue;
                                if (pos.getY() < 0 && (GroundBridge.virtualSolid(neighborPos.getX(), neighborPos.getY(), neighborPos.getZ())
                                        || face == Direction.UP && pos.getY() == -1 && GroundBridge.floorIntact(pos.getX(), pos.getZ()))) continue;
                                for (var q : part.getQuads(face)) quad(mc, pos, q, vertices, ox, oy, oz);
                            }
                        }
                    }
                }
            }
            vertices.flip();
            link.writeMesh(atlasRevision, ox, oy, oz, vertices);
        } finally { MemoryUtil.memFree(vertices); }
    }

    private static void quad(Minecraft mc, BlockPos pos, BakedQuad q, ByteBuffer out, int ox, int oy, int oz) {
        if (out.remaining() < 6 * SceneLink.VERTEX_BYTES) throw new IllegalStateException("Too many native block vertices");
        var material = q.materialInfo();
        var state = mc.level.getBlockState(pos);
        var tintSource = material.isTinted() ? mc.getBlockColors().getTintSource(state, material.tintIndex()) : null;
        int tint = tintSource != null ? tintSource.colorInWorld(state, mc.level, pos) : 0xFFFFFF;
        var n = new org.joml.Vector3f(q.position1()).sub(q.position0())
                .cross(new org.joml.Vector3f(q.position2()).sub(q.position0()));
        if (n.lengthSquared() < 1e-8f) n.set(q.direction().getStepX(), q.direction().getStepY(), q.direction().getStepZ());
        else n.normalize();
        for (int i : TRIANGLES) {
            var v = q.position(i);
            out.putFloat(pos.getX() - ox + v.x()).putFloat(pos.getY() - oy + v.y()).putFloat(pos.getZ() - oz + v.z());
            out.putFloat(n.x()).putFloat(n.y()).putFloat(n.z());
            out.putFloat(UVPair.unpackU(q.packedUV(i))).putFloat(UVPair.unpackV(q.packedUV(i)));
            out.putFloat((tint >> 16 & 255) / 255f).putFloat((tint >> 8 & 255) / 255f).putFloat((tint & 255) / 255f);
            out.putFloat(Math.max(material.lightEmission(),state.getLightEmission()) / 15f);
        }
    }

    private static String posText(int cx, int baseY, int cz, int x, int y, int z) {
        return "(" + ((cx << 4) + x) + "," + (baseY + y) + "," + ((cz << 4) + z) + ")";
    }
}

