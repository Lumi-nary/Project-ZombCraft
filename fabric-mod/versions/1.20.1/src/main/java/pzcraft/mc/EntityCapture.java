package pzcraft.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import pzcraft.mc.mixin.RenderTypeState120Accessor;
import pzcraft.mc.mixin.RenderTexture120Accessor;
import pzcraft.mc.mixin.TextureLocation120Invoker;
import pzcraft.protocol.EntityLink;
import pzcraft.protocol.VertexLight;

/** Minecraft 1.20.1's immediate renderer into the same native triangle transport as 26.x. */
final class EntityCapture implements MultiBufferSource {
    private record Key(RenderType type, int flags) {}
    private final Map<Key, Consumer> consumers = new LinkedHashMap<>();
    private final ToIntFunction<ResourceLocation> texture;
    int flags;

    EntityCapture(ToIntFunction<ResourceLocation> texture) { this.texture = texture; }
    private final Map<String, Consumer> raw = new LinkedHashMap<>();
    void clear() { consumers.clear(); raw.clear(); flags = 0; }

    /** Quads drawn straight with one texture, no render type (the mining cracks' strip, BlockCracks120). */
    VertexConsumer raw(ResourceLocation id) {
        int f = flags;
        return raw.computeIfAbsent(id + "#" + f, key -> new Consumer(VertexFormat.Mode.QUADS, texture.applyAsInt(id), f));
    }

    @Override public VertexConsumer getBuffer(RenderType type) {
        return consumers.computeIfAbsent(new Key(type, flags), key -> {
            ResourceLocation id = null;
            if (type instanceof RenderTypeState120Accessor access) {
                var state = ((RenderTexture120Accessor)(Object)access.pzcraft$state()).pzcraft$texture();
                id = ((TextureLocation120Invoker)state).pzcraft$location().orElse(null);
            }
            // Line/debug passes have no texture and no surface in the native g-buffer.
            int slot = id == null ? -1 : texture.applyAsInt(id);
            return new Consumer(type.mode(), slot, key.flags());
        });
    }

    List<EntityLink.Batch> finish(ByteBuffer out) {
        List<EntityLink.Batch> batches = new ArrayList<>();
        var all = new ArrayList<Consumer>(consumers.values());
        all.addAll(raw.values());
        for (Consumer c : all) {
            if (c.slot < 0 || c.data.position() == 0) continue;
            if (batches.size() == EntityLink.MAX_BATCHES || out.remaining() < c.data.position())
                throw new IllegalStateException("Native entity transport capacity exceeded");
            int first = out.position() / EntityLink.VERTEX_BYTES;
            c.data.flip(); out.put(c.data);
            batches.add(new EntityLink.Batch(c.slot, first, out.position() / EntityLink.VERTEX_BYTES - first,
                    c.flags | (c.translucent ? EntityLink.FLAG_TRANSLUCENT : 0)));
        }
        return batches;
    }

    private static final class Consumer implements VertexConsumer {
        final VertexFormat.Mode mode;
        final int slot, flags;
        ByteBuffer data = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        final float[][] v = new float[4][13];
        int count, total;
        boolean translucent, fixedColor;
        int red=255, green=255, blue=255, alpha=255;
        Consumer(VertexFormat.Mode mode, int slot, int flags) { this.mode=mode;this.slot=slot;this.flags=flags; }
        private float[] current() { return v[count]; }
        @Override public VertexConsumer vertex(double x,double y,double z) {
            float[] a=current(); a[0]=(float)x;a[1]=(float)y;a[2]=(float)z;
            a[3]=0;a[4]=1;a[5]=0;a[6]=a[7]=a[12]=0;
            color(red,green,blue,alpha);return this;
        }
        @Override public VertexConsumer color(int r,int g,int b,int a) {
            float[] v=current();v[8]=r/255f;v[9]=g/255f;v[10]=b/255f;v[11]=a/255f;
            if(a<255)translucent=true;return this;
        }
        @Override public VertexConsumer uv(float u,float v) { current()[6]=u;current()[7]=v;return this; }
        @Override public VertexConsumer overlayCoords(int u,int v) { return this; }
        @Override public VertexConsumer uv2(int u,int v) { current()[12]=VertexLight.packCoords(0,(v&0xffff)<<16|(u&0xffff));return this; }
        @Override public VertexConsumer normal(float x,float y,float z) { current()[3]=x;current()[4]=y;current()[5]=z;return this; }
        @Override public void defaultColor(int r,int g,int b,int a) { red=r;green=g;blue=b;alpha=a;fixedColor=true; }
        @Override public void unsetDefaultColor() { red=green=blue=alpha=255;fixedColor=false; }
        @Override public void endVertex() {
            if(slot<0)return;
            count++;total++;
            if(mode==VertexFormat.Mode.QUADS && count==4) { emit(0,1,2);emit(0,2,3);count=0; }
            else if(mode==VertexFormat.Mode.TRIANGLES && count==3) { emit(0,1,2);count=0; }
            else if((mode==VertexFormat.Mode.TRIANGLE_STRIP || mode==VertexFormat.Mode.TRIANGLE_FAN) && count==3) {
                if(mode==VertexFormat.Mode.TRIANGLE_FAN || (total&1)==1)emit(0,1,2);else emit(1,0,2);
                if(mode==VertexFormat.Mode.TRIANGLE_STRIP)System.arraycopy(v[1],0,v[0],0,13);
                System.arraycopy(v[2],0,v[1],0,13);count=2;
            } else if(count==4)count=0;
        }
        private void emit(int a,int b,int c) {
            int need=3*EntityLink.VERTEX_BYTES;
            if(data.remaining()<need) {
                if(data.capacity()>=EntityLink.MAX_VERTICES*EntityLink.VERTEX_BYTES)throw new IllegalStateException("Entity too large");
                ByteBuffer bigger=ByteBuffer.allocate(Math.min(EntityLink.MAX_VERTICES*EntityLink.VERTEX_BYTES,data.capacity()*2)).order(ByteOrder.LITTLE_ENDIAN);
                data.flip();bigger.put(data);data=bigger;
            }
            for(int i:new int[]{a,b,c})for(float f:v[i])data.putFloat(f);
        }
    }
}
