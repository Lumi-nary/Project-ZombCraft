package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
@Patch(className = "org.lwjglx.input.Keyboard", methodName = "isKeyDown")
public class Patch_GuiKeyboard {
    @OnExit public static void exit(int key, @Return(readOnly = false) boolean down) {
        if (down && GuiInput.mutesRawKey(key)) down = false;
    }
}

