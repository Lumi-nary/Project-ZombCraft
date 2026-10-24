package pzcraft.mc;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.PoseStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import pzcraft.protocol.EntityLink;
import pzcraft.protocol.PlayerState;

/** 1.20.1 immediate entity/block-entity renderers captured as native PZ geometry. Hand/particles remain video effects. */
public final class EntitySceneExporter {
    private record Texture(int slot,int gl) {}
    private static final Map<ResourceLocation,Texture> textures=new HashMap<>();
    private static EntityLink link;
    private static ByteBuffer vertices;
    private static EntityCapture capture;
    private static boolean capturing,ready,failed,projectionSaved;
    private static int failures;
    private static long reported;

    public static boolean capturing() { return capturing; }
    public static boolean ready() { return ready && !failed; }

    static void frame(Minecraft mc,boolean thirdPerson,PlayerState poseState) {
        if(failed || mc.player==null || mc.level==null)return;
        try {
            if(link==null) {
                link=EntityLink.open(true);
                vertices=MemoryUtil.memAlloc(EntityLink.MAX_VERTICES*EntityLink.VERTEX_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                capture=new EntityCapture(id -> texture(mc,id));
            }
            vertices.clear();capture.clear();capturing=true;
            // The capture runs after the HUD, whose projection is orthographic. Renderers that read it to tell the world
            // from the inventory's paper doll (Viltrumite Flight's flight pose: m33 > .5 means GUI) took Steve for the
            // paper doll and dropped their poses: give them a world projection while capturing.
            com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix();
            projectionSaved=true;
            com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setPerspective(
                    (float)Math.toRadians(70),Math.max(.1f,(float)mc.getWindow().getWidth()/Math.max(1,mc.getWindow().getHeight())),.05f,1024f),
                    com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
            var player=mc.player;
            float pt=mc.getFrameTime();
            int ox=Mth.floor(player.getX()),oy=Mth.floor(player.getY()),oz=Mth.floor(player.getZ());
            var dispatcher=mc.getEntityRenderDispatcher();
            capture.flags=EntityLink.FLAG_PLAYER|(thirdPerson?0:EntityLink.FLAG_SHADOW_ONLY);
            render(mc,player,pt,ox,oy,oz);
            capture.flags=0;
            for(Entity e:mc.level.entitiesForRendering()) {
                if(e==player || e.distanceToSqr(player)>48*48)continue;
                if((ActorProxies.isProxy(e) || e.isInvisible()) && !e.displayFireAnimation())continue;
                render(mc,e,pt,ox,oy,oz);
            }
            var blockDispatcher=mc.getBlockEntityRenderDispatcher();
            int cx=player.blockPosition().getX()>>4,cz=player.blockPosition().getZ()>>4;
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
                var chunk=mc.level.getChunkSource().getChunk(cx+dx,cz+dz,ChunkStatus.FULL,false);
                if(chunk==null)continue;
                for(var be:chunk.getBlockEntities().values()) {
                    BlockPos pos=be.getBlockPos();
                    if(pos.distSqr(player.blockPosition())>48*48)continue;
                    PoseStack pose=new PoseStack();pose.translate(pos.getX()-ox,pos.getY()-oy,pos.getZ()-oz);
                    blockDispatcher.render(be,pt,pose,capture);
                }
            }
            BlockCracks120.submit(mc,capture,ox,oy,oz);
            GunFeel.submitTracers(capture,mc.gameRenderer.getMainCamera().getPosition(),ox,oy,oz);
            var batches=capture.finish(vertices);vertices.flip();
            link.writeFrame(ox,oy,oz,batches,vertices,poseState,70f);
            ready=true;failures=0;
            if(System.nanoTime()-reported>10_000_000_000L) {
                reported=System.nanoTime();
                PzCraftMain.LOG.info("Native entities: {} triangles, {} batches, thirdPerson={}",vertices.remaining()/EntityLink.VERTEX_BYTES/3,batches.size(),thirdPerson);
            }
        } catch(Throwable t) {
            ready=false;
            if(link!=null)try { link.writeFrame(Mth.floor(mc.player.getX()),Mth.floor(mc.player.getY()),Mth.floor(mc.player.getZ()),java.util.List.of(),ByteBuffer.allocate(0),poseState,70f); }catch(Exception ignored){}

            if(++failures>=3)failed=true;
            PzCraftMain.LOG.error("Native entity export failed ({}/3); using video entities",failures,t);
        } finally {
            capturing=false;
            if(projectionSaved){projectionSaved=false;com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix();}
        }
    }

    private static void render(Minecraft mc,Entity e,float pt,int ox,int oy,int oz) {
        var d=mc.getEntityRenderDispatcher();
        double x=Mth.lerp(pt,e.xo,e.getX())-ox,y=Mth.lerp(pt,e.yo,e.getY())-oy,z=Mth.lerp(pt,e.zo,e.getZ())-oz;
        d.render(e,x,y,z,Mth.lerp(pt,e.yRotO,e.getYRot()),pt,new PoseStack(),capture,d.getPackedLightCoords(e,pt));
    }

    private static int texture(Minecraft mc,ResourceLocation id) {
        var source=mc.getTextureManager().getTexture(id);
        int gl=source.getId();
        Texture cached=textures.get(id);
        if(cached!=null && cached.gl()==gl)return cached.slot();
        if(cached==null && textures.size()>=EntityLink.TEXTURE_SLOTS)throw new IllegalStateException("Too many entity textures");
        int slot=cached==null?textures.size():cached.slot();
        if(id.equals(TextureAtlas.LOCATION_BLOCKS)) {
            link.writeTexture(slot,EntityLink.KIND_BLOCK_ATLAS,0,0,null);
        } else {
            int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(gl);
            ByteBuffer pixels=null;
            try {
                int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
                int h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
                if(w<=0 || h<=0)throw new IllegalStateException("Empty entity texture "+id);
                int size=Math.multiplyExact(Math.multiplyExact(w,h),4);
                if(size>link.textureBytesFree())throw new IllegalStateException("Entity texture space full");
                pixels=MemoryUtil.memAlloc(size);
                GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT,4);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                link.writeTexture(slot,EntityLink.KIND_PIXELS,w,h,pixels);
            } finally { if(pixels!=null)MemoryUtil.memFree(pixels);GlStateManager._bindTexture(previous); }
        }
        textures.put(id,new Texture(slot,gl));return slot;
    }
    private EntitySceneExporter() {}
}
