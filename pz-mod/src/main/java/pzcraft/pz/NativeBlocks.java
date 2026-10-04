package pzcraft.pz;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import pzcraft.protocol.SceneLink;

/** Draws Minecraft's baked block geometry in Viewpoint's scene, at Viewpoint's current camera and frame rate. */
public final class NativeBlocks {
    private static SceneLink link;
    private static SceneLink.Mesh mesh;
    private static long atlasRevision, meshRevision;
    private static int atlas, vao, vbo, program;
    private static int outlineVao, outlineVbo;
    private static int shadowProgram, shadowFailures;
    private static long shadowKey = -1, shadowDraws;
    private static volatile boolean shadows = true;
    private static final ByteBuffer outlineData = BufferUtils.createByteBuffer(24 * SceneLink.VERTEX_BYTES);
    private static int failures;
    private static volatile boolean enabled = true, failed, drawn;
    private static volatile boolean presented;
    private static volatile long lastDrawNanos;
    private static volatile int vertices;
    private static Method libraryRead, libraryMacros, bandBind, cellBind;
    private static Field lightTexture, lightRows;
    private static boolean reflected;
    private static final java.nio.FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

    private NativeBlocks() {}

    public static void beginFrame() { drawn = false; }
    static int atlasTexture() { return atlas; }
    static boolean wanted() { return enabled && !failed; }
    public static boolean active() {
        return enabled && !failed && drawn && System.nanoTime() - lastDrawNanos < 500_000_000L;
    }
    static void notePresented() { presented = active(); }
    static void setEnabled(boolean value) { enabled = value; if (!value) { drawn = false; LinkService.nativeWorldRendered(false); } }
    static Map<String, Object> stats() {
        var out = new LinkedHashMap<String, Object>();
        out.put("enabled", enabled); out.put("failed", failed);
        out.put("active", enabled && !failed && presented && System.nanoTime() - lastDrawNanos < 500_000_000L);
        out.put("vertices", vertices); out.put("atlasRevision", atlasRevision); out.put("meshRevision", meshRevision);
        out.put("failures", failures);
        out.put("sunShadows", shadows); out.put("shadowDraws", shadowDraws); out.put("shadowFailures", shadowFailures);
        return out;
    }

    static void setShadows(boolean value) { shadows = value; }

    /** Force cached cascades to refresh after addition/removal or switching native terrain off. */
    public static void shadowFrame(Object pass) {
        try {
            long key = enabled && !failed && shadows && LinkService.connected() && link != null && link.available() ? meshRevision : 0;
            if (key != shadowKey) {
                java.util.Arrays.fill((boolean[])get(pass, "valid"), false);
                shadowKey = key;
            }
        } catch (Throwable t) { shadowError(t); }
    }

    public static void shadow(Object pass, Object scene, int cascade) {
        if (!enabled || failed || !shadows || mesh == null || atlas == 0 || mesh.atlasRevision() > atlasRevision
                || !LinkService.connected() || link == null || !link.available()) return;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousSampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (shadowProgram == 0) {
                int vert = compile(GL20.GL_VERTEX_SHADER, VERTEX), frag = 0;
                try {
                    frag = compile(GL20.GL_FRAGMENT_SHADER, """
                            #version 330
                            in vec2 vUv;
                            uniform sampler2D uTexture;
                            void main() { if (texture(uTexture, vUv).a < 0.3) discard; }
                            """);
                    shadowProgram = GL20.glCreateProgram();
                    GL20.glAttachShader(shadowProgram, vert); GL20.glAttachShader(shadowProgram, frag);
                    GL20.glLinkProgram(shadowProgram);
                    if (GL20.glGetProgrami(shadowProgram, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(shadowProgram));
                } finally { GL20.glDeleteShader(vert); if (frag != 0) GL20.glDeleteShader(frag); }
            }
            GL20.glUseProgram(shadowProgram);
            Matrix4f vp = ((Matrix4f[])get(pass, "viewProjection"))[cascade];
            matrix.clear(); vp.get(matrix);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(shadowProgram, "uViewProjection"), false, matrix);
            GL20.glUniform3f(GL20.glGetUniformLocation(shadowProgram, "uOffset"), (float)(-mesh.x() - number(scene, "originX")),
                    (float)(mesh.y() - number(scene, "originY")), (float)(-mesh.z() - number(scene, "originZ")));
            GL20.glUniform1i(GL20.glGetUniformLocation(shadowProgram, "uTexture"), 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas); GL33.glBindSampler(0, 0);
            GL11.glEnable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(true); GL11.glDepthFunc(GL11.GL_LESS);
            GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL); GL11.glPolygonOffset(2f, 4f);
            GL30.glBindVertexArray(vao); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, mesh.vertexCount());
            // Steve (always, even in first person) and dropped items cast shadows too.
            NativeEntities.drawShadow(number(scene, "originX"), number(scene, "originY"), number(scene, "originZ"), atlas,
                    GL20.glGetUniformLocation(shadowProgram, "uOffset"));
            shadowDraws++;
        } catch (Throwable t) { shadowError(t); }
        finally {
            GL11.glPopAttrib(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture); GL33.glBindSampler(0, previousSampler);
            GL13.glActiveTexture(previousActive); GL30.glBindVertexArray(previousVao); GL20.glUseProgram(previousProgram);
        }
    }

    private static void shadowError(Throwable t) {
        if (++shadowFailures >= 3) shadows = false;
        Log.error("native block sun shadows failed (" + shadowFailures + "/3)", t);
    }

    /** Public because ZombieBuddy inlines its advice into Viewpoint's class. Exceptions stay inside this hook. */
    public static void draw(Object frame) {
        drawn = false;
        if (!enabled || failed || !LinkService.minecraftReady() || !LinkService.connected()) { LinkService.nativeWorldRendered(false); return; }
        try {
            if (link == null) link = SceneLink.open(false);
            reflect();
            render(frame);
            LinkService.nativeWorldRendered(active());
        } catch (Throwable t) {
            LinkService.nativeWorldRendered(false);
            if (++failures >= 3) failed = true;
            Log.error("native blocks failed (" + failures + "/3); video overlay remains available", t);
        }
    }

    private static void reflect() throws Exception {
        if (reflected) return;
        Class<?> library = Class.forName("viewpoint.platform.ShaderLibrary");
        libraryRead = library.getMethod("read", String.class);
        libraryMacros = library.getMethod("pipelineMacros");
        bandBind = method("viewpoint.render.BandLight", "bind", int.class);
        cellBind = method("viewpoint.render.CellLightPages", "bind", int.class, int.class);
        lightTexture = field(Class.forName("viewpoint.render.MeshArena"), "lightArray");
        lightRows = field(Class.forName("viewpoint.render.MeshArena"), "rows");
        reflected = true;
    }

    private static void render(Object frame) throws Exception {
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldPbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] units = {0, 5, 45, 46, 47};
        int[] targets = {GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_2D, GL12_TEXTURE_3D, GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_2D};
        int[] bindings = {GL11.GL_TEXTURE_BINDING_2D, GL11.GL_TEXTURE_BINDING_2D, GL12_TEXTURE_BINDING_3D,
                GL30.GL_TEXTURE_BINDING_2D_ARRAY, GL11.GL_TEXTURE_BINDING_2D};
        int[] previous = new int[units.length], samplers = new int[units.length];
        for (int i = 0; i < units.length; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + units[i]);
            previous[i] = GL11.glGetInteger(bindings[i]);
            samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            var nextAtlas = link.readAtlas(atlasRevision);
            if (nextAtlas != null) uploadAtlas(nextAtlas);
            var nextMesh = link.readMesh(meshRevision);
            if (nextMesh != null) uploadMesh(nextMesh);
            if (!link.available()) return;
            if (mesh == null || atlas == 0 || mesh.atlasRevision() > atlasRevision) return;
            if (program == 0) program = makeProgram();
            GL20.glUseProgram(program);
            for (int unit : units) GL33.glBindSampler(unit, 0);

            Object scene = get(frame, "scene");
            Streaming.scene(scene);
            double sx = number(scene, "originX"), sy = number(scene, "originY"), sz = number(scene, "originZ");
            Matrix4f vp = (Matrix4f) get(frame, "viewProjection");
            matrix.clear(); vp.get(matrix);
            GL20.glUniformMatrix4fv(uniform("uViewProjection"), false, matrix);
            GL20.glUniform3f(uniform("uOffset"), (float)(-mesh.x() - sx), (float)(mesh.y() - sy), (float)(-mesh.z() - sz));
            GL20.glUniform3f(uniform("uWorldOrigin"), mesh.x(), mesh.y(), mesh.z());
            GL20.glUniform3f(uniform("uEye"), (float)number(frame, "eyeX"), (float)number(frame, "eyeY"), (float)number(frame, "eyeZ"));
            GL20.glUniform3f(uniform("uSkyLevel"), (float)number(scene, "skyR"), (float)number(scene, "skyG"), (float)number(scene, "skyB"));
            boolean daylightKnown = (boolean)get(scene, "daylightKnown");
            GL20.glUniform3f(uniform("uDaylight"), daylightKnown ? (float)number(scene, "daylightR") : .8f,
                    daylightKnown ? (float)number(scene, "daylightG") : .8f, daylightKnown ? (float)number(scene, "daylightB") : .8f);
            GL20.glUniform2f(uniform("uLightAtlasSize"), 4080f, lightRows.getInt(null) * 40f);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 5);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightTexture.getInt(null));
            bandBind.invoke(null, 45); cellBind.invoke(null, 46, 47);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LESS); GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND); GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glColorMask(true, true, true, true);
            GL30.glBindVertexArray(vao);
            GL20.glUniform1i(uniform("uOutline"), 0);
            GL20.glUniform1i(uniform("uDither"), 0);
            GL20.glUniform1i(uniform("uPlayerMotion"), 0);
            frameNoise = (frameNoise + 1) % 64;
            GL20.glUniform1f(uniform("uFrameNoise"), frameNoise * 5.588238f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, mesh.vertexCount());
            // Entities, block entities and particles, with the same shading; then back to the block mesh's frame.
            NativeEntities.drawGbuffer(sx, sy, sz, atlas, uniform("uOffset"), uniform("uWorldOrigin"), uniform("uDither"),uniform("uPlayerMotion"));
            GL20.glUniform3f(uniform("uOffset"), (float)(-mesh.x() - sx), (float)(mesh.y() - sy), (float)(-mesh.z() - sz));
            GL20.glUniform3f(uniform("uWorldOrigin"), mesh.x(), mesh.y(), mesh.z());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL30.glBindVertexArray(vao);
            drawSelection();
            vertices = mesh.vertexCount(); lastDrawNanos = System.nanoTime(); drawn = true;
        } finally {
            GL11.glPopAttrib();
            for (int i = 0; i < units.length; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + units[i]);
                GL11.glBindTexture(targets[i], previous[i]); GL33.glBindSampler(units[i], samplers[i]);
            }
            GL13.glActiveTexture(oldActive);
            GL30.glBindVertexArray(oldVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldBuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, oldPbo); GL20.glUseProgram(oldProgram);
        }
    }

    private static int atlasWidth,atlasHeight;
    private static int frameNoise;
    private static void uploadAtlas(SceneLink.Atlas next) {
        if (atlas == 0) atlas = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
        if(atlasWidth==next.width()&&atlasHeight==next.height())
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,next.width(),next.height(),GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,next.pixels());
        else {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, next.width(), next.height(), 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, next.pixels());
            atlasWidth=next.width();atlasHeight=next.height();
            Log.info("native block atlas uploaded: " + next.width() + "x" + next.height());
        }
        atlasRevision = next.revision();
    }

    private static void uploadMesh(SceneLink.Mesh next) {
        if (vao == 0) {
            vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            int[] sizes = {3, 3, 2, 3, 1}; int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                GL20.glEnableVertexAttribArray(i);
                GL20.glVertexAttribPointer(i, sizes[i], GL11.GL_FLOAT, false, SceneLink.VERTEX_BYTES, (long)offset);
                offset += sizes[i] * 4;
            }
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, next.vertices(), GL15.GL_DYNAMIC_DRAW);
        mesh = next; meshRevision = next.revision();
    }

    private static void drawSelection() {
        var selected = link.readSelection();
        if (selected == null || !selected.active()) return;
        if (outlineVao == 0) {
            outlineVao = GL30.glGenVertexArrays(); outlineVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(outlineVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outlineVbo);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, SceneLink.VERTEX_BYTES, 0L);
        }
        GL30.glBindVertexArray(outlineVao); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, outlineVbo);
        outlineData.clear();
        float x0 = selected.x() - mesh.x() + selected.minX(), x1 = selected.x() - mesh.x() + selected.maxX();
        float y0 = selected.y() - mesh.y() + selected.minY(), y1 = selected.y() - mesh.y() + selected.maxY();
        float z0 = selected.z() - mesh.z() + selected.minZ(), z1 = selected.z() - mesh.z() + selected.maxZ();
        for (int i = 0; i < 8; i++) for (int bit : new int[] {1, 2, 4}) {
            if ((i & bit) != 0) continue;
            for (int corner : new int[] {i, i | bit}) {
                outlineData.putFloat((corner & 1) == 0 ? x0 : x1).putFloat((corner & 2) == 0 ? y0 : y1)
                        .putFloat((corner & 4) == 0 ? z0 : z1);
                for (int pad = 0; pad < 9; pad++) outlineData.putFloat(0);
            }
        }
        outlineData.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, outlineData, GL15.GL_STREAM_DRAW);
        GL20.glUniform1i(uniform("uOutline"), 1);
        GL11.glLineWidth(1.5f); GL11.glDepthMask(false); GL11.glDepthFunc(GL11.GL_LEQUAL);
        GL11.glDrawArrays(GL11.GL_LINES, 0, 24);
    }

    @SuppressWarnings("unchecked")
    private static int makeProgram() throws Exception {
        StringBuilder defines = new StringBuilder("#version 330\n");
        ((Map<String, String>)libraryMacros.invoke(null)).forEach((key, value) -> defines.append("#define ").append(key).append(' ').append(value).append('\n'));
        String fragment = defines + expand(FRAGMENT);
        int vert = compile(GL20.GL_VERTEX_SHADER, VERTEX), frag = 0, result = 0;
        try {
            frag = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            result = GL20.glCreateProgram(); GL20.glAttachShader(result, vert); GL20.glAttachShader(result, frag);
            GL20.glLinkProgram(result);
            if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL20.glGetProgramInfoLog(result));
            GL20.glUseProgram(result);
            String[] names = {"uTexture", "uLight", "uBandLight", "uCellLight", "uCellTable"};
            int[] units = {0, 5, 45, 46, 47};
            for (int i = 0; i < names.length; i++) GL20.glUniform1i(GL20.glGetUniformLocation(result, names[i]), units[i]);
            Log.info("native block shader linked using Viewpoint's light library");
            return result;
        } catch (Throwable t) { if (result != 0) GL20.glDeleteProgram(result); throw t; }
        finally { GL20.glDeleteShader(vert); if (frag != 0) GL20.glDeleteShader(frag); }
    }

    private static String expand(String source) throws Exception {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("#include \"")) {
                String path = stripped.substring(10, stripped.lastIndexOf('"'));
                String part = (String)libraryRead.invoke(null, path);
                if (part == null) throw new IllegalStateException("Viewpoint shader library missing " + path);
                out.append(expand(part));
            } else out.append(line).append('\n');
        }
        return out.toString();
    }

    private static int compile(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(shader); GL20.glDeleteShader(shader);
            throw new IllegalStateException("native block shader: " + error);
        }
        return shader;
    }
    private static int uniform(String name) { return GL20.glGetUniformLocation(program, name); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object get(Object obj, String name) throws Exception { return field(obj.getClass(), name).get(obj); }
    private static double number(Object obj, String name) throws Exception { return ((Number)get(obj, name)).doubleValue(); }
    private static Method method(String type, String name, Class<?>... args) throws Exception {
        Method m = Class.forName(type).getDeclaredMethod(name, args); m.setAccessible(true); return m;
    }
    private static final int GL12_TEXTURE_3D = 0x806F, GL12_TEXTURE_BINDING_3D = 0x806A, GL12_CLAMP_TO_EDGE = 0x812F;

    private static final String VERTEX = """
            #version 330
            layout(location=0) in vec3 aPosition;
            layout(location=1) in vec3 aNormal;
            layout(location=2) in vec2 aUv;
            layout(location=3) in vec4 aTint; // blocks give rgb (alpha then reads 1), entities and particles rgba
            layout(location=4) in float aEmission;
            uniform mat4 uViewProjection;
            uniform vec3 uOffset, uWorldOrigin;
            out vec3 vPosition, vWorld, vNormal;
            out vec4 vTint;
            out vec2 vUv;
            out float vEmission;
            void main() {
              vPosition = aPosition * vec3(-1.0, 1.0, -1.0) + uOffset;
              vWorld = aPosition + uWorldOrigin;
              vNormal = aNormal * vec3(-1.0, 1.0, -1.0); vTint = aTint; vUv = aUv; vEmission = aEmission;
              gl_Position = uViewProjection * vec4(vPosition, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #include "lib/band_light.glsl"
            in vec3 vPosition, vWorld, vNormal;
            in vec4 vTint;
            in vec2 vUv;
            in float vEmission;
            uniform sampler2D uTexture;
            uniform vec3 uEye;
            uniform int uOutline;
            uniform int uDither;
            uniform int uPlayerMotion;
            uniform float uFrameNoise;
            layout(location=0) out vec4 gAlbedo;
            layout(location=1) out vec4 gNormal;
            layout(location=2) out vec4 gLight;
            layout(location=3) out vec4 gVelocity;
            void main() {
              // Static blocks use depth reprojection. Steve has no camera-relative translation;
              // depth zero rejects stale temporal history and explicit motion prevents terrain motion blur.
              gVelocity = uPlayerMotion != 0 ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(0.0);
              if (uOutline != 0) {
                gAlbedo = vec4(vec3(0.015), 0.25);
                gNormal = vec4(0.0, 1.0, 0.0, 1.0); gLight = vec4(1.0);
                return;
              }
              vec4 texel = texture(uTexture, vUv);
              if (uDither != 0) {
                // see-through (smoke, splashes, lightning): cover this share of the pixels, a different set every
                // frame; Viewpoint's temporal anti-aliasing blends them into transparency
                float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy + uFrameNoise, vec2(0.06711056, 0.00583715))));
                if (texel.a * vTint.a <= noise) discard;
              } else if (texel.a < 0.1) discard;
              vec3 n = normalize(vNormal);
              if (dot(n, vPosition - uEye) > 0.0) n = -n;
              vec4 light = bandLight(vWorld.xz, vWorld.y, n);
              vec2 at = vWorld.xz - n.xz * 0.3;
              vec2 chunk = floor(at / 8.0);
              int level = int(floor(vWorld.y / SQRT6 + n.y * 0.05));
              float sky = 1.0;
              if (level >= 0 && level < BAND_LEVELS) {
                int slot = texelFetch(uBandLight, ivec3(ivec2(mod(chunk, float(BAND_WINDOW))), level), 0).r - 1;
                if (slot >= 0) {
                  vec2 origin = vec2(mod(float(slot), LIGHT_COLUMNS), floor(float(slot) / LIGHT_COLUMNS)) * LIGHT_GRID;
                  sky = gridSky(origin, clamp(at - chunk * 8.0, 0.01, 7.99) + 1.0);
                }
              }
              gAlbedo = vec4(texel.rgb * vTint.rgb, vEmission > 0.99 ? 0.25 : 0.0);
              gNormal = vec4(n, light.a);
              gLight = vec4(max(light.rgb, vec3(vEmission)), sky);
            }
            """;
}

