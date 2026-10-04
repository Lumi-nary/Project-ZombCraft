package pzcraft.pz;

import java.lang.reflect.Field;
import java.util.Set;
import pzcraft.protocol.PlayerState;
import zombie.characters.IsoGameCharacter;

/**
 * First-person camera height while Steve drives: Viewpoint's eye is put at Steve's own eye, as Minecraft places it
 * (1.62 blocks standing, lower while sneaking, swimming or sleeping, eased between them by Minecraft's camera), centred
 * on him, instead of the PZ character's animated head. Minecraft renders the overlay and aims from the same point, so
 * the picture, the crosshair and what Steve can reach all agree, and no PZ animation (falling, staggering, opening a
 * door, eating) can move the view.
 *
 * <p>Without a Minecraft eye height (an older Minecraft side), Viewpoint's own eye is kept, held steady through PZ
 * animations outside Viewpoint's steady states.
 */
public final class SteveEye {
    /** Viewpoint's own steady states (viewpoint.input.Controls.STEADY_STATES). */
    private static final Set<String> STEADY = Set.of("idle", "movement", "strafe", "run", "sprint", "aim", "maskingleft",
            "maskingright", "turning", "turning180", "turningAim180", "turningIdle180", "turningMovement180");
    private static final float STOREY = 2.4494896f;
    private static Field eyeX, eyeY, eyeZ, eyeLean, camX, camY, camZ;
    private static boolean failed, have;
    private static float offsetX, offsetZ, height, lean;
    private static final PlayerState steve = new PlayerState();
    private static volatile int held;
    private static volatile float lastSteveEye;
    /** Dev toggle ("eye follow" / "eye steady") for A/B comparisons: off = Viewpoint's own PZ-head eye. */
    static volatile boolean enabled = true;

    private SteveEye() {}

    static int heldFrames() { return held; }
    static float steveEyeHeight() { return lastSteveEye; }

    /** Public: ZombieBuddy inlines the advice that calls this into Viewpoint's Controls.eye. */
    public static void after(IsoGameCharacter c, Object frame) {
        if (failed || c == null || frame == null) return;
        try {
            if (eyeX == null) {
                Class<?> f = frame.getClass();
                eyeX = f.getField("eyeX"); eyeY = f.getField("eyeY"); eyeZ = f.getField("eyeZ"); eyeLean = f.getField("eyeLean");
                camX = f.getField("camX"); camY = f.getField("camY"); camZ = f.getField("camZ");
            }
            float dx = c.getX() - camX.getFloat(frame), dy = c.getY() - camY.getFloat(frame);
            float dz = (c.getZ() - camZ.getFloat(frame)) * STOREY;
            boolean steady = STEADY.contains(String.valueOf(c.getCurrentActionContextStateName()));
            boolean drives = enabled && SteveControl.drives(c);
            if (drives && SteveCamera.frame(frame)) {
                if (LinkService.pullInto(steve)) lastSteveEye = steve.eyeHeight;
                return;
            }
            float eye = drives && LinkService.pullInto(steve) ? steve.eyeHeight : 0f;
            if (eye > 0.1f && eye < 3f) {
                // Steve's eye straight above his feet (Controls.put with a zero head offset and Minecraft's height)
                lastSteveEye = eye;
                eyeX.setFloat(frame, -dx);
                eyeY.setFloat(frame, eye + dz);
                eyeZ.setFloat(frame, -dy);
                eyeLean.setFloat(frame, 0f);
                if (!steady) held++;
                return;
            }
            if (steady || !drives) {
                // Viewpoint's own result: remember it as offsets from the player (see Controls.put)
                offsetX = eyeX.getFloat(frame) + dx;
                offsetZ = eyeZ.getFloat(frame) + dy;
                height = eyeY.getFloat(frame) - dz;
                lean = eyeLean.getFloat(frame);
                have = true;
                return;
            }
            if (!have) return;
            eyeX.setFloat(frame, offsetX - dx);
            eyeY.setFloat(frame, height + dz);
            eyeZ.setFloat(frame, offsetZ - dy);
            eyeLean.setFloat(frame, lean);
            held++;
        } catch (Throwable t) {
            failed = true;
            Log.error("steady eye disabled (Viewpoint's frame changed shape)", t);
        }
    }
}

