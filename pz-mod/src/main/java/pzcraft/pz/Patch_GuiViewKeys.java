package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
/** Typing O/Delete/Tab in chat must not toggle Viewpoint or its loot menu. */
@Patch(className = "viewpoint.platform.KeyBind", methodName = "pressed")
public class Patch_GuiViewKeys {
    @OnExit public static void exit(@Return(readOnly = false) boolean pressed) {
        if (pressed && GuiInput.ownsInput()) pressed = false;
    }
}

