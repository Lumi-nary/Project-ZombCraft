package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;

@Patch(className = "viewpoint.render.ShadowPass", methodName = "draw")
public class Patch_BlockShadowFrame {
    @OnEnter
    public static void enter(@This Object pass) { NativeBlocks.shadowFrame(pass); }
}

