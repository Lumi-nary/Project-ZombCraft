package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.This;
import pzcraft.protocol.InputState;
import zombie.core.physics.CarController;

/** The development input API drives PZ's car controls while Minecraft's walking inputs remain masked. */
@Patch(className = "zombie.core.physics.CarController", methodName = "updateControls")
public class Patch_CarInput {
    @OnExit public static void exit(@This CarController self) { CarInput.apply(self); }
}
