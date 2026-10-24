package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
import me.zed_0xff.zombie_buddy.Patch.This;
/**
 * Typing O/Delete/Tab in chat must not toggle Viewpoint or its loot menu; and a character of a kind (CharacterType) stays
 * in first person: Viewpoint's first-person and third-person keys do nothing for it.
 */
@Patch(className = "viewpoint.platform.KeyBind", methodName = "pressed")
public class Patch_GuiViewKeys {
    @OnExit public static void exit(@This Object bind, @Return(readOnly = false) boolean pressed) {
        if (pressed && (GuiInput.ownsInput() || ViewpointBridge.lockedViewKey(bind))) pressed = false;
    }
}
