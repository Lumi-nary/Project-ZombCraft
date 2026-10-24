package pzcraft.mc.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.List;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.PostAlpha120;
import pzcraft.mc.PzCraftMain;
import pzcraft.mc.Session;

/**
 * Every post chain that writes the screen target, whoever runs it (GameRenderer's effect, or a mod calling
 * PostChain.process itself, as Viltrumite Core does), keeps PZ's world visible: PostAlpha120 restores the alpha the
 * chain overwrote. Chains that only write their own targets (the glowing-entity outline) are left alone. If the
 * restore cannot run, the chain is skipped while PZ is connected, as before, rather than blacking out PZ.
 */
@Mixin(PostChain.class)
public abstract class PostChain120Mixin {
    @Shadow @Final private RenderTarget screenTarget;
    @Shadow @Final private List<PostPass> passes;
    @Shadow public abstract String getName();
    @Unique private boolean pzcraft$restore;
    @Unique private static final java.util.Set<String> pzcraft$reported = new java.util.HashSet<>();

    @Inject(method = "process", at = @At("HEAD"), cancellable = true)
    private void pzcraft$saveAlpha(float partialTick, CallbackInfo ci) {
        pzcraft$restore = false;
        if (!Session.pzConnected || !pzcraft$writesScreen()) return;
        if (PostAlpha120.before(screenTarget)) {
            pzcraft$restore = true;
            pzcraft$report("Post effect {} runs over PZ's world (alpha kept)");
        } else {
            pzcraft$report("Post effect {} skipped: it would cover PZ's world");
            ci.cancel();
        }
    }

    @Inject(method = "process", at = @At("TAIL"))
    private void pzcraft$restoreAlpha(float partialTick, CallbackInfo ci) {
        if (pzcraft$restore) PostAlpha120.after(screenTarget);
        pzcraft$restore = false;
    }

    @Unique
    private boolean pzcraft$writesScreen() {
        for (PostPass pass : passes) if (pass.outTarget == screenTarget) return true;
        return false;
    }

    @Unique
    private void pzcraft$report(String message) {
        if (pzcraft$reported.add(message + getName())) PzCraftMain.LOG.info(message, getName());
    }
}
