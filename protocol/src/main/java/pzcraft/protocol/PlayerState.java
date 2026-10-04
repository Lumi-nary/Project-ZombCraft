package pzcraft.protocol;

/** Minecraft -> PZ: Steve's pose, in Minecraft coordinates. One copy per frame via a seqlock slot. */
public final class PlayerState {
    public static final int FLAG_ON_GROUND = 1;
    public static final int FLAG_SNEAKING = 1 << 1;
    public static final int FLAG_SPRINTING = 1 << 2;
    public static final int FLAG_MENU_OPEN = 1 << 3;

    public long tick;
    public double x, y, z;
    public float yawDeg, pitchDeg;
    public int flags;
    /** Steve's camera eye above his feet this frame (1.62 standing, lower sneaking/swimming), in blocks. 0 = unknown. */
    public float eyeHeight;
    public static final int CAMERA_FIRST = 0, CAMERA_BACK = 1, CAMERA_FRONT = 2;
    /** Final vanilla camera, including boom clipping. Independent of the player's aiming direction. */
    public int cameraMode;
    public double cameraX, cameraY, cameraZ;
    public float cameraYawDeg, cameraPitchDeg;
    public boolean cameraValid;
    /** Aiming down sights, 0 (hip) to 1 (fully aimed): PZ narrows Viewpoint's field of view with it. Carried by the entity frame. */
    public float aim;

    public boolean onGround() { return (flags & FLAG_ON_GROUND) != 0; }

    public void set(PlayerState o) {
        tick = o.tick; x = o.x; y = o.y; z = o.z; yawDeg = o.yawDeg; pitchDeg = o.pitchDeg; flags = o.flags;
        eyeHeight = o.eyeHeight;
        cameraMode = o.cameraMode; cameraX = o.cameraX; cameraY = o.cameraY; cameraZ = o.cameraZ;
        cameraYawDeg = o.cameraYawDeg; cameraPitchDeg = o.cameraPitchDeg; cameraValid = o.cameraValid; aim = o.aim;
    }

    @Override
    public String toString() {
        return String.format("PlayerState[tick=%d pos=(%.2f,%.2f,%.2f) yaw=%.1f pitch=%.1f flags=%d eye=%.2f]",
                tick, x, y, z, yawDeg, pitchDeg, flags, eyeHeight);
    }
}
