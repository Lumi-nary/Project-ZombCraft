package pzcraft.mc.mixin;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pzcraft.mc.McSounds;

/**
 * Minecraft's one-shot sounds go to PZ, which plays them with its own audio ({@link McSounds}); looping, moving (tickable)
 * and streamed sounds (music, records) are still played here. Subtitles are still told.
 */
@Mixin(SoundEngine.class)
public abstract class SoundForwardMixin {
    @Shadow private boolean loaded;
    @Shadow @org.spongepowered.asm.mixin.Final private SoundManager soundManager;
    @Shadow private float calculateVolume(SoundInstance instance) { throw new AssertionError(); }
    @Shadow private float calculatePitch(SoundInstance instance) { throw new AssertionError(); }
    //#if MC_26_1
    //$ @Shadow @org.spongepowered.asm.mixin.Final private java.util.List<net.minecraft.client.sounds.SoundEventListener> listeners;
    //$ /** SoundEngine.notifyListeners of 26.3 (26.1.2 does it inline in play). */
    //$ private void notifyListeners(SoundInstance instance, WeighedSoundEvents soundEvent, float attenuationDistance) {
    //$     float range = !instance.isRelative() && instance.getAttenuation() != SoundInstance.Attenuation.NONE ? attenuationDistance : Float.POSITIVE_INFINITY;
    //$     for (var listener : listeners) listener.onPlaySound(instance, soundEvent, range);
    //$ }
    //#else
    @Shadow private void notifyListeners(SoundInstance instance, WeighedSoundEvents soundEvent, float attenuationDistance) { throw new AssertionError(); }
    //#endif

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void pzcraft$toPz(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (!loaded || !McSounds.active() || instance instanceof TickableSoundInstance || instance.isLooping() || !instance.canPlaySound()) return;
        //#if MC_26_1
        //$ WeighedSoundEvents event = instance.resolve(soundManager);
        //#else
        WeighedSoundEvents event = instance.getOrResolve(soundManager);
        //#endif
        Sound sound = instance.getSound();
        if (event == null || sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND
                || sound.shouldStream()) return;
        //#if MC_26_1
        //$ float distance = Math.max(instance.getVolume(), 1.0F) * sound.getAttenuationDistance();
        //#else
        float distance = sound.getAttenuationDistance(instance.getVolume());
        //#endif
        notifyListeners(instance, event, distance);
        float volume = calculateVolume(instance);
        if (volume <= 0) { cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED); return; }
        boolean relative = instance.isRelative() || instance.getAttenuation() != SoundInstance.Attenuation.LINEAR;
        if (McSounds.forward(sound.getPath(), instance.getX(), instance.getY(), instance.getZ(), volume, calculatePitch(instance), distance, relative))
            cir.setReturnValue(SoundEngine.PlayResult.STARTED);
    }
}
