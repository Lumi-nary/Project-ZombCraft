package pzcraft.pz;

/**
 * Aiming down sights narrows Viewpoint's first-person field of view by this much at full aim (Minecraft sends the aim amount).
 * Public on purpose: ZombieBuddy inlines the advice that calls this into Viewpoint's own class, which must be able to reach it.
 */
public final class AdsZoom {
    static final double ZOOM = 1.35;

    private AdsZoom() {}

    /** Viewpoint's vertical field of view in radians for this frame. Never throws: it runs inside Viewpoint's camera code. */
    public static float fov(float fov) {
        try {
            var pose = NativeEntities.camera();
            float aim = pose == null ? 0f : Math.max(0f, Math.min(1f, pose.aim));
            if (aim <= 0.001f) return fov;
            double zoom = 1 + aim * (ZOOM - 1);
            return (float) (2 * Math.atan(Math.tan(fov / 2.0) / zoom));
        } catch (Throwable t) {
            return fov;
        }
    }
}

