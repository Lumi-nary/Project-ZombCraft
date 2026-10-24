package pzcraft.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import pzcraft.protocol.FrameLink;

/**
 * 1.20.1 version of the shared FrameCapture (same job and layer logic; 1.20.1 has no GPU device API, so the read-back
 * is plain OpenGL). Each layer is read from the main render target into a pixel buffer object with glReadPixels, which
 * returns at once; a fence marks when the copy is done, and later frames copy finished buffers into the frame link. Rows
 * are bottom-up, as the 26.1.2 GL backend's copyTextureToBuffer gives them.
 */
final class FrameCapture {
    /** Frames whose read-backs have been issued but not yet published; the link has room for FrameLink.SLOTS - 1. */
    private static final AtomicInteger inFlight = new AtomicInteger();
    private static Job job;
    private static long captured;
    private static float yaw, pitch, fov;
    private static long frameNanos;
    private static boolean warnedSize;
    private static final boolean DISABLED = System.getenv("PZCRAFT_NO_CAPTURE") != null; // A/B testing the cost of the read-back
    private static long statsStart = System.nanoTime(), framesSeen, framesCaptured, publishNanosSum;

    /** Read-backs issued, oldest first: finished in order, so frames are published in the order they were reserved. */
    private static final ArrayDeque<Readback> readbacks = new ArrayDeque<>();
    /** Pixel buffers not in use, with their byte size. */
    private static final ArrayDeque<int[]> freeBuffers = new ArrayDeque<>();
    /** A read-back still unfinished after this many frames is waited for. */
    private static final int MAX_FRAMES_BEHIND = 3;

    private record Readback(Job job, int layer, int[] buffer, long fence, long issuedFrame) {}

    private FrameCapture() {}

    /** One frame being assembled; published once both layer read-backs have landed. */
    private static final class Job {
        final FrameLink link;
        final long seq;
        final int w, h;
        final float yaw, pitch, fov, aspect;
        final long nanos;
        /** Outstanding read-backs, plus one token held until the frame has finished rendering. */
        final AtomicInteger pending = new AtomicInteger(1);
        boolean hasWorld;
        final boolean nativeEffects;

        Job(FrameLink link, int w, int h, float yaw, float pitch, float fov, long nanos) {
            this.link = link;
            this.seq = link.reserve();
            this.w = w;
            this.h = h;
            this.yaw = yaw;
            this.pitch = pitch;
            this.fov = fov;
            this.aspect = (float) w / h;
            this.nanos = nanos;
            this.nativeEffects = Session.nativeWorld;
        }

        void layerDone() {
            if (pending.decrementAndGet() == 0) {
                link.publish(seq, w, h, (hasWorld ? FrameLink.FLAG_WORLD : 0) | (nativeEffects ? FrameLink.FLAG_NATIVE_EFFECTS : 0), yaw, pitch, fov, aspect, nanos);
                inFlight.decrementAndGet();
                framesCaptured++;
                publishNanosSum += System.nanoTime() - nanos;
                if (++captured == 1) PzCraftMain.LOG.info("first overlay frame published ({}x{})", w, h);
            }
        }

        void fail(Throwable t) {
            PzCraftMain.LOG.error("frame capture failed", t);
            inFlight.decrementAndGet();
        }
    }

    /** Start of a frame: remember the camera this frame is about to be rendered through. */
    static void begin(float yawDeg, float pitchDeg, float fovDeg) {
        collect(false);
        if (job != null) job.layerDone(); // the last frame never reached its HUD capture: publish what it has
        job = null;
        framesSeen++;
        long now = System.nanoTime();
        if (now - statsStart > 5_000_000_000L) {
            PzCraftMain.LOG.info("frame stats: {} rendered/s, {} published/s, publish latency {} ms",
                    String.format("%.1f", framesSeen * 1e9 / (now - statsStart)), String.format("%.1f", framesCaptured * 1e9 / (now - statsStart)),
                    framesCaptured > 0 ? String.format("%.1f", publishNanosSum / 1e6 / framesCaptured) : "-");
            statsStart = now;
            framesSeen = framesCaptured = publishNanosSum = 0;
        }
        yaw = yawDeg;
        pitch = pitchDeg;
        fov = fovDeg;
        frameNanos = System.nanoTime();
    }

    static void camera(float yawDeg, float pitchDeg) { yaw = yawDeg; pitch = pitchDeg; }

    /** The level has just been drawn into the main target: snapshot it, then clear it so the hand and HUD draw alone. */
    static void captureWorld(Minecraft mc) {
        Job j = newJob(mc);
        RenderTarget target = mc.getMainRenderTarget();
        if (j == null) {
            if (Session.nativeWorld) clearColor(target);
            return;
        }
        job = j;
        // Always sent on 1.20.1: with native blocks (PZ draws them) Minecraft's terrain pass is skipped
        // (LevelRenderer120Mixin), so this layer holds what 1.20.1 does not export natively yet: entities, block
        // entities, particles. PZ draws it over its scene when the frame says FLAG_NATIVE_EFFECTS (Job.nativeEffects).
        j.hasWorld = true;
        j.pending.incrementAndGet();
        readLayer(j, target, FrameLink.WORLD);
        clearColor(target);
    }

    /** End of the frame: the hand and HUD (on their own when the world layer was split off before). */
    static void captureHud(Minecraft mc) {
        Job j = job;
        job = null;
        if (j == null) j = newJob(mc);
        if (j == null) return;
        j.pending.incrementAndGet();
        readLayer(j, mc.getMainRenderTarget(), FrameLink.HUD);
        j.layerDone(); // the frame is fully issued
    }

    private static Job newJob(Minecraft mc) {
        FrameLink link = Session.frames;
        if (DISABLED || link == null || inFlight.get() >= FrameLink.SLOTS - 1 || !Session.pzConnected
                || !Session.released && !(net.minecraft.client.Minecraft.getInstance().player != null
                && net.minecraft.client.Minecraft.getInstance().player.isDeadOrDying())) return null;
        RenderTarget target = mc.getMainRenderTarget();
        if (target.frameBufferId <= 0 || target.getColorTextureId() <= 0) return null;
        int w = target.width, h = target.height;
        if (w <= 0 || h <= 0 || w > FrameLink.MAX_WIDTH || h > FrameLink.MAX_HEIGHT) {
            if (!warnedSize) {
                warnedSize = true;
                PzCraftMain.LOG.warn("overlay frames are limited to {}x{}, the render target is {}x{}; no overlay until it fits",
                        FrameLink.MAX_WIDTH, FrameLink.MAX_HEIGHT, w, h);
            }
            return null;
        }
        inFlight.incrementAndGet();
        return new Job(link, w, h, yaw, pitch, fov, frameNanos);
    }

    /** Clear only the colour of the main target (the hand still depth-tests against its own clear). */
    private static void clearColor(RenderTarget target) {
        target.bindWrite(false);
        RenderSystem.clearColor(0f, 0f, 0f, 0f);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private static void readLayer(Job j, RenderTarget target, int layer) {
        int size = j.w * j.h * 4;
        int[] buffer = takeBuffer(size);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer[0]);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glReadPixels(0, 0, j.w, j.h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            readbacks.add(new Readback(j, layer, buffer, fence, framesSeen));
        } catch (Throwable t) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            freeBuffers.add(buffer);
            j.fail(t);
        } finally {
            target.bindWrite(false); // GL_FRAMEBUFFER (read and draw) back on the main target
        }
    }

    private static int[] takeBuffer(int size) {
        for (var it = freeBuffers.iterator(); it.hasNext(); ) {
            int[] b = it.next();
            if (b[1] == size) { it.remove(); return b; }
        }
        // a resize leaves buffers of the old size: drop them
        while (freeBuffers.size() > 4) GL15.glDeleteBuffers(freeBuffers.poll()[0]);
        int id = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        return new int[] {id, size};
    }

    /** Copy every finished read-back into the frame link, oldest first; with wait, also the unfinished ones. */
    private static void collect(boolean waitAll) {
        Readback r;
        while ((r = readbacks.peek()) != null) {
            boolean late = waitAll || framesSeen - r.issuedFrame() >= MAX_FRAMES_BEHIND;
            int status = GL32.glClientWaitSync(r.fence(), late ? GL32.GL_SYNC_FLUSH_COMMANDS_BIT : 0, late ? 1_000_000_000L : 0L);
            if (status == GL32.GL_TIMEOUT_EXPIRED && !late) return;
            readbacks.poll();
            GL32.glDeleteSync(r.fence());
            try {
                if (status == GL32.GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed");
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, r.buffer()[0]);
                ByteBuffer src = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, r.buffer()[1], null);
                if (src == null) throw new IllegalStateException("glMapBuffer returned null");
                ByteBuffer dst = r.job().link.beginLayer(r.job().seq, r.layer(), r.job().w, r.job().h);
                dst.put(src);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                r.job().layerDone();
            } catch (Throwable t) {
                r.job().fail(t);
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                freeBuffers.add(r.buffer());
            }
        }
    }
}
