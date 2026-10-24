package pzcraft.mc.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.ActorProxies;

/**
 * Marks Steve's melee swings on the server, so ActorProxies applies reach, line of sight and PZ's swing cap to swings
 * only, and other mods' area attacks with a player damage source (Viltrumite) reach PZ's zombies. ActorProxies resets
 * the count every tick, in case a swing never returns here.
 */
@Mixin(Player.class)
public abstract class PlayerMelee120Mixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void pzcraft$swingStart(Entity target, CallbackInfo ci) {
        if (!((Player) (Object) this).level().isClientSide) ActorProxies.meleeDepth++;
    }

    @Inject(method = "attack", at = @At("RETURN"))
    private void pzcraft$swingEnd(Entity target, CallbackInfo ci) {
        if (!((Player) (Object) this).level().isClientSide && ActorProxies.meleeDepth > 0) ActorProxies.meleeDepth--;
    }
}
