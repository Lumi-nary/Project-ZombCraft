package pzcraft.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import pzcraft.protocol.FrameLink;

/**
 * Native mode copies only the transparent HUD. Video fallback keeps separate world and hand/HUD layers;
 * PZ re-projects its world layer to the current camera.
 */
final class FrameCapture {
    /** Frames whose read-backs have been issued but not yet published; the link has room for FrameLink.SLOTS - 1. */
    private static final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private static Job job;
    private static long captured;
    private static float yaw, pitch, fov;
    private static long frameNanos;
    private static boolean warnedSize;
    private static final boolean DISABLED = System.getenv("PZCRAFT_NO_CAPTURE") != null; // A/B testing the cost of the read-back
    private static long statsStart = System.nanoTime(), framesSeen, framesCaptured, publishNanosSum;

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
                if (++captured == 1) PzCraftClient.LOG.info("first overlay frame published ({}x{})", w, h);
            }
        }

        void fail(Throwable t) {
            PzCraftClient.LOG.error("frame capture failed", t);
            inFlight.decrementAndGet();
        }
    }

    /** Start of a frame: remember the camera this frame is about to be rendered through. */
    static void begin(float yawDeg, float pitchDeg, float fovDeg) {
        if (job != null) job.layerDone(); // the last frame never reached its HUD capture: publish what it has
        job = null;
        framesSeen++;
        long now = System.nanoTime();
        if (now - statsStart > 5_000_000_000L) {
            PzCraftClient.LOG.info("frame stats: {} rendered/s, {} published/s, publish latency {} ms",
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

    /** The level has just been drawn into the main target: snapshot it, then clear it so the hand and HUD draw alone. */
    static void camera(float yawDeg, float pitchDeg) { yaw = yawDeg; pitch = pitchDeg; }

    static void captureWorld(Minecraft mc) {
        Job j = newJob(mc);
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        if (j == null) {
            if(Session.nativeWorld)RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(),new Vector4f(0f,0f,0f,0f));
            return;
        }
        job = j;
        if(!Session.nativeWorld) {
            j.hasWorld = true;
            j.pending.incrementAndGet();
            readLayer(j, target.getColorTexture(), FrameLink.WORLD);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), new Vector4f(0f, 0f, 0f, 0f));
    }

    /** End of the frame: the hand and HUD (on their own when the world layer was split off before). */
    static void captureHud(Minecraft mc) {
        Job j = job;
        job = null;
        if (j == null) j = newJob(mc);
        if (j == null) return;
        j.pending.incrementAndGet();
        readLayer(j, mc.gameRenderer.mainRenderTarget().getColorTexture(), FrameLink.HUD);
        j.layerDone(); // the frame is fully issued
    }

    private static Job newJob(Minecraft mc) {
        FrameLink link = Session.frames;
        if (DISABLED || link == null || inFlight.get() >= FrameLink.SLOTS - 1 || !Session.pzConnected
                || !Session.released && !(net.minecraft.client.Minecraft.getInstance().player != null
                && net.minecraft.client.Minecraft.getInstance().player.isDeadOrDying())) return null;
        RenderTarget target = mc.gameRenderer.mainRenderTarget();
        GpuTexture tex = target.getColorTexture();
        if (tex == null) return null;
        int w = target.width, h = target.height;
        if (w <= 0 || h <= 0 || w > FrameLink.MAX_WIDTH || h > FrameLink.MAX_HEIGHT) {
            if (!warnedSize) {
                warnedSize = true;
                PzCraftClient.LOG.warn("overlay frames are limited to {}x{}, the render target is {}x{}; no overlay until it fits",
                        FrameLink.MAX_WIDTH, FrameLink.MAX_HEIGHT, w, h);
            }
            return null;
        }
        if ((long) w * h * tex.getFormat().blockSize() != (long) w * h * 4) return null; // expect RGBA8
        inFlight.incrementAndGet();
        return new Job(link, w, h, yaw, pitch, fov, frameNanos);
    }

    private static void readLayer(Job j, GpuTexture tex, int layer) {
        long size = (long) j.w * j.h * 4;
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "pzcraft frame layer " + layer, GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
                ByteBuffer dst = j.link.beginLayer(j.seq, layer, j.w, j.h);
                dst.put(read.data());
                j.layerDone();
            } catch (Throwable t) {
                j.fail(t);
            } finally {
                buffer.close();
            }
        }, 0);
    }
}

