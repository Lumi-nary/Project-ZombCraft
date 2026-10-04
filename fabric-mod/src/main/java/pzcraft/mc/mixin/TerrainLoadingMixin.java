package pzcraft.mc.mixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import pzcraft.mc.TerrainCoverage;
@Mixin(Entity.class)
public abstract class TerrainLoadingMixin {
    @ModifyVariable(method="move", at=@At("HEAD"), argsOnly=true)
    private Vec3 pzcraft$knownTerrain(Vec3 motion) { return TerrainCoverage.guard((Entity)(Object)this,motion); }
}

