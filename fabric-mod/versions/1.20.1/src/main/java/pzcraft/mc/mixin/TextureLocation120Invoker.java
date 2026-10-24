package pzcraft.mc.mixin;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(RenderStateShard.EmptyTextureStateShard.class)
public interface TextureLocation120Invoker {
    @Invoker("cutoutTexture") Optional<ResourceLocation> pzcraft$location();
}
