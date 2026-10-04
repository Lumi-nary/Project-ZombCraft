package pzcraft.mc.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.CollisionField;

/**
 * Adds PZ's collision boxes to everything the entity movement code collides with, on both the client and the
 * integrated server (they share a JVM), so prediction and the server's authoritative step agree.
 */
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
    @Inject(
            method = "collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true)
    private static void pzcraft$addPzBoxes(Entity source, Level level, List<VoxelShape> entityColliders, AABB area,
                                           CallbackInfoReturnable<List<VoxelShape>> cir) {
        pzcraft$merge(area, cir);
    }

    @Inject(
            method = "collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true)
    private static void pzcraft$addPzBoxesCtx(CollisionContext source, Level level, List<VoxelShape> entityColliders, AABB area,
                                              CallbackInfoReturnable<List<VoxelShape>> cir) {
        pzcraft$merge(area, cir);
    }

    private static void pzcraft$merge(AABB area, CallbackInfoReturnable<List<VoxelShape>> cir) {
        List<VoxelShape> extra = new ArrayList<>(4);
        CollisionField.collect(area, extra);
        if (extra.isEmpty()) return;
        List<VoxelShape> all = new ArrayList<>(cir.getReturnValue().size() + extra.size());
        all.addAll(cir.getReturnValue());
        all.addAll(extra);
        cir.setReturnValue(all);
    }
}

