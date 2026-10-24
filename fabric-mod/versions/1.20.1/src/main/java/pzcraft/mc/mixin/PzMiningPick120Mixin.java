package pzcraft.mc.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.PzMining;
@Mixin(GameRenderer.class)
public abstract class PzMiningPick120Mixin {
    @Inject(method="pick",at=@At("TAIL"))
    private void pzcraft$pick(float partialTick,CallbackInfo ci){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null && mc.getCameraEntity()!=null && mc.hitResult!=null) {
            mc.hitResult=PzMining.pick(mc.player,mc.getCameraEntity(),partialTick,mc.hitResult);
            if(!(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult))mc.crosshairPickEntity=null;
        }
    }
}
