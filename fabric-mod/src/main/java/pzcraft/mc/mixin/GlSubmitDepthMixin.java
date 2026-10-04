package pzcraft.mc.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import pzcraft.mc.Session;

/**
 * Minecraft lets two frames queue on the GPU before it blocks. PZ keeps the GPU saturated, so every queued frame of ours
 * waits its turn behind PZ's work and the overlay gets a picture that is two GPU turnarounds old. Queue one frame less (0 is not possible: the encoder refuses to wait on the submit it just made).
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlSubmitDepthMixin {
    private static final int DEPTH = Integer.getInteger("pzcraft.gpuQueueDepth", 1);

    @ModifyArg(method = "submit",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlCommandEncoder;awaitSubmit(JJ)Z"), index = 0)
    private long pzcraft$shallowQueue(long index) {
        return Session.pzConnected ? index + (2 - Math.max(1, Math.min(2, DEPTH))) : index;
    }
}

