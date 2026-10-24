package pzcraft.mc.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.EntitySceneExporter;
import pzcraft.mc.Session;

/**
 * With native blocks PZ draws the terrain and Minecraft's chunk layers are skipped (LevelRenderer120Mixin), so vanilla's
 * mining cracks had no block under them: they were blended into the empty, transparent video layer over PZ's picture.
 * EntitySceneExporter sends them to PZ as decals on the native block instead (as 26.x does).
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class Crumbling120Mixin {
    @Inject(method = "renderBreakingTexture", at = @At("HEAD"), cancellable = true)
    private void pzcraft$nativeCracks(BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose, VertexConsumer out, CallbackInfo ci) {
        if (Session.nativeWorld && EntitySceneExporter.ready()) ci.cancel();
    }
}
