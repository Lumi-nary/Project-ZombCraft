package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;

/** The previous-frame half of {@link Patch_MuteKeyDown}, so "pressed"/"released" edges stay consistent. */
@Patch(className = "zombie.input.GameKeyboard", methodName = "wasKeyDown")
public class Patch_MuteWasKeyDown {
    @OnExit
    public static void exit(String keyName, @Return(readOnly = false) boolean down) {
        if (down && SteveControl.mutes(keyName)) down = false;
    }
}

