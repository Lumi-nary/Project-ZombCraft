package pzcraft.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * View recoil with the mechanics of the reference gun's player recoil controller (single-shot form): a shot turns the
 * look up and a little to the right by a fraction of the amplitude in a short linear phase, then eases back with
 * {@code 1-(1-t)^2.35}. PZ owns the mouse look (it is absolute input every frame), so the recoil is an offset added to
 * that input: the player's real look, and with it the next shot and the camera, is {@code input + offset}. The return gives
 * back only what the player did not already take back with the mouse. Numbers come from the M9 data (amplitude 3.0, half
 * of that aimed) and can be changed in {@code ~/.pzcraft/tuning.json} ({@code recoil.*}).
 */
final class GunRecoil {
    private enum State { IDLE, RECOILING, RESETTING }

    private static final double BASE_MIDDLE = 1.0 - Math.sqrt(2.0) / 2.0;
    private static final double EASE_POWER = 2.35;

    private static State state = State.IDLE;
    private static long startTime, transitionNanos, completeAt, lastShot, lastFrame = System.nanoTime();
    private static double progress, prevProgress, amplitude, forwardLimit, startPitch, initialPitch, maxPitch;
    private static double accumulatedPitch, accumulatedYaw, trackedPitch, trackedYaw, resetRatio = 1;
    private static double offsetPitch, offsetYaw, peakPitch, peakYaw;
    private static int shots;
    private static boolean hasTracked, hadAccumulated, pendingStart;
    private static int uninterruptedShots;
    /** The look for this frame, valid after {@link #apply}. */
    static float yaw, pitch;

    private GunRecoil() {}

    /** Degrees added to PZ's look right now (negative pitch is up). */
    static double offsetPitch() { return offsetPitch; }
    static double offsetYaw() { return offsetYaw; }
    static boolean active() { return state != State.IDLE; }
    /** Largest upward offset (most negative pitch) and rightward yaw since the last shot, and shots seen: for tests. */
    static double peakPitch() { return peakPitch; }
    static double peakYaw() { return peakYaw; }
    static int shots() { return shots; }

    /** A shot PZ confirmed. {@code aiming} halves the kick like the reference. */
    static void fired(boolean aiming) { fired(aiming, 1.0); }

    /** {@code gunScale}: this gun's kick relative to the M9 (PZ recoil delay / 12, the M9's own). */
    static void fired(boolean aiming, double gunScale) {
        long now = System.nanoTime();
        shots++; peakPitch = peakYaw = 0;
        // shots less than a firing cycle apart belong to one uninterrupted burst
        uninterruptedShots = now - lastShot < (long) (Tuning.get("recoil.burstGapMs", 250) * 1e6) ? uninterruptedShots + 1 : 1;
        lastShot = now;
        forwardLimit = BASE_MIDDLE / 7.0;
        maxPitch = -Math.abs(Tuning.get("recoil.maxPitch", 20));
        state = State.RECOILING;
        startTime = now;
        transitionNanos = (long) (Tuning.get("recoil.durationMs", 100) * 7 * 1e6);
        progress = prevProgress = 0;
        amplitude = Tuning.get("recoil.amplitude", 3.0) * gunScale * (aiming ? Tuning.get("recoil.adsMultiplier", .5) : 1);
        pendingStart = true;                     // the starting look is read in apply(), where the live input is known
        completeAt = now + (long) (Tuning.get("recoil.completeMs", 75) * 1e6);
    }

    /**
     * Once per rendered frame, before the frame is drawn: combine PZ's absolute look with the recoil offset. Writes
     * {@link #yaw} and {@link #pitch}.
     */
    static void apply(Minecraft mc, float inYaw, float inPitch) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        if (state != State.IDLE && !mc.options.getCameraType().isFirstPerson()) { state = State.IDLE; pendingStart = false; }
        if (state == State.IDLE) {
            // what is left of an offset (the mouse took part of it back) settles instead of lingering as a mismatch
            double settle = Math.exp(-dt * 6);
            offsetPitch *= settle; offsetYaw *= settle;
            if (Math.abs(offsetPitch) < 1e-3 && Math.abs(offsetYaw) < 1e-3) offsetPitch = offsetYaw = 0;
            hasTracked = false;
            out(inYaw, inPitch);
            return;
        }
        double currentPitch = inPitch + offsetPitch, currentYaw = inYaw + offsetYaw;
        if (pendingStart) {
            pendingStart = false;
            startPitch = currentPitch;
            if (uninterruptedShots <= 1 || startPitch > initialPitch) {
                initialPitch = startPitch;
                accumulatedPitch = accumulatedYaw = 0;
                trackedPitch = startPitch;
                trackedYaw = currentYaw;
                hasTracked = true;
                hadAccumulated = false;
            }
            if (uninterruptedShots > 1 && !(Math.abs(startPitch - initialPitch) >= Math.abs(maxPitch))) {
                resetRatio = 0.1;
                hadAccumulated = true;
            } else resetRatio = 1;
        }
        if (state == State.RECOILING && now >= completeAt) startImmediateReturn(now);
        if (hasTracked) {
            accumulatedPitch = Math.min(0, accumulatedPitch + Math.abs(currentPitch - trackedPitch));
            accumulatedYaw = Math.max(0, accumulatedYaw - Math.abs(currentYaw - trackedYaw));
        }
        trackedPitch = currentPitch; trackedYaw = currentYaw; hasTracked = true;
        prevProgress = progress;
        progress = Math.min(1.0, (now - startTime) / (double) transitionNanos);
        if (state == State.RECOILING && progress >= 1.0) {
            if (!hadAccumulated || !(accumulatedPitch < 0) && !(accumulatedYaw > 0)) {
                state = State.IDLE; accumulatedPitch = accumulatedYaw = 0;
            } else {
                state = State.RESETTING;
                startTime = now;
                progress = prevProgress = 0;
                transitionNanos = 1_400_000_000L;
            }
        }
        double dPitch = 0, dYaw = 0;
        if (state == State.RESETTING) {
            double eased = ease(progress), easedPrev = ease(prevProgress);
            double remaining = Math.max(1.0 - easedPrev, 1e-6);
            dPitch = -accumulatedPitch * (eased - easedPrev) / remaining;
            dYaw = -accumulatedYaw * (eased - easedPrev) / remaining;
            double newPitch = clampPitch(currentPitch + dPitch), newYaw = currentYaw + dYaw;
            accumulatedPitch = Math.min(accumulatedPitch + (newPitch - currentPitch), 0);
            accumulatedYaw = Math.max(accumulatedYaw + (newYaw - currentYaw), 0);
            offsetPitch += newPitch - currentPitch; offsetYaw += newYaw - currentYaw;
            trackedPitch = newPitch; trackedYaw = newYaw;
            if (progress >= 1.0 || accumulatedPitch == 0 && accumulatedYaw == 0) {
                state = State.IDLE; accumulatedPitch = accumulatedYaw = 0;
            }
        } else if (state == State.RECOILING) {
            dPitch = pitchOffset(progress) - pitchOffset(prevProgress);
            dYaw = yawOffset(progress) - yawOffset(prevProgress);
            double newPitch = clampPitch(currentPitch + dPitch), newYaw = currentYaw + dYaw;
            if (newPitch < startPitch || dPitch <= 0) {
                accumulatedPitch = Math.min(accumulatedPitch + (newPitch - currentPitch), 0);
                offsetPitch += newPitch - currentPitch;
                trackedPitch = newPitch;
            }
            accumulatedYaw = Math.max(accumulatedYaw + (newYaw - currentYaw), 0);
            offsetYaw += newYaw - currentYaw;
            trackedYaw = newYaw;
        }
        out(inYaw, inPitch);
    }

    private static void out(float inYaw, float inPitch) {
        peakPitch = Math.min(peakPitch, offsetPitch); peakYaw = Math.max(peakYaw, offsetYaw);
        yaw = (float) (inYaw + offsetYaw);
        pitch = (float) Mth.clamp(inPitch + offsetPitch, -90.0, 90.0);
    }

    private static void startImmediateReturn(long now) {
        if (state != State.RECOILING || progress >= 1.0 || accumulatedPitch >= 0 && accumulatedYaw <= 0) return;
        state = State.RESETTING;
        startTime = now;
        progress = prevProgress = 0;
        double base = Math.max(Tuning.get("recoil.durationMs", 100) * 7 * 1e6 * (1.0 - BASE_MIDDLE), 300_000_000.0);
        double normalizedPitch = Math.min(1.0, Math.abs(accumulatedPitch) / Math.max(amplitude, 1e-6));
        double normalizedYaw = Math.min(1.0, accumulatedYaw / Math.max(amplitude * 0.18, 1e-6));
        double scale = 0.9 + 0.1 * Math.sqrt(Math.max(normalizedPitch, normalizedYaw));
        transitionNanos = Math.max((long) (base * scale), 300_000_000L);
    }

    private static double clampPitch(double value) { return Mth.clamp(value, -90.0, 90.0); }

    private static double returnProgress(double t) { return Math.max(0, Math.min(1, (t - forwardLimit) / (1 - forwardLimit))); }

    private static double pitchOffset(double t) {
        return t <= forwardLimit ? -amplitude * t / forwardLimit : -amplitude + resetRatio * amplitude * ease(returnProgress(t));
    }

    private static double yawOffset(double t) {
        double yaw = amplitude * Tuning.get("recoil.yawBias", 0.18);
        return t <= forwardLimit ? yaw * t / forwardLimit : yaw - resetRatio * yaw * ease(returnProgress(t));
    }

    private static double ease(double t) { return 1.0 - Math.pow(1.0 - t, EASE_POWER); }
}
