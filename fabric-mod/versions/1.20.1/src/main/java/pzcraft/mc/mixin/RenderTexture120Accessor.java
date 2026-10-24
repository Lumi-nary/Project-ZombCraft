package pzcraft.mc.mixin;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderType.CompositeState.class)
public interface RenderTexture120Accessor {
    @Accessor("textureState") RenderStateShard.EmptyTextureStateShard pzcraft$texture();
}
