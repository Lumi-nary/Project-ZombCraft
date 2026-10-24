package pzcraft.pz;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import pzcraft.protocol.McSound;
import pzcraft.protocol.PlayerState;

/**
 * Minecraft's one-shot sounds played by PZ's own audio (McSound): each .ogg is loaded into FMOD once and played in PZ's
 * in-game sound group (so PZ's volume settings apply and the sound comes out of PZ's window), quieter with distance from
 * Steve's camera the way Minecraft fades them (linear to the sound's range). Played without panning, as PZ plays its own
 * file sounds. If FMOD cannot load them, Minecraft is told to play its sounds itself again. Game thread, except {@link #receive}.
 *
 * <p>FMOD only mixes a few dozen channels for real and makes the rest virtual (silent), quietest first. A burst of Minecraft
 * sounds (mining, explosions, a fight) used to fill those slots at default priority, so PZ's sounds and the newest Minecraft
 * ones went silent together. Now at most {@link #MAX_CHANNELS} of ours play at once (the oldest is cut), the same file
 * starts at most twice per frame, and ours play at a higher priority than PZ's default so they are not the ones dropped.
 */
final class SoundBridge {
    /** FMOD_LOOP_OFF | FMOD_2D. */
    private static final long MODE = 0x1 | 0x8;
    private static final int MAX_CHANNELS = 24, SAME_FILE_PER_FRAME = 2;
    /** FMOD priority: 0 most important, 128 default, 256 least. */
    private static final int PRIORITY = 64;
    private static final ArrayDeque<Long> channels = new ArrayDeque<>();
    private static final Map<String, Integer> thisFrame = new HashMap<>();
    private static final ConcurrentLinkedQueue<McSound.Play> queue = new ConcurrentLinkedQueue<>();
    private static final Map<String, Long> sounds = new HashMap<>();
    private static final PlayerState steve = new PlayerState();
    private static boolean failed;
    private static volatile int played, unheard, loadFailures, cut, merged, virtual;
    private static volatile String last = "";

    private SoundBridge() {}

    /** Link thread. */
    static void receive(byte[] payload) {
        if (!failed && queue.size() < 128) queue.add(McSound.Play.decode(payload));
    }

    /** Game thread, once per frame. */
    static void tick() {
        McSound.Play p;
        boolean haveCamera = false;
        thisFrame.clear();
        prune();
        while ((p = queue.poll()) != null) {
            if (failed) { queue.clear(); return; }
            try {
                float volume = p.volume();
                if (!p.relative()) {
                    if (!haveCamera) haveCamera = LinkService.pullInto(steve);
                    if (haveCamera) {
                        double d = Math.sqrt(Math.pow(p.x() - steve.cameraX, 2) + Math.pow(p.y() - steve.cameraY, 2) + Math.pow(p.z() - steve.cameraZ, 2));
                        volume *= (float) Math.max(0, 1 - d / Math.max(1, p.range()));
                    }
                }
                if (volume <= 0.001f) { unheard++; continue; }
                int same = thisFrame.merge(p.file(), 1, Integer::sum);
                if (same > SAME_FILE_PER_FRAME) { merged++; continue; }
                while (channels.size() >= MAX_CHANNELS) { fmod.javafmod.FMOD_Channel_Stop(channels.pollFirst()); cut++; }
                long sound = sound(p.file());
                if (sound <= 0) continue;
                long channel = fmod.javafmod.FMOD_System_PlaySound(sound, true);
                if (channel <= 0) continue;
                fmod.javafmod.FMOD_Channel_SetChannelGroup(channel, fmod.fmod.FMODManager.instance.channelGroupInGameNonBankSounds);
                fmod.javafmod.FMOD_Channel_SetVolume(channel, Math.min(volume, 2f));
                fmod.javafmod.FMOD_Channel_SetPitch(channel, p.pitch());
                fmod.javafmod.FMOD_Channel_SetPriority(channel, PRIORITY);
                fmod.javafmod.FMOD_Channel_SetPaused(channel, false);
                channels.addLast(channel);
                played++;
                last = p.file();
            } catch (Throwable t) {
                fail("FMOD refused Minecraft's sound: " + t);
            }
        }
    }

    /** Forgets channels that finished (FMOD reuses their handles' slots); counts ours that FMOD made silent. */
    private static void prune() {
        if (channels.isEmpty()) return;
        int silent = 0;
        for (var it = channels.iterator(); it.hasNext(); ) {
            long c = it.next();
            if (!fmod.javafmod.FMOD_Channel_IsPlaying(c)) it.remove();
            else if (fmod.javafmod.FMOD_Channel_IsVirtual(c)) silent++;
        }
        virtual = silent;
    }

    private static long sound(String file) {
        Long cached = sounds.get(file);
        if (cached != null) return cached;
        long sound = fmod.javafmod.FMOD_System_CreateSound(file, MODE);
        if (sound <= 0) {
            loadFailures++;
            if (loadFailures >= 3 && played == 0) fail("FMOD could not load Minecraft's sound files (" + file + ")");
        }
        if (sounds.size() < 4096) sounds.put(file, sound);
        return sound;
    }

    private static void fail(String why) {
        if (failed) return;
        failed = true;
        last = why;
        Log.info("Minecraft sounds stay in Minecraft: " + why);
        LinkService.send(McSound.MSG_SOUND_FAILED, why.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("failed", failed);
        m.put("played", played);
        m.put("tooFar", unheard);
        m.put("loadFailures", loadFailures);
        m.put("files", sounds.size());
        m.put("playing", channels.size());
        m.put("virtualNow", virtual);
        m.put("cutOldest", cut);
        m.put("mergedSameFrame", merged);
        m.put("last", last);
        return m;
    }
}
