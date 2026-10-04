package pzcraft.mc;

import java.nio.ByteBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import pzcraft.protocol.SceneLink;

/** Captures vanilla fluid tessellation, including corner heights, flows, tint and both sides. */
final class FluidCapture implements VertexConsumer {
    private final ByteBuffer out;
    private final float dx,dy,dz,emission;
    private final float[] p=new float[12],uv=new float[8];
    private final int[] color=new int[4];
    private int count;
    FluidCapture(ByteBuffer out,int dx,int dy,int dz,float emission){this.out=out;this.dx=dx;this.dy=dy;this.dz=dz;this.emission=emission;}
    public VertexConsumer addVertex(float x,float y,float z){
        if(count==4){flush();count=0;}p[count*3]=x+dx;p[count*3+1]=y+dy;p[count*3+2]=z+dz;color[count]=-1;count++;return this;
    }
    public VertexConsumer setColor(int r,int g,int b,int a){return setColor(a<<24|r<<16|g<<8|b);}
    public VertexConsumer setColor(int c){color[count-1]=c;return this;}
    public VertexConsumer setUv(float u,float v){uv[(count-1)*2]=u;uv[(count-1)*2+1]=v;return this;}
    public VertexConsumer setUv1(int u,int v){return this;}
    public VertexConsumer setUv2(int u,int v){return this;}
    public VertexConsumer setUv3(float u,float v){return this;}
    public VertexConsumer setNormal(float x,float y,float z){return this;}
    public VertexConsumer setLineWidth(float width){return this;}
    void finish(){if(count==4)flush();count=0;}
    private void flush(){
        if(out.remaining()<6*SceneLink.VERTEX_BYTES)throw new IllegalStateException("Too many native fluid vertices");
        var normal=new org.joml.Vector3f(p[3]-p[0],p[4]-p[1],p[5]-p[2])
                .cross(p[6]-p[0],p[7]-p[1],p[8]-p[2]);
        if(normal.lengthSquared()<1e-8)normal.set(0,1,0);else normal.normalize();
        for(int i:new int[]{0,1,2,0,2,3})out.putFloat(p[i*3]).putFloat(p[i*3+1]).putFloat(p[i*3+2])
                .putFloat(normal.x).putFloat(normal.y).putFloat(normal.z).putFloat(uv[i*2]).putFloat(uv[i*2+1])
                .putFloat((color[i]>>16&255)/255f).putFloat((color[i]>>8&255)/255f).putFloat((color[i]&255)/255f).putFloat(emission);
    }
}

