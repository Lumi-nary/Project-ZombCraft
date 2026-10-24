package pzcraft.mc.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sleeping in a PZ bed starts the sleep timer at 0, as Player.startSleepInBed does for a bed block. */
@Mixin(Player.class)
public interface PlayerSleepAccessor {
    @Accessor("sleepCounter")
    void pzcraft$setSleepCounter(int ticks);
}
