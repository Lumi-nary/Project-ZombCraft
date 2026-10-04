package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
@Patch(className = "viewpoint.input.Look", methodName = "onUpdateMouseCursor")
public class Patch_GuiCapture {
    @OnEnter public static void enter() { if (GuiInput.ownsInput()) ViewpointBridge.releaseForGui(); }
}

