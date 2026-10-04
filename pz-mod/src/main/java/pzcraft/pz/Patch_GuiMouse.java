package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;
@Patch(className = "org.lwjglx.input.Mouse", methodName = "isButtonDown")
public class Patch_GuiMouse {
    @OnExit public static void exit(int button, @Return(readOnly = false) boolean down) {
        if (down && GuiInput.ownsInput()) down = false;
    }
}

