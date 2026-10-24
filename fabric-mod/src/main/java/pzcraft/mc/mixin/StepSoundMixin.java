package pzcraft.mc.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import pzcraft.mc.PzFloorSounds;
import pzcraft.mc.Session;

/** A player's footsteps on a PZ floor sound like its material, not like the ground under it ({@link PzFloorSounds}). */
@Mixin(Entity.class)
public abstract class StepSoundMixin {
    @ModifyVariable(method = "vibrationAndSoundEffectsFromBlock", at = @At("HEAD"), argsOnly = true)
    private BlockState pzcraft$pzFloor(BlockState state) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player) || !Session.pzConnected) return state;
        BlockState floor = PzFloorSounds.standIn(self);
        return floor != null ? floor : state;
    }
}
