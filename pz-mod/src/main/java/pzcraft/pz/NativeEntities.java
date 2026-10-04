package pzcraft.pz;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import pzcraft.protocol.EntityLink;

/**
 * Steve, mobs, dropped items, falling blocks, moving pistons and particles, drawn by PZ the way {@link NativeBlocks}
 * draws blocks: Minecraft re-sends their triangles every frame through {@link EntityLink}; here they go into Viewpoint's
 * g-buffer (so PZ lights them, fogs them and hides them behind walls) and, except particles, into its sun-shadow
 * cascades (so they cast shadows). Partly transparent batches (smoke, splashes, lightning) cover a share of their pixels
 * that changes every frame, which Viewpoint's temporal anti-aliasing blends into see-through. Runs inside the native
 * block hooks, with their GL state, program and uniforms already set up.
 */
public final class NativeEntities {
    private static EntityLink link;
    private static long frameRevision;
    private static EntityLink.Frame frame;
    private static final long[] textureRevision = new long[EntityLink.TEXTURE_SLOTS];
    private static final int[] textureKind = new int[EntityLink.TEXTURE_SLOTS];
    private static final int[] textures = new int[EntityLink.TEXTURE_SLOTS];
    private static int vao, vbo;
    private static boolean failed;
    private static int failures;
    private static volatile int lastTriangles, lastBatches, drawnBatches, shadowBatches, translucentBatches;
    private static volatile int handBatches,decals,flames,playerBatches;
    private static boolean latched;
    private static int handProgram;
    private static final java.nio.FloatBuffer projection=org.lwjgl.BufferUtils.createFloatBuffer(16);

    private NativeEntities() {}

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("failed", failed);
        m.put("triangles", lastTriangles);
        m.put("batches", lastBatches);
        m.put("drawnBatches", drawnBatches);
        m.put("shadowBatches", shadowBatches);
        m.put("translucentBatches", translucentBatches);
        int loaded = 0;
        for (long r : textureRevision) if (r != 0) loaded++;
        m.put("textures", loaded);
        m.put("handBatches",handBatches);m.put("decals",decals);m.put("flames",flames);
        m.put("playerBatches",playerBatches);
        m.put("frameAgeMs",frame==null?0:(System.nanoTime()-frame.revision())/1e6);
        return m;
    }

    /** Pick up new textures and this frame's geometry (cheap when nothing changed). Render thread. */
    private static boolean update() {
        if (failed) return false;
        if(latched)return frame!=null && LinkService.minecraftReady();
        try {
            if (link == null) link = EntityLink.open(false);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            for (int slot = 0; slot < EntityLink.TEXTURE_SLOTS; slot++) {
                EntityLink.Texture t = link.readTexture(slot, textureRevision[slot]);
                if (t != null) upload(t);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            EntityLink.Frame next = link.readFrame(frameRevision);
            if (next != null) {
                if (vao == 0) {
                    int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                    int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                    vao = GL30.glGenVertexArrays();
                    vbo = GL15.glGenBuffers();
                    GL30.glBindVertexArray(vao);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                    int[] sizes = {3, 3, 2, 4, 1}; // position, normal, uv, rgba, emission
                    int offset = 0;
                    for (int i = 0; i < sizes.length; i++) {
                        GL20.glEnableVertexAttribArray(i);
                        GL20.glVertexAttribPointer(i, sizes[i], GL11.GL_FLOAT, false, EntityLink.VERTEX_BYTES, (long) offset);
                        offset += sizes[i] * 4;
                    }
                    GL30.glBindVertexArray(previousVao);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                }
                int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, next.vertices(), GL15.GL_STREAM_DRAW);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
                frame = next;
                frameRevision = next.revision();
                lastTriangles = next.vertexCount() / 3;
                lastBatches = next.batches().size();
            }
            return frame != null && LinkService.minecraftReady();
        } catch (Throwable t) {
            if (++failures >= 3) failed = true;
            Log.error("native entities failed (" + failures + "/3)", t);
            return false;
        }
    }
    /** Latch geometry and its camera together, once per Viewpoint frame. Shadow and colour passes reuse it. */
    public static void beginFrame() { latched=false;update();latched=true; }
    static pzcraft.protocol.PlayerState camera() {
        return NativeBlocks.wanted() && frame!=null && frame.pose().cameraValid && !failed && System.nanoTime()-frame.revision()<250_000_000L ? frame.pose() : null;
    }

    private static void upload(EntityLink.Texture t) {
        int slot = t.slot();
        textureRevision[slot] = t.revision();
        textureKind[slot] = t.kind();
        if (t.revision() == 0 || t.kind() != EntityLink.KIND_PIXELS) return;
        if (textures[slot] == 0) textures[slot] = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[slot]);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x812F);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x812F);
        ByteBuffer pixels = t.pixels();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, t.width(), t.height(), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        Log.info("native entity texture " + slot + " uploaded: " + t.width() + "x" + t.height());
    }

    private static int glTexture(int slot, int blockAtlas) {
        if (slot < 0 || slot >= EntityLink.TEXTURE_SLOTS || textureRevision[slot] == 0) return 0;
        return textureKind[slot] == EntityLink.KIND_BLOCK_ATLAS ? blockAtlas : textures[slot];
    }

    /**
     * G-buffer pass: the block program is bound with its camera uniforms. {@code offset}/{@code origin} are that program's
     * uOffset / uWorldOrigin locations; scene origin (sx, sy, sz) as for blocks. Unit 0 is the texture unit.
     */
    static void drawGbuffer(double sx, double sy, double sz, int blockAtlas, int offset, int origin, int dither,int playerMotion) {
        if (!update()) return;
        EntityLink.Frame f = frame;
        GL20.glUniform3f(offset, (float) (-f.x() - sx), (float) (f.y() - sy), (float) (-f.z() - sz));
        GL20.glUniform3f(origin, f.x(), f.y(), f.z());
        GL30.glBindVertexArray(vao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int drawn = 0, see = 0, decalCount=0,flameCount=0,players=0;
        boolean dithering = false;
        for (EntityLink.Batch b : f.batches()) {
            if ((b.flags() & (EntityLink.FLAG_SHADOW_ONLY|EntityLink.FLAG_HAND)) != 0) continue;
            int texture = glTexture(b.texture(), blockAtlas);
            if (texture == 0) continue;
            boolean translucent = (b.flags() & EntityLink.FLAG_TRANSLUCENT) != 0;
            if (translucent != dithering) { GL20.glUniform1i(dither, translucent ? 1 : 0); dithering = translucent; }
            boolean player=(b.flags()&EntityLink.FLAG_PLAYER)!=0;
            GL20.glUniform1i(playerMotion,player?1:0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, b.first(), b.count());
            drawn++;
            if((b.flags()&EntityLink.FLAG_DECAL)!=0)decalCount++;
            if((b.flags()&EntityLink.FLAG_FLAME)!=0)flameCount++;
            if(player)players++;
            if (translucent) see++;
        }
        if (dithering) GL20.glUniform1i(dither, 0);
        GL20.glUniform1i(playerMotion,0);
        drawnBatches = drawn;
        translucentBatches = see;
        decals=decalCount;
        flames=flameCount;
        playerBatches=players;
    }

    /** Sun-shadow cascade: the shadow program is bound with this cascade's matrix; Steve too, particles not. */
    static void drawShadow(double sx, double sy, double sz, int blockAtlas, int offset) {
        if (!update()) return;
        EntityLink.Frame f = frame;
        GL20.glUniform3f(offset, (float) (-f.x() - sx), (float) (f.y() - sy), (float) (-f.z() - sz));
        GL30.glBindVertexArray(vao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int drawn = 0;
        for (EntityLink.Batch b : f.batches()) {
            if ((b.flags() & (EntityLink.FLAG_NO_SHADOW | EntityLink.FLAG_TRANSLUCENT | EntityLink.FLAG_HAND)) != 0) continue;
            int texture = glTexture(b.texture(), blockAtlas);
            if (texture == 0) continue;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, b.first(), b.count());
            drawn++;
        }
        shadowBatches = drawn;
    }

    /** The hand is camera-space geometry, with a fresh depth buffer, after the opaque scene and before the HUD. */
    static void drawHands(int width,int height) {
        if(frame==null || failed || !LinkService.minecraftReady())return;
        boolean any=frame.batches().stream().anyMatch(b -> (b.flags()&EntityLink.FLAG_HAND)!=0);
        if(!any){handBatches=0;return;}
        int oldVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),oldProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        try {
            if(handProgram==0) {
                int vs=shader(GL20.GL_VERTEX_SHADER,"""
                    #version 330
                    layout(location=0) in vec3 aPosition;
                    layout(location=1) in vec3 aNormal;
                    layout(location=2) in vec2 aUv;
                    layout(location=3) in vec4 aTint;
                    layout(location=4) in float aEmission;
                    uniform mat4 uProjection;
                    out vec2 uv; out vec4 tint;
                    void main(){gl_Position=uProjection*vec4(aPosition,1);uv=aUv;
                        float shade=.7+.3*max(0.,dot(normalize(aNormal),normalize(vec3(.2,1.,.5))));
                        tint=vec4(aTint.rgb*mix(shade,1.,clamp(aEmission,0.,1.)),aTint.a);}
                    """);
                int fs=shader(GL20.GL_FRAGMENT_SHADER,"""
                    #version 330
                    uniform sampler2D uTexture;uniform float uAlphaCutoff;in vec2 uv;in vec4 tint;out vec4 color;
                    void main(){color=texture(uTexture,uv)*tint;if(color.a<uAlphaCutoff)discard;}
                    """);
                handProgram=GL20.glCreateProgram();GL20.glAttachShader(handProgram,vs);GL20.glAttachShader(handProgram,fs);GL20.glLinkProgram(handProgram);
                GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);
                if(GL20.glGetProgrami(handProgram,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(handProgram));
            }
            GL20.glUseProgram(handProgram);GL20.glUniform1i(GL20.glGetUniformLocation(handProgram,"uTexture"),0);
            float fov=frame.handFov();if(fov<10||fov>170)fov=70;
            float t=(float)(1/Math.tan(Math.toRadians(fov)/2)),aspect=(float)width/height;
            projection.clear();projection.put(new float[]{t/aspect,0,0,0, 0,t,0,0, 0,0,-1.001f,-1, 0,0,-.10005f,0}).flip();
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(handProgram,"uProjection"),false,projection);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_LEQUAL);GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glClearDepth(1);GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL30.glBindVertexArray(vao);int drawn=0;
            // Opaque hands establish depth first; flashes blend over them and do not occlude later effects.
            for(int pass=0;pass<2;pass++)for(var b:frame.batches())if((b.flags()&EntityLink.FLAG_HAND)!=0) {
                boolean translucent=(b.flags()&EntityLink.FLAG_TRANSLUCENT)!=0;
                if(translucent!=(pass==1))continue;
                int texture=glTexture(b.texture(),NativeBlocks.atlasTexture());if(texture==0)continue;
                if(translucent){GL11.glEnable(GL11.GL_BLEND);GL11.glBlendFunc(GL11.GL_SRC_ALPHA,
                        (b.flags()&EntityLink.FLAG_ADDITIVE)!=0?GL11.GL_ONE:GL11.GL_ONE_MINUS_SRC_ALPHA);}
                else GL11.glDisable(GL11.GL_BLEND);
                GL11.glDepthMask(!translucent);
                GL20.glUniform1f(GL20.glGetUniformLocation(handProgram,"uAlphaCutoff"),translucent?.001f:.1f);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glDrawArrays(GL11.GL_TRIANGLES,b.first(),b.count());drawn++;
            }
            GL11.glDepthMask(true);GL11.glDisable(GL11.GL_BLEND);
            handBatches=drawn;
        } catch(Throwable t){failed=true;Log.error("native hands failed",t);}
        finally {GL30.glBindVertexArray(oldVao);GL20.glUseProgram(oldProgram);}
    }
    private static int shader(int type,String source) {
        int s=GL20.glCreateShader(type);GL20.glShaderSource(s,source);GL20.glCompileShader(s);
        if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(s));return s;
    }
}

