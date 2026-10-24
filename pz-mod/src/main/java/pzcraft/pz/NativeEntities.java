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
        m.put("carCorrection", carCorrection);
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
            // Previous geometry is already in the VBO. Reuse the upload copy rather than allocating native memory at FPS rate.
            EntityLink.Frame next = link.readFrame(frameRevision, frame == null ? null : frame.vertices());
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
    /** Once per PZ frame: take Minecraft's newest entities and hands, then hold them for the frame; while PZ is paused keep
     *  the last ones (PauseFreeze). */
    private static pzcraft.protocol.Cars.Seat renderSeat = pzcraft.protocol.Cars.Seat.NONE;
    private static volatile java.util.List<Double> carCorrection = java.util.List.of(0.0, 0.0, 0.0);
    public static void beginFrame() { if(frame!=null&&PauseFreeze.active()){latched=true;return;} latched=false;update();renderSeat=CarBridge.renderSeat();latched=true; }

    /** Move only Steve's batches to PZ's current passenger position, including the matching sun shadow. */
    private static void entityOffset(EntityLink.Frame f, boolean player, double sx, double sy, double sz, int offset) {
        double dx = 0, dy = 0, dz = 0;
        if (player && renderSeat.inCar()) {
            dx = renderSeat.x() - f.pose().x;
            dy = renderSeat.y() - f.pose().y; // seat entity is +.6, but its passenger attachment cancels that height
            dz = renderSeat.z() - f.pose().z;
        }
        if (player) carCorrection = java.util.List.of(dx, dy, dz);
        GL20.glUniform3f(offset, (float) (-f.x() - sx - dx), (float) (f.y() - sy + dy), (float) (-f.z() - sz - dz));
    }
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
        int fw = Math.max(t.fullWidth(), t.width()), fh = Math.max(t.fullHeight(), t.height());
        if (fw == t.width() && fh == t.height()) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fw, fh, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            Log.info("native entity texture " + slot + " uploaded: " + fw + "x" + fh);
            return;
        }
        // Minecraft sent only the used top-left part of an atlas (large item sets make it 8192x8192); UVs are relative to
        // the full size, and every pixel outside the part is 0.
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, fw, fh, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, t.width(), t.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        clear(t.width(), 0, fw - t.width(), t.height());
        clear(0, t.height(), fw, fh - t.height());
        Log.info("native entity texture " + slot + " uploaded: " + fw + "x" + fh + " (sent " + t.width() + "x" + t.height() + ")");
    }

    private static ByteBuffer zeros;

    /** Fill a {@code w x h} region of the bound texture with 0, a few rows at a time from one small buffer. */
    private static void clear(int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (zeros == null) zeros = org.lwjgl.BufferUtils.createByteBuffer(4 << 20);
        int rows = Math.max(1, Math.min(h, zeros.capacity() / (w * 4)));
        for (int r = 0; r < h; r += rows) {
            int n = Math.min(rows, h - r);
            zeros.limit(n * w * 4).position(0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y + r, w, n, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, zeros);
        }
        zeros.clear();
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
            entityOffset(f, player, sx, sy, sz, offset);
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
            entityOffset(f, (b.flags() & EntityLink.FLAG_PLAYER) != 0, sx, sy, sz, offset);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, b.first(), b.count());
            drawn++;
        }
        shadowBatches = drawn;
    }

    /** PZ's light on Steve's square (white until known), and a gain to match Viewpoint's picture; the hand is lit with it. */
    private static final float[] handLight = {1f, 1f, 1f};
    static volatile float handGain = 1f;

    /** Game thread, once per frame: the light PZ gives the square Steve stands on (what it lights its own player with). */
    static void lightOn(zombie.characters.IsoPlayer player) {
        zombie.iso.IsoGridSquare sq = player == null ? null : player.getCurrentSquare();
        if (sq == null) return;
        zombie.core.textures.ColorInfo c = sq.getLightInfo(player.getPlayerNum());
        if (c == null) { sq.cacheLightInfo(); c = sq.getLightInfo(player.getPlayerNum()); }
        if (c == null) return;
        handLight[0] = c.r; handLight[1] = c.g; handLight[2] = c.b;
    }

    static java.util.Map<String, Object> handLightStats() {
        return java.util.Map.of("r", handLight[0], "g", handLight[1], "b", handLight[2], "gain", handGain);
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
                    uniform vec3 uLight, uDaylight;
                    out vec2 uv; out vec4 tint;
                    float mcCurve(float level){return level/(4.-3.*level);}
                    void main(){gl_Position=uProjection*vec4(aPosition,1);uv=aUv;
                        // the light on Steve: PZ's at his square; under a Minecraft roof or in a cave Minecraft's caps it, and
                        // a Minecraft torch nearby adds its own (VertexLight). Glowing parts (a held torch's flame) stay bright.
                        float emission=aEmission;vec3 light=uLight;
                        if(aEmission>=4095.5){
                            float code=floor(aEmission-4096.+.5);emission=mod(code,16.)/15.;
                            vec3 torch=vec3(1.,.82,.6)*mcCurve(mod(floor(code/16.),16.)/15.);
                            float sky=floor(code/256.)/15.;
                            if(sky<.99)light=min(light,uDaylight*mcCurve(sky)+torch+.012);
                            light=max(light,torch);
                        }
                        float shade=.7+.3*max(0.,dot(normalize(aNormal),normalize(vec3(.2,1.,.5))));
                        tint=vec4(aTint.rgb*mix(shade*light,vec3(1.),clamp(emission,0.,1.)),aTint.a);}
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
            GL20.glUniform3f(GL20.glGetUniformLocation(handProgram,"uLight"),handLight[0]*handGain,handLight[1]*handGain,handLight[2]*handGain);
            GL20.glUniform3f(GL20.glGetUniformLocation(handProgram,"uDaylight"),NativeBlocks.daylight[0],NativeBlocks.daylight[1],NativeBlocks.daylight[2]);
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
