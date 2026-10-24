package pzcraft.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import pzcraft.protocol.SceneLink;

/**
 * 1.20.1 version of the shared FluidCapture: captures LiquidBlockRenderer's quads (corner heights, flow, tint, both
 * sides). 1.20.1's VertexConsumer is vertex().color().uv().uv2().normal().endVertex(); four vertices make a quad.
 */
final class FluidCapture implements VertexConsumer {
    private final ByteBuffer out;
    private final float dx, dy, dz, emission;
    private final float[] p = new float[12], uv = new float[8];
    private final int[] color = new int[4], light = new int[4];
    private int count;

    FluidCapture(ByteBuffer out, int dx, int dy, int dz, float emission) {
        this.out = out; this.dx = dx; this.dy = dy; this.dz = dz; this.emission = emission;
    }

    @Override public VertexConsumer vertex(double x, double y, double z) {
        p[count * 3] = (float) x + dx; p[count * 3 + 1] = (float) y + dy; p[count * 3 + 2] = (float) z + dz;
        color[count] = -1; light[count] = 0;
        return this;
    }
    @Override public VertexConsumer color(int r, int g, int b, int a) { color[count] = a << 24 | r << 16 | g << 8 | b; return this; }
    @Override public VertexConsumer uv(float u, float v) { uv[count * 2] = u; uv[count * 2 + 1] = v; return this; }
    @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
    // Minecraft's light at the vertex (VertexLight), as 26.x's setUv2
    @Override public VertexConsumer uv2(int u, int v) { light[count] = (v & 0xF0) << 16 | (u & 0xF0); return this; }
    @Override public VertexConsumer normal(float x, float y, float z) { return this; }
    @Override public void endVertex() { if (++count == 4) { flush(); count = 0; } }
    @Override public void defaultColor(int r, int g, int b, int a) {}
    @Override public void unsetDefaultColor() {}

    void finish() { count = 0; }

    private void flush() {
        if (out.remaining() < 6 * SceneLink.VERTEX_BYTES) throw new IllegalStateException("Too many native fluid vertices");
        var normal = new org.joml.Vector3f(p[3] - p[0], p[4] - p[1], p[5] - p[2])
                .cross(p[6] - p[0], p[7] - p[1], p[8] - p[2]);
        if (normal.lengthSquared() < 1e-8) normal.set(0, 1, 0); else normal.normalize();
        for (int i : new int[] {0, 1, 2, 0, 2, 3}) out.putFloat(p[i * 3]).putFloat(p[i * 3 + 1]).putFloat(p[i * 3 + 2])
                .putFloat(normal.x).putFloat(normal.y).putFloat(normal.z).putFloat(uv[i * 2]).putFloat(uv[i * 2 + 1])
                .putFloat((color[i] >> 16 & 255) / 255f).putFloat((color[i] >> 8 & 255) / 255f).putFloat((color[i] & 255) / 255f)
                .putFloat(pzcraft.protocol.VertexLight.packCoords(emission, light[i]));
    }
}
