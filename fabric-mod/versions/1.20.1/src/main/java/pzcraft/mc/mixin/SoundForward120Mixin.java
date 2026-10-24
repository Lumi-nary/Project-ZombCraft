package pzcraft.mc.mixin;

import java.util.List;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pzcraft.mc.McSounds;

/**
 * 1.20.1 version of SoundForwardMixin: one-shot positional sounds go to PZ (McSounds) instead of OpenAL; loops, ticking
 * and streamed sounds still play here. 1.20.1's play returns nothing, notifies subtitle listeners inline (no range
 * argument) and takes the volume as (volume, source).
 */
@Mixin(SoundEngine.class)
public abstract class SoundForward120Mixin {
    @Shadow private boolean loaded;
    @Shadow @Final private SoundManager soundManager;
    @Shadow @Final private List<SoundEventListener> listeners;
    @Shadow private float calculateVolume(float volume, SoundSource source) { throw new AssertionError(); }
    @Shadow private float calculatePitch(SoundInstance instance) { throw new AssertionError(); }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void pzcraft$toPz(SoundInstance instance, CallbackInfo ci) {
        if (!loaded || !McSounds.active() || instance instanceof TickableSoundInstance || instance.isLooping() || !instance.canPlaySound()) return;
        WeighedSoundEvents event = instance.resolve(soundManager);
        Sound sound = instance.getSound();
        if (event == null || sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND
                || sound.shouldStream()) return;
        float distance = Math.max(instance.getVolume(), 1.0F) * sound.getAttenuationDistance();
        for (SoundEventListener listener : listeners) listener.onPlaySound(instance, event);
        float volume = calculateVolume(instance.getVolume(), instance.getSource());
        if (volume <= 0) { ci.cancel(); return; }
        boolean relative = instance.isRelative() || instance.getAttenuation() != SoundInstance.Attenuation.LINEAR;
        if (McSounds.forward(sound.getPath(), instance.getX(), instance.getY(), instance.getZ(), volume, calculatePitch(instance), distance, relative))
            ci.cancel();
    }
}
