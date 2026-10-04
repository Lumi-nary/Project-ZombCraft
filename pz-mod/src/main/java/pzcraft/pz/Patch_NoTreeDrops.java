package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;

/** While {@link TreeBridge} topples a tree that Minecraft felled, PZ does not drop its own logs (Minecraft already did). */
@Patch(className = "zombie.iso.objects.IsoTree", methodName = "dropWood")
public class Patch_NoTreeDrops {
    @OnEnter(skipOn = true)
    public static boolean enter() {
        return TreeBridge.suppressesDrops();
    }
}

