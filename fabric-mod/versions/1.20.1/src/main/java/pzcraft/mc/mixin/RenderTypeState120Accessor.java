package pzcraft.mc.mixin;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface RenderTypeState120Accessor {
    @Accessor("state") RenderType.CompositeState pzcraft$state();
}
