package pzcraft.mc.mixin;

import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The progress Minecraft's loading screen shows (its bar), for PZ's own loading screen (McStart). */
@Mixin(LoadingOverlay.class)
public interface LoadingOverlayAccessor {
    @Accessor("currentProgress")
    float pzcraft$progress();
}
