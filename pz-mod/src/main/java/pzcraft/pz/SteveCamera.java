package pzcraft.pz;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import pzcraft.protocol.Coords;
import pzcraft.protocol.PlayerState;
import zombie.characters.IsoPlayer;

/** Uses the camera Minecraft actually rendered, without changing Viewpoint's mouse input or Steve's aim. */
public final class SteveCamera {
    private static final ThreadLocal<PlayerState> states = ThreadLocal.withInitial(PlayerState::new);
    private static Field eyeX, eyeY, eyeZ, eyeLean, camX, camY, camZ, viewYaw, viewPitch;
    private static Field drawerFrame, drawerEye, drawerDir;
    private static volatile boolean failed;
    private static volatile long drawn;
    private SteveCamera() {}

    private static PlayerState latest() {
        PlayerState s = states.get();
        return !failed && SteveControl.drives(IsoPlayer.getInstance()) && LinkService.pullInto(s) && s.cameraValid ? s : null;
    }

    private static synchronized void resolve(Object f) throws ReflectiveOperationException {
        if (eyeX != null) return;
        Class<?> c = f.getClass();
        eyeX = c.getField("eyeX"); eyeY = c.getField("eyeY"); eyeZ = c.getField("eyeZ"); eyeLean = c.getField("eyeLean");
        camX = c.getField("camX"); camY = c.getField("camY"); camZ = c.getField("camZ");
        viewYaw = c.getField("viewYaw"); viewPitch = c.getField("viewPitch");
    }

    static boolean frame(Object f) {
        PlayerState s = latest(); if (s == null) return false;
        try {
            resolve(f);
            eyeX.setFloat(f, (float) (camX.getFloat(f) - s.cameraX));
            eyeY.setFloat(f, (float) (s.cameraY - Coords.mcY(camZ.getFloat(f))));
            eyeZ.setFloat(f, (float) (camY.getFloat(f) - s.cameraZ)); eyeLean.setFloat(f, 0);
            viewYaw.setFloat(f, Coords.mcYawDegToPzRad(s.cameraYawDeg));
            viewPitch.setFloat(f, Coords.mcPitchDegToPzRad(s.cameraPitchDeg));
            return true;
        } catch (ReflectiveOperationException e) { fail(e); return false; }
    }

    /** After Camera.eye: do not add Viewpoint's 0.12-block forward offset to Minecraft's camera. */
    public static void eye(Object f, float[] out) {
        if (latest() == null) return;
        try { resolve(f); out[0] = eyeX.getFloat(f); out[1] = eyeY.getFloat(f); out[2] = eyeZ.getFloat(f); }
        catch (ReflectiveOperationException e) { fail(e); }
    }

    /** SceneDrawer reads Look directly. Override its vectors before it builds the view matrix. */
    public static void draw(Object drawer) {
        PlayerState s = latest(); if (s == null) return;
        PlayerState captured=NativeEntities.camera();if(captured!=null)s=captured;
        try {
            if (drawerFrame == null) {
                Class<?> c = drawer.getClass();
                drawerFrame = c.getDeclaredField("frame"); drawerFrame.setAccessible(true);
                drawerEye = c.getDeclaredField("eye"); drawerEye.setAccessible(true);
                drawerDir = c.getDeclaredField("dir"); drawerDir.setAccessible(true);
            }
            Object f = drawerFrame.get(drawer); resolve(f);
            float[] eye = (float[]) drawerEye.get(drawer), dir = (float[]) drawerDir.get(drawer);
            eye[0] = (float) (camX.getFloat(f) - s.cameraX);
            eye[1] = (float) (s.cameraY - Coords.mcY(camZ.getFloat(f)));
            eye[2] = (float) (camY.getFloat(f) - s.cameraZ);
            double yaw = Coords.mcYawDegToPzRad(s.cameraYawDeg), pitch = Coords.mcPitchDegToPzRad(s.cameraPitchDeg);
            dir[0] = (float) (-Math.cos(yaw) * Math.cos(pitch)); dir[1] = (float) Math.sin(pitch);
            dir[2] = (float) (-Math.sin(yaw) * Math.cos(pitch));
            drawn++;
        } catch (ReflectiveOperationException e) { fail(e); }
    }

    private static void fail(Exception e) { failed = true; Log.error("Minecraft camera bridge disabled", e); }

    static Map<String, Object> stats() {
        var result = new LinkedHashMap<String, Object>();
        PlayerState s = latest();
        result.put("failed", failed); result.put("draws", drawn); result.put("active", s != null);
        if (s != null) {
            result.put("mode", s.cameraMode); result.put("position", java.util.List.of(s.cameraX, s.cameraY, s.cameraZ));
            result.put("yaw", s.cameraYawDeg); result.put("pitch", s.cameraPitchDeg);
        }
        return result;
    }
}

