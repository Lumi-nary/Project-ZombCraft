package pzcraft.pz;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjglx.opengl.Display;
import pzcraft.protocol.Coords;
import pzcraft.protocol.FrameLink;
import pzcraft.protocol.Warp;

/**
 * Draws Minecraft's blocks (re-projected to PZ's current camera) and hand + HUD (two transparent, premultiplied-alpha layers
 * from {@link FrameLink}) over everything
 * PZ and Viewpoint have drawn, just before the buffers are swapped. Runs on PZ's render thread, which owns the GL
 * context. Every piece of GL state it touches is saved and restored, because Viewpoint leaves its own state bound.
 */
public final class Overlay {
    private static FrameLink link;
    private static long lastOpenAttempt;
    private static final int[] textures = new int[2];
    private static int texW, texH;
    private static long uploadedFrame;
    private static final FrameLink.Meta meta = new FrameLink.Meta();
    private static final float[] warp = new float[12];
    /** A/B switch for the rotation time-warp (dev command "warp on|off"). */
    static volatile boolean warpEnabled = true;
    /** Draw with our own shader (immune to whatever fixed-function state PZ and Viewpoint left behind); false = fixed function. */
    static volatile boolean useShader = true;
    /** Test hook: leave a black texture enabled on texture unit 1 before drawing, as stale PZ state might (1: after the fixed-function cleanup, 2: before it). */
    static volatile int pollute;
    private static int program, uniformTex;
    private static boolean shaderFailed;
    private static volatile float lastWarpYaw, lastWarpPitch, lastAgeMs;
    private static volatile boolean lastWarpApplied;
    private static boolean disabled;
    private static int failures;
    private static boolean loggedFirst;

    private Overlay() {}

    /** Called from Display.update(boolean), once per presented frame. */
    public static void draw() {
        try {
            drawOverlay();
            NativeBlocks.notePresented();
        } catch (Throwable t) {
            Log.error("frame hook failed", t);
        }
    }

    /** Overlay state for the developer automation. */
    static java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("drawn", loggedFirst);
        m.put("frameId", lastFrameId);
        m.put("texW", texW); m.put("texH", texH);
        m.put("disabled", disabled);
        m.put("warpEnabled", warpEnabled);
        m.put("shader", useShader && !shaderFailed);
        m.put("warpApplied", lastWarpApplied);
        m.put("warpYawDeg", lastWarpYaw);
        m.put("warpPitchDeg", lastWarpPitch);
        m.put("frameAgeMs", lastAgeMs);
        m.put("worldVideo",meta.hasWorld());
        return m;
    }

    private static volatile float viewYaw, viewPitch, viewFov;
    private static volatile long viewNoted;

    /** Render thread, as Viewpoint starts drawing the 3D view: the camera direction this frame's PZ picture is drawn through. */
    public static void noteViewLook() {
        viewYaw = Coords.pzRadToMcYawDeg(ViewpointBridge.yaw());
        viewPitch = Coords.pzPitchRadToMcDeg(ViewpointBridge.pitch());
        pzcraft.protocol.PlayerState s = new pzcraft.protocol.PlayerState();
        if (SteveControl.drives(zombie.characters.IsoPlayer.getInstance()) && LinkService.pullInto(s) && s.cameraValid) {
            viewYaw = s.cameraYawDeg; viewPitch = s.cameraPitchDeg;
        }
        viewFov = ViewpointBridge.fovDegrees();
        viewNoted = System.nanoTime();
    }

    static float lastWarpYaw() { return lastWarpYaw; }
    static float lastWarpPitch() { return lastWarpPitch; }

    private static volatile long lastFrameId;

    private static void drawOverlay() {
        if (disabled) return;
        try {
            if (!ViewpointBridge.viewEnabled() || !LinkService.minecraftReady()) return;
            if (!ensureLink()) return;
            long id = link.frameId();
            if (id == 0) return;
            lastFrameId = id;
            link.readMeta(meta);
            int w = meta.width, h = meta.height;
            if (w <= 0 || h <= 0 || w > FrameLink.MAX_WIDTH || h > FrameLink.MAX_HEIGHT) return;
            render(id, w, h);
        } catch (Throwable t) {
            if (++failures >= 5) {
                disabled = true;
                Log.info("overlay disabled after repeated failures");
            }
            Log.error("overlay draw failed (" + failures + "/5)", t);
        }
    }

    private static boolean ensureLink() {
        if (link != null) return true;
        long now = System.currentTimeMillis();
        if (now - lastOpenAttempt < 1000) return false;
        lastOpenAttempt = now;
        try {
            link = FrameLink.open();
            Log.info("frame link open at " + FrameLink.defaultPath());
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    private static long lastProbe;

    /** Experiment: does the framebuffer being presented carry scene depth we could test blocks against? */
    private static void probeDepth(int vw, int vh) {
        long now = System.currentTimeMillis();
        if (now - lastProbe < 4000) return;
        lastProbe = now;
        java.nio.FloatBuffer f = org.lwjgl.BufferUtils.createFloatBuffer(1);
        StringBuilder sb = new StringBuilder("depth probe " + vw + "x" + vh + " fbo=" + GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) + ":");
        int[][] pts = {{vw / 2, vh / 2}, {vw / 4, vh / 2}, {3 * vw / 4, vh / 2}, {vw / 2, vh / 4}, {vw / 2, 3 * vh / 4}, {10, 10}};
        for (int[] p : pts) {
            f.clear();
            GL11.glReadPixels(p[0], p[1], 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, f);
            sb.append(String.format(" (%d,%d)=%.5f", p[0], p[1], f.get(0)));
        }
        Log.info(sb.toString() + " depthBits=" + GL11.glGetInteger(GL11.GL_DEPTH_BITS));
    }

    private static void render(long id, int w, int h) {
        // ---- save the state we are about to disturb ----
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevPbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glPushMatrix();
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL20.glUseProgram(0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);

            if (textures[0] == 0) {
                textures[0] = GL11.glGenTextures();
                textures[1] = GL11.glGenTextures();
            }
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            boolean resized = w != texW || h != texH;
            for (int layer = 0; layer < 2; layer++) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[layer]);
                if (resized) {
                    // The world layer is re-projected, so what lies outside the old picture must be transparent, not smeared.
                    int wrap = layer == FrameLink.WORLD ? GL12Const.CLAMP_TO_BORDER : GL12Const.CLAMP_TO_EDGE;
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap);
                    GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL12Const.TEXTURE_BORDER_COLOR, new float[] {0f, 0f, 0f, 0f});
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                }
                if (resized || id != uploadedFrame) {
                    if (layer == FrameLink.WORLD && !meta.hasWorld()) continue;
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, link.frontLayer(layer, w, h));
                }
            }
            if (resized) {
                texW = w;
                texH = h;
            }
            if (id != uploadedFrame) {
                uploadedFrame = id;
                lastAgeMs = (System.nanoTime() - meta.renderNanos) / 1_000_000f;
            }

            int vw = Display.getWidth(), vh = Display.getHeight();
            probeDepth(vw, vh);
            GL11.glViewport(0, 0, vw, vh);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();

            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColorMask(true, true, true, true);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glDisable(GL12Const.FRAMEBUFFER_SRGB);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);

            boolean shader = useShader && ensureProgram();
            if (pollute == 2) pollute(); // test hook: stale state that the cleanup below must undo
            if (!shader) cleanFixedFunctionState();
            if (pollute == 1) pollute(); // test hook: stale state arriving after the cleanup (what only the shader path survives)
            if (shader) {
                GL20.glUseProgram(program);
                GL20.glUniform1i(uniformTex, 0);
            }

            // Minecraft's rows come bottom-first, which is GL's own texture orientation: no flip needed.
            if (meta.hasWorld() && (!NativeBlocks.active() || meta.nativeEffects())) {
                // The world layer was rendered a few milliseconds ago through the camera stored with it; PZ's camera has
                // turned since, so draw it re-projected to where PZ is looking now.
                boolean noted = System.nanoTime() - viewNoted < 250_000_000L;
                float curYaw = noted ? viewYaw : Coords.pzRadToMcYawDeg(ViewpointBridge.yaw());
                float curPitch = noted ? viewPitch : Coords.pzPitchRadToMcDeg(ViewpointBridge.pitch());
                float curFov = noted ? viewFov : ViewpointBridge.fovDegrees();
                float curAspect = vh > 0 ? (float) vw / vh : meta.aspect;
                lastWarpYaw = curYaw - meta.yawDeg;
                lastWarpPitch = curPitch - meta.pitchDeg;
                // With the warp off (A/B test) the picture is still shown for the direction it was rendered in.
                boolean warped = Warp.corners(meta.yawDeg, meta.pitchDeg, meta.fovDeg, (float) meta.width / meta.height,
                        warpEnabled ? curYaw : meta.yawDeg, warpEnabled ? curPitch : meta.pitchDeg, curFov, curAspect, warp);
                lastWarpApplied = warped;
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[FrameLink.WORLD]);
                quad(warp);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[FrameLink.HUD]);
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            if(NativeBlocks.active())NativeEntities.drawHands(vw,vh);
            GL11.glPopAttrib();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[FrameLink.HUD]);
            Warp.identity(warp);
            quad(warp);

            if (!loggedFirst) {
                loggedFirst = true;
                Log.info("overlay: first frame drawn (" + w + "x" + h + " onto " + vw + "x" + vh + ")");
            }
        } finally {
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            GL13.glActiveTexture(prevActive);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, prevPbo);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL20.glUseProgram(prevProgram);
        }
    }

    private static final String VERT = "#version 120\nvarying vec4 tc;\nvoid main() { gl_Position = gl_Vertex; tc = gl_MultiTexCoord0; }\n";
    private static final String FRAG = "#version 120\nuniform sampler2D tex;\nvarying vec4 tc;\nvoid main() { gl_FragColor = texture2DProj(tex, tc); }\n";

    /** Our own program: samples unit 0 and writes the texel as is, whatever fixed-function texture state is left over. */
    private static boolean ensureProgram() {
        if (program != 0) return true;
        if (shaderFailed) return false;
        try {
            int vs = compile(GL20.GL_VERTEX_SHADER, VERT), fs = compile(GL20.GL_FRAGMENT_SHADER, FRAG);
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            uniformTex = GL20.glGetUniformLocation(p, "tex");
            program = p;
            Log.info("overlay: shader program ready");
            return true;
        } catch (Throwable t) {
            shaderFailed = true;
            Log.error("overlay shader failed; using fixed-function drawing", t);
            return false;
        }
    }

    private static int compile(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        return s;
    }

    /** A screen-filling quad whose corners carry projective texture coordinates (s, t, q) from {@link Warp}. */
    /**
     * Fixed-function drawing combines every enabled texture unit, so make sure only unit 0 takes part, with the plain
     * modulate environment, and nothing generates or transforms coordinates behind our back.
     */
    private static void cleanFixedFunctionState() {
        int units = Math.max(2, Math.min(8, GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS)));
        for (int u = units - 1; u >= 0; u--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            if (u > 0) {
                GL11.glDisable(GL11.GL_TEXTURE_2D);
            }
            GL11.glDisable(GL11.GL_TEXTURE_GEN_S);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_T);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_R);
            GL11.glDisable(GL11.GL_TEXTURE_GEN_Q);
            GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
            GL11.glMatrixMode(GL11.GL_TEXTURE);
            GL11.glLoadIdentity();
        }
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_COLOR_MATERIAL);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glShadeModel(GL11.GL_SMOOTH);
    }

    private static int blackTexture;

    private static void pollute() {
        if (blackTexture == 0) {
            blackTexture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blackTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) org.lwjgl.BufferUtils.createByteBuffer(4).put(new byte[] {0, 0, 0, (byte) 255}).flip());
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, blackTexture);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void quad(float[] c) {
        GL11.glBegin(GL11.GL_QUADS);
        float[] x = {-1f, 1f, 1f, -1f};
        float[] y = {-1f, -1f, 1f, 1f};
        for (int i = 0; i < 4; i++) {
            GL11.glTexCoord4f(c[i * 3], c[i * 3 + 1], 0f, c[i * 3 + 2]);
            GL11.glVertex2f(x[i], y[i]);
        }
        GL11.glEnd();
    }

    /** Constants that live in newer GL classes than the ones imported above. */
    private static final class GL21 {
        static final int PIXEL_UNPACK_BUFFER_CONST = 0x88EC;
        static final int GL_PIXEL_UNPACK_BUFFER = PIXEL_UNPACK_BUFFER_CONST;
        static final int GL_PIXEL_UNPACK_BUFFER_BINDING = 0x88EF;
    }

    private static final class GL12Const {
        static final int CLAMP_TO_EDGE = 0x812F;
        static final int CLAMP_TO_BORDER = 0x812D;
        static final int TEXTURE_BORDER_COLOR = 0x1004;
        static final int FRAMEBUFFER_SRGB = 0x8DB9;
    }
}


