package pzcraft.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import pzcraft.protocol.EntityLink;

/**
 * A {@link SubmitNodeCollector} that records instead of rendering: Minecraft's own entity, block entity and particle
 * renderers (Steve with his animation, armour and held items; mobs; dropped items; falling blocks, primed TNT and moving
 * pistons; particles) submit into it, and what they submit becomes textured triangles for PZ to draw natively, lit,
 * depth-tested and shadowed in Viewpoint's scene. Text, shadows, flames, outlines and leashes are dropped (PZ draws its
 * own shadows from this geometry).
 */
final class EntityCapture implements SubmitNodeCollector {
    /** Stand-in texture for untextured geometry (lightning): a white pixel, coloured by the vertices. */
    static final Identifier WHITE = Identifier.fromNamespaceAndPath("pzcraft", "white");

    private final ByteBuffer out;
    private final ToIntFunction<Identifier> textureSlot;
    private final List<EntityLink.Batch> batches = new ArrayList<>();
    private int batchTexture = -1, batchFlags, batchFirst, vertices;
    /** Applied to everything submitted until changed (e.g. Steve's body in first person casts a shadow only). */
    int flags;
    /** Added to every position: particles and their kin are submitted relative to the camera, not to our origin. */
    private float shiftX, shiftY, shiftZ;
    private final Recorder recorder = new Recorder();
    /** Which submit calls arrived since the last {@link #takeTrace} (diagnostics for renderers that draw nothing). */
    private final java.util.LinkedHashSet<String> trace = new java.util.LinkedHashSet<>();
    /** Record submit calls only while the exporter still wants to describe this kind of entity. */
    boolean tracing;

    String takeTrace() {
        String s = String.join(",", trace);
        trace.clear();
        return s;
    }

    EntityCapture(ByteBuffer out, ToIntFunction<Identifier> textureSlot) {
        this.out = out;
        this.textureSlot = textureSlot;
    }

    void clear() {
        out.clear();
        batches.clear();
        batchTexture = -1;
        vertices = 0;
        shift(0, 0, 0);
    }

    void shift(float x, float y, float z) {
        shiftX = x; shiftY = y; shiftZ = z;
    }

    List<EntityLink.Batch> batches() {
        closeBatch();
        return batches;
    }

    int vertexCount() { return vertices; }

    // ---- batching ----

    private boolean room(int triangles) {
        return out.remaining() >= triangles * 3 * EntityLink.VERTEX_BYTES && batches.size() < EntityLink.MAX_BATCHES - 1;
    }

    private void use(int texture, int extraFlags) {
        int f = flags | extraFlags;
        if (texture == batchTexture && f == batchFlags) return;
        closeBatch();
        batchTexture = texture;
        batchFlags = f;
        batchFirst = vertices;
    }

    private void closeBatch() {
        if (batchTexture >= 0 && vertices > batchFirst) batches.add(new EntityLink.Batch(batchTexture, batchFirst, vertices - batchFirst, batchFlags));
        batchFirst = vertices;
    }

    private void vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v, int argb, float emission) {
        out.putFloat(x + shiftX).putFloat(y + shiftY).putFloat(z + shiftZ).putFloat(nx).putFloat(ny).putFloat(nz).putFloat(u).putFloat(v)
                .putFloat((argb >> 16 & 255) / 255f).putFloat((argb >> 8 & 255) / 255f).putFloat((argb & 255) / 255f)
                .putFloat((argb >>> 24) / 255f).putFloat(emission);
        vertices++;
    }

    /** A quad as two triangles: (0,1,2) and (0,2,3). Positions are already in the export origin's frame. */
    private void quad(float[] p, float[] n, float[] uv, int[] color, float[] emission) {
        if (!room(2)) return;
        for (int i : TRIANGLES) {
            vertex(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], n[i * 3], n[i * 3 + 1], n[i * 3 + 2], uv[i * 2], uv[i * 2 + 1], color[i], emission[i]);
        }
    }

    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

    /**
     * A glowing starburst (three crossed squares) centred where {@code pose} is, {@code size} model units across, emissive and
     * shadowless: the muzzle flash, drawn at the model's own muzzle bone so it follows the animation, the aim and the view.
     */
    void glowStar(com.mojang.blaze3d.vertex.PoseStack.Pose pose, float size, int argb) {
        use(textureSlot.applyAsInt(WHITE), EntityLink.FLAG_NO_SHADOW);
        float h = size / 2, t = size / 5;
        float[][] corners = {
                {-h, -h, 0, h, -h, 0, h, h, 0, -h, h, 0},   // square in XY
                {0, -h, -h, 0, -h, h, 0, h, h, 0, h, -h},   // square in YZ
                {-h, 0, -h, h, 0, -h, h, 0, h, -h, 0, h},   // square in XZ
                {-t, -h * 1.8f, 0, t, -h * 1.8f, 0, t, h * 1.8f, 0, -t, h * 1.8f, 0},   // long thin spikes along the barrel and across
                {-h * 1.8f, -t, 0, h * 1.8f, -t, 0, h * 1.8f, t, 0, -h * 1.8f, t, 0}};
        var m = pose.pose();
        for (float[] c : corners) {
            float[] p = new float[12];
            for (int i = 0; i < 4; i++) { m.transformPosition(c[i * 3], c[i * 3 + 1], c[i * 3 + 2], scratch); p[i * 3] = scratch.x; p[i * 3 + 1] = scratch.y; p[i * 3 + 2] = scratch.z; }
            quad(p, new float[] {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0}, new float[8], new int[] {argb, argb, argb, argb}, new float[] {1, 1, 1, 1});
        }
    }

    /** A textured, emissive muzzle sprite in its bone's XY plane, using one random atlas frame per confirmed shot. */
    void glowSprite(PoseStack.Pose pose, Identifier texture, float halfSize, int frame, int frames, float alpha) {
        use(textureSlot.applyAsInt(texture), EntityLink.FLAG_NO_SHADOW | EntityLink.FLAG_TRANSLUCENT | EntityLink.FLAG_ADDITIVE);
        float[] p = new float[12], corners = {-halfSize,-halfSize,0, halfSize,-halfSize,0, halfSize,halfSize,0, -halfSize,halfSize,0};
        for (int i=0;i<4;i++) {
            pose.pose().transformPosition(corners[i*3], corners[i*3+1], 0, scratch);
            p[i*3]=scratch.x; p[i*3+1]=scratch.y; p[i*3+2]=scratch.z;
        }
        float u0=(float)frame/frames, u1=(float)(frame+1)/frames;
        int color=((int)(255*Math.max(0,Math.min(1,alpha)))<<24)|0xFFFFFF;
        quad(p, new float[]{0,0,1,0,0,1,0,0,1,0,0,1}, new float[]{u0,1,u1,1,u1,0,u0,0},
                new int[]{color,color,color,color},new float[]{1,1,1,1});
    }

    /**
     * A glowing strip between two points that faces the camera (bullet tracers). Positions are relative to the export origin,
     * {@code camera} too; width in blocks; colour ARGB. Drawn emissive, without a shadow.
     */
    void glowSegment(float x0, float y0, float z0, float x1, float y1, float z1, float cx, float cy, float cz, float width, int argb) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float mx = (x0 + x1) / 2 - cx, my = (y0 + y1) / 2 - cy, mz = (z0 + z1) / 2 - cz;
        float sx = dy * mz - dz * my, sy = dz * mx - dx * mz, sz = dx * my - dy * mx;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1e-6f) return;
        float k = width / 2 / len;
        sx *= k; sy *= k; sz *= k;
        use(textureSlot.applyAsInt(WHITE), EntityLink.FLAG_NO_SHADOW);
        quad(new float[] {x0 - sx, y0 - sy, z0 - sz, x0 + sx, y0 + sy, z0 + sz, x1 + sx, y1 + sy, z1 + sz, x1 - sx, y1 - sy, z1 - sz},
                new float[] {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0}, new float[8], new int[] {argb, argb, argb, argb}, new float[] {1, 1, 1, 1});
    }

    /** Two textured blades along a confirmed ray and a camera-facing head, in the world export's coordinate frame. */
    void texturedTracer(Vec3 start, Vec3 end, Vec3 camera, float width, float faceWidth, float roll) {
        var texture = Identifier.parse("pzcraft:textures/effect/tracers2.png");
        Vec3 direction = end.subtract(start);
        float halfLength = (float)direction.length() / 2;
        if (halfLength < .001f) return;
        Vec3 centre = start.add(end).scale(.5);
        var orientation = new Quaternionf().rotateTo(0,0,-1,(float)direction.x,(float)direction.y,(float)direction.z).rotateZ(roll);
        for(int blade=0;blade<2;blade++) {
            var rotation = new Quaternionf(orientation).rotateZ(blade*(float)Math.PI/2);
            lightQuad(texture, centre, rotation, new float[]{0,width/2,halfLength, 0,width/2,-halfLength,
                    0,-width/2,-halfLength, 0,-width/2,halfLength}, new float[]{.75f,1,.75f,0,0,0,0,1});
        }
        Vec3 facing = camera.subtract(centre).normalize();
        var face = new Quaternionf().rotateTo(0,0,1,(float)facing.x,(float)facing.y,(float)facing.z).rotateZ(roll);
        float h=faceWidth/2;
        lightQuad(texture,centre,face,new float[]{-h,-h,0,-h,h,0,h,h,0,h,-h,0},new float[]{1,1,1,0,.75f,0,.75f,1});
    }

    private void lightQuad(Identifier texture, Vec3 centre, Quaternionf rotation, float[] corners, float[] uv) {
        use(textureSlot.applyAsInt(texture),EntityLink.FLAG_NO_SHADOW|EntityLink.FLAG_TRANSLUCENT|EntityLink.FLAG_ADDITIVE);
        float[] positions=new float[12], normals=new float[12];
        for(int i=0;i<4;i++) {
            scratch.set(corners[i*3],corners[i*3+1],corners[i*3+2]).rotate(rotation);
            positions[i*3]=(float)centre.x+scratch.x;positions[i*3+1]=(float)centre.y+scratch.y;positions[i*3+2]=(float)centre.z+scratch.z;
            normals[i*3+1]=1;
        }
        quad(positions,normals,uv,new int[]{-1,-1,-1,-1},new float[]{1,1,1,1});
    }

    // ---- vertices from models, custom geometry and particles (four addVertex calls per quad) ----

    private final class Recorder implements VertexConsumer {
        private final float[] p = new float[12], n = new float[12], uv = new float[8], emission = new float[4];
        private final int[] color = new int[4];
        private int count = -1;
        /** Particles carry no normals: use the quad's own facing. */
        boolean faceNormals;
        /** Light-coded emission: full block light (fire, lava, glowing particles) glows in PZ too. */
        boolean lightEmission;
        /** Every vertex glows (lightning). */
        boolean emissive;

        void start(boolean faceNormals, boolean lightEmission, boolean emissive) {
            count = -1;
            this.faceNormals = faceNormals;
            this.lightEmission = lightEmission;
            this.emissive = emissive;
        }

        private void commit() {
            if (count != 3) return;
            if (faceNormals) {
                float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2], bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
                float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > 1e-6f) for (int i = 0; i < 4; i++) { n[i * 3] = nx / len; n[i * 3 + 1] = ny / len; n[i * 3 + 2] = nz / len; }
            }
            quad(p, n, uv, color, emission);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 3) { commit(); count = -1; }
            count++;
            p[count * 3] = x; p[count * 3 + 1] = y; p[count * 3 + 2] = z;
            color[count] = -1;
            emission[count] = emissive ? 1f : 0f;
            n[count * 3] = 0; n[count * 3 + 1] = 1; n[count * 3 + 2] = 0;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { if (count >= 0) color[count] = a << 24 | r << 16 | g << 8 | b; return this; }
        @Override public VertexConsumer setColor(int argb) { if (count >= 0) color[count] = argb; return this; }
        @Override public VertexConsumer setUv(float u, float v) { if (count >= 0) { uv[count * 2] = u; uv[count * 2 + 1] = v; } return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) {
            // packed light: u = block light << 4, v = sky light << 4
            if (count >= 0 && lightEmission && (u >> 4) >= 15) emission[count] = 1f;
            return this;
        }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { if (count >= 0) { n[count * 3] = x; n[count * 3 + 1] = y; n[count * 3 + 2] = z; } return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }

        void end() {
            commit();
            count = -1;
        }
    }

    // ---- models ----

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords,
                                int overlayCoords, int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) { if (tracing) trace.add("submitModel");
        if (outlineColor != 0 || renderType.primitiveTopology() != PrimitiveTopology.QUADS) return;
        Identifier texture = textureOf(renderType);
        if (texture == null) return;
        use(textureSlot.applyAsInt(texture), 0);
        recorder.start(false, false, false);
        VertexConsumer buffer = uvMapping != null ? uvMapping.wrap(recorder) : recorder;
        model.setupAnim(state);
        model.renderToBuffer(poseStack, buffer, lightCoords, overlayCoords, tintedColor);
        recorder.end();
    }

    // ---- items (dropped items, held items, armour trims...) and blocks (primed TNT, minecart contents...) ----

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor,
                           int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType foilType) { if (tracing) trace.add("submitItem");
        if (outlineColor != 0) return;
        bakedQuads(poseStack.last(), quads.all(), tintLayers, 0f);
    }

    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers,
                                 int lightCoords, int overlayCoords, int outlineColor) { if (tracing) trace.add("submitBlockModel");
        if (outlineColor != 0) return;
        for (BlockStateModelPart part : parts) {
            bakedQuads(poseStack.last(), part.getQuads(null), tintLayers, 0f);
            for (Direction d : Direction.values()) bakedQuads(poseStack.last(), part.getQuads(d), tintLayers, 0f);
        }
    }

    // ---- Fabric's renderer API: models it turned into meshes arrive here, with the vanilla part list left empty ----

    @Override
    public void submitBlockModel(PoseStack poseStack, java.util.function.Function<net.minecraft.client.renderer.chunk.ChunkSectionLayer, RenderType> renderTypes,
                                 boolean translucent, List<BlockStateModelPart> parts, net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh mesh,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (tracing) trace.add("submitBlockModel(mesh)");
        if (outlineColor != 0) return;
        submitBlockModel(poseStack, null, parts, tintLayers, lightCoords, overlayCoords, 0);
        meshQuads(poseStack.last(), mesh, tintLayers);
    }

    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor,
                           int[] tintLayers, ItemQuads quads, net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView mesh,
                           ItemStackRenderState.FoilType foilType) {
        if (tracing) trace.add("submitItem(mesh)");
        if (outlineColor != 0) return;
        bakedQuads(poseStack.last(), quads.all(), tintLayers, 0f);
        meshQuads(poseStack.last(), mesh, tintLayers);
    }

    private final Vector3f meshNormal = new Vector3f();

    /** Fabric mesh quads: positions, normals, uvs and colours per vertex, the atlas and tint per quad. Opaque. */
    private void meshQuads(PoseStack.Pose pose, net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView mesh, int[] tintLayers) {
        if (mesh == null || mesh.size() == 0) return;
        Matrix4f m = pose.pose();
        mesh.forEach(q -> {
            use(textureSlot.applyAsInt(q.atlas().getTextureLocation()), 0);
            int tintIndex = q.tintIndex();
            int tint = tintIndex >= 0 && tintIndex < tintLayers.length ? tintLayers[tintIndex] : -1;
            float emission = q.emissive() ? 1f : 0f;
            for (int i = 0; i < 4; i++) {
                m.transformPosition(q.x(i), q.y(i), q.z(i), scratch);
                qp[i * 3] = scratch.x; qp[i * 3 + 1] = scratch.y; qp[i * 3 + 2] = scratch.z;
                if (q.hasNormal(i)) meshNormal.set(q.normalX(i), q.normalY(i), q.normalZ(i));
                else meshNormal.set(q.faceNormal());
                pose.transformNormal(meshNormal, meshNormal);
                qn[i * 3] = meshNormal.x; qn[i * 3 + 1] = meshNormal.y; qn[i * 3 + 2] = meshNormal.z;
                quv[i * 2] = q.u(i);
                quv[i * 2 + 1] = q.v(i);
                qc[i] = 0xFF000000 | multiply(q.color(i), tint);
                qe[i] = emission;
            }
            quad(qp, qn, quv, qc, qe);
        });
    }

    private static int multiply(int a, int b) {
        if (b == -1) return a;
        int r = ((a >> 16 & 255) * (b >> 16 & 255)) / 255, g = ((a >> 8 & 255) * (b >> 8 & 255)) / 255, bl = ((a & 255) * (b & 255)) / 255;
        return r << 16 | g << 8 | bl;
    }

    /**
     * Falling sand, gravel, anvils and concrete powder, and the blocks a piston is moving (with the piston head): a whole
     * block model at the entity's or the piston's current offset, tinted like the placed block would be.
     */
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState moving, int outlineColor) { if (tracing) trace.add("submitMovingBlock");
        if (outlineColor != 0 || moving.blockState.getRenderShape() != RenderShape.MODEL) return;
        Minecraft mc = Minecraft.getInstance();
        var state = moving.blockState;
        var model = mc.getModelManager().getBlockStateModelSet().get(state);
        movingParts.clear();
        random.setSeed(state.getSeed(moving.randomSeedPos));
        model.collectParts(random, movingParts);
        float emission = state.getLightEmission() / 15f;
        PoseStack.Pose pose = poseStack.last();
        for (BlockStateModelPart part : movingParts) {
            movingQuads(pose, moving, part.getQuads(null), emission);
            for (Direction d : Direction.values()) movingQuads(pose, moving, part.getQuads(d), emission);
        }
    }

    private final List<BlockStateModelPart> movingParts = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private final int[] oneTint = new int[1];

    private void movingQuads(PoseStack.Pose pose, MovingBlockRenderState moving, List<BakedQuad> quads, float emission) {
        Minecraft mc = Minecraft.getInstance();
        for (BakedQuad q : quads) {
            var material = q.materialInfo();
            int tint = -1;
            if (material.isTinted()) {
                var source = mc.getBlockColors().getTintSource(moving.blockState, material.tintIndex());
                if (source != null) tint = 0xFF000000 | source.colorInWorld(moving.blockState, moving, moving.blockPos);
            }
            oneTint[0] = tint;
            bakedQuad(pose, q, oneTint, 0, Math.max(emission, material.lightEmission() / 15f));
        }
    }

    private final float[] qp = new float[12], qn = new float[12], quv = new float[8], qe = new float[4];
    private final int[] qc = new int[4];
    private final Vector3f scratch = new Vector3f();

    private void bakedQuads(PoseStack.Pose pose, List<BakedQuad> quads, int[] tintLayers, float emission) {
        for (BakedQuad q : quads) {
            var material = q.materialInfo();
            int tintIndex = material.isTinted() && material.tintIndex() < tintLayers.length ? material.tintIndex() : -1;
            bakedQuad(pose, q, tintLayers, tintIndex, Math.max(emission, material.lightEmission() / 15f));
        }
    }

    /** One baked quad; {@code tints[tintIndex]} colours it when the index is valid (-1 = untinted). Always opaque. */
    private void bakedQuad(PoseStack.Pose pose, BakedQuad q, int[] tints, int tintIndex, float emission) {
        Matrix4f m = pose.pose();
        use(textureSlot.applyAsInt(q.materialInfo().sprite().atlasLocation()), 0);
        int tint = tintIndex >= 0 && tintIndex < tints.length ? 0xFF000000 | tints[tintIndex] : -1;
        Vector3f normal = pose.transformNormal(q.direction().getUnitVec3f(), new Vector3f());
        for (int i = 0; i < 4; i++) {
            m.transformPosition(q.position(i), scratch);
            qp[i * 3] = scratch.x; qp[i * 3 + 1] = scratch.y; qp[i * 3 + 2] = scratch.z;
            qn[i * 3] = normal.x; qn[i * 3 + 1] = normal.y; qn[i * 3 + 2] = normal.z;
            quv[i * 2] = UVPair.unpackU(q.packedUV(i));
            quv[i * 2 + 1] = UVPair.unpackV(q.packedUV(i));
            qc[i] = tint;
            qe[i] = emission;
        }
        quad(qp, qn, quv, qc, qe);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) { if (tracing) trace.add("submitCustomGeometry");
        if (renderType.primitiveTopology() != PrimitiveTopology.QUADS) return;
        Identifier texture = textureOf(renderType);
        boolean untextured = texture == null;
        // Untextured geometry (lightning bolts) is light itself: white, coloured by its vertices, glowing, see-through.
        use(textureSlot.applyAsInt(untextured ? WHITE : texture),
                untextured ? EntityLink.FLAG_NO_SHADOW | EntityLink.FLAG_TRANSLUCENT : 0);
        recorder.start(untextured, false, untextured);
        renderer.render(poseStack.last(), recorder);
        recorder.end();
    }

    // ---- particles: camera-relative billboards, per atlas layer ----

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) { if (tracing) trace.add("submitQuadParticleGroup");
        for (SingleQuadParticle.Layer layer : particles.layers()) {
            use(textureSlot.applyAsInt(layer.textureAtlasLocation()),
                    EntityLink.FLAG_NO_SHADOW | (layer.translucent() ? EntityLink.FLAG_TRANSLUCENT : 0));
            recorder.start(true, true, false);
            particles.buildLayer(layer, recorder);
            recorder.end();
        }
    }

    // ---- not geometry PZ should draw ----

    @Override public OrderedSubmitNodeCollector order(int order) { return this; }
    @Override public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) { if (tracing) trace.add("submitShadow");}
    @Override public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough,
                                        int lightCoords, CameraRenderState camera) { if (tracing) trace.add("submitNameTag");}
    @Override public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                                     Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) { if (tracing) trace.add("submitText");}
    @Override public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                               Font.DisplayMode displayMode, int lightCoords) { if (tracing) trace.add("submitTextBackground");}
    @Override public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
        if(tracing)trace.add("submitFlame");
        var sprites=Minecraft.getInstance().getAtlasManager();
        var fire0=sprites.get(net.minecraft.client.resources.model.ModelBakery.FIRE_0);
        var fire1=sprites.get(net.minecraft.client.resources.model.ModelBakery.FIRE_1);
        poseStack.pushPose();
        float scale=state.boundingBoxWidth*1.4f,height=state.boundingBoxHeight/scale;
        poseStack.scale(scale,scale,scale);poseStack.rotate(rotation);poseStack.translate(0,0,.3f-(int)height*.02f);
        float half=.5f,y=0,z=0;int layer=0;
        use(textureSlot.applyAsInt(fire0.atlasLocation()),EntityLink.FLAG_NO_SHADOW|EntityLink.FLAG_FLAME);
        recorder.start(false,false,true);
        while(height>0) {
            var sprite=(layer%2==0?fire0:fire1);float u0=sprite.getU0(),u1=sprite.getU1();
            if(layer/2%2==0){float t=u0;u0=u1;u1=t;}
            float[] xs={-half,half,half,-half},ys={-y,-y,1.4f-y,1.4f-y},us={u1,u0,u0,u1},vs={sprite.getV1(),sprite.getV1(),sprite.getV0(),sprite.getV0()};
            for(int i=0;i<4;i++)recorder.addVertex(poseStack.last(),xs[i],ys[i],z).setColor(-1).setUv(us[i],vs[i]).setNormal(poseStack.last(),0,0,1);
            height-=.45f;y-=.45f;half*=.9f;z-=.03f;layer++;
        }
        recorder.end();poseStack.popPose();
    }
    @Override public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) { if (tracing) trace.add("submitLeash");}
    @Override public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                                     int lightCoords, int overlayCoords, int tintedColor,
                                                     ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) { if (tracing) trace.add("submitCrumblingOverlay");}
    @Override public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent) {
        if(progress<0||progress>9)return;
        int slot=textureSlot.applyAsInt(Identifier.withDefaultNamespace("textures/block/destroy_stage_"+progress+".png"));
        PoseStack.Pose pose=poseStack.last();
        use(slot,EntityLink.FLAG_NO_SHADOW|EntityLink.FLAG_DECAL);
        for(var part:parts) {
            var quads=new ArrayList<BakedQuad>(part.getQuads(null));
            for(Direction d:Direction.values())quads.addAll(part.getQuads(d));
            for(var q:quads) {
                var normal=pose.transformNormal(q.direction().getUnitVec3f(),new Vector3f());
                for(int i=0;i<4;i++) {
                    var local=q.position(i);pose.pose().transformPosition(local,scratch);
                    qp[i*3]=scratch.x+normal.x*.002f;qp[i*3+1]=scratch.y+normal.y*.002f;qp[i*3+2]=scratch.z+normal.z*.002f;
                    qn[i*3]=normal.x;qn[i*3+1]=normal.y;qn[i*3+2]=normal.z;
                    switch(q.direction().getAxis()) {
                        case X -> {quv[i*2]=local.z();quv[i*2+1]=1-local.y();}
                        case Y -> {quv[i*2]=local.x();quv[i*2+1]=local.z();}
                        case Z -> {quv[i*2]=local.x();quv[i*2+1]=1-local.y();}
                    }
                    qc[i]=-1;qe[i]=0;
                }
                quad(qp,qn,quv,qc,qe);
            }
        }
    }
    @Override public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) { if (tracing) trace.add("submitShapeOutline");}
    @Override public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) { if (tracing) trace.add("submitGizmoPrimitives");}

    // ---- which texture a render type samples (RenderType -> RenderSetup -> "Sampler0") ----

    private static Field stateField, texturesField;
    private static Method locationMethod;

    static @Nullable Identifier textureOf(RenderType type) {
        try {
            if (stateField == null) {
                stateField = RenderType.class.getDeclaredField("state");
                stateField.setAccessible(true);
            }
            Object setup = stateField.get(type);
            if (texturesField == null) {
                texturesField = setup.getClass().getDeclaredField("textures");
                texturesField.setAccessible(true);
            }
            Map<?, ?> textures = (Map<?, ?>) texturesField.get(setup);
            Object binding = textures.get("Sampler0");
            if (binding == null) return null;
            if (locationMethod == null) {
                locationMethod = binding.getClass().getDeclaredMethod("location");
                locationMethod.setAccessible(true);
            }
            return (Identifier) locationMethod.invoke(binding);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

