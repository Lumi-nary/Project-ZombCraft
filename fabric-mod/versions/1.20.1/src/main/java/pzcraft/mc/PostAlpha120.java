package pzcraft.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Keeps a full-screen post effect (another mod's PostChain, such as Viltrumite's dash/punch and flight shaders, or
 * vanilla's spectator views) from covering PZ's world. Minecraft's picture is laid over PZ's with premultiplied alpha
 * and is transparent wherever Minecraft drew nothing; a post chain's last pass writes alpha 1 everywhere, which turned
 * the whole overlay opaque black. Around the chain the alpha of the screen target is saved and written back: the effect
 * still changes everything Minecraft drew (hand, entities, particles, its own VFX), and whatever it adds over the empty
 * parts (flashes, speed lines, tints) is added over PZ's picture. It cannot distort PZ's own pixels, which Minecraft
 * never sees.
 */
public final class PostAlpha120 {
    private static TextureTarget saved;
    private static int program = -1, vao, samplerUniform;
    private static boolean failed;

    private PostAlpha120() {}

    /** Before the chain runs: copy the screen target (its alpha is what matters) aside. */
    public static boolean before(RenderTarget screen) {
        if (failed || !Session.pzConnected || screen.frameBufferId <= 0) return false;
        try {
            int w = screen.width, h = screen.height;
            if (saved == null) saved = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            else if (saved.width != w || saved.height != h) saved.resize(w, h, Minecraft.ON_OSX);
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(true, true, true, true);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, screen.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saved.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            screen.bindWrite(false);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** After the chain: write the saved alpha back into the screen target, leaving the effect's colour. */
    public static void after(RenderTarget screen) {
        if (failed || saved == null) return;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            if (program < 0) compile();
            screen.bindWrite(true);
            GlStateManager._disableBlend();
            GlStateManager._disableDepthTest();
            GlStateManager._disableScissorTest();
            GlStateManager._colorMask(false, false, false, true);
            GL20.glUseProgram(program);
            GL20.glUniform1i(samplerUniform, 0);
            GlStateManager._bindTexture(saved.getColorTextureId());
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            fail(t);
        } finally {
            GlStateManager._colorMask(true, true, true, true);
            GL30.glBindVertexArray(previousVao);
            GL20.glUseProgram(previousProgram);
            GlStateManager._bindTexture(previousTexture);
            GlStateManager._activeTexture(previousUnit);
        }
    }

    private static void compile() {
        int vs = shader(GL20.GL_VERTEX_SHADER, """
                #version 150
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """);
        int fs = shader(GL20.GL_FRAGMENT_SHADER, """
                #version 150
                uniform sampler2D Saved;
                out vec4 fragColor;
                void main() {
                    fragColor = vec4(0.0, 0.0, 0.0, texelFetch(Saved, ivec2(gl_FragCoord.xy), 0).a);
                }
                """);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL30.glBindFragDataLocation(p, 0, "fragColor");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException("post alpha program: " + GL20.glGetProgramInfoLog(p));
        samplerUniform = GL20.glGetUniformLocation(p, "Saved");
        vao = GL30.glGenVertexArrays();
        program = p;
    }

    private static int shader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE)
            throw new IllegalStateException("post alpha shader: " + GL20.glGetShaderInfoLog(s));
        return s;
    }

    private static void fail(Throwable t) {
        failed = true;
        PzCraftMain.LOG.error("Post effect alpha restore disabled; full-screen effects may cover PZ's world", t);
    }
}
