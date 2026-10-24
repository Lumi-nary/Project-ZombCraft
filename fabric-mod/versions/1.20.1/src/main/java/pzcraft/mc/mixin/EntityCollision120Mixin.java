package pzcraft.mc.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import pzcraft.mc.CollisionField;

/**
 * 1.20.1 version of EntityCollisionMixin: PZ's walls and floors collide like blocks. 1.20.1 has no collectColliders;
 * every move (and step-up) goes through Entity.collideBoundingBox with the entity colliders as its list, so PZ's boxes
 * are added to that list.
 */
@Mixin(Entity.class)
public abstract class EntityCollision120Mixin {
    @ModifyVariable(method = "collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"), argsOnly = true)
    private static List<VoxelShape> pzcraft$addPzBoxes(List<VoxelShape> entityColliders, Entity entity, Vec3 move, AABB box, Level level) {
        List<VoxelShape> extra = new ArrayList<>(4);
        CollisionField.collect(box.expandTowards(move), extra);
        if (extra.isEmpty()) return entityColliders;
        List<VoxelShape> all = new ArrayList<>(entityColliders.size() + extra.size());
        all.addAll(entityColliders);
        all.addAll(extra);
        return all;
    }
}
