package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;

/**
 * PZ's Lua key handlers match key events with getCore():isKey(binding, key): the PZ hotbar (1-8), shout and the emote
 * wheel (Q). While Steve drives those keys belong to Minecraft's hotbar and drop, so the match fails and PZ neither
 * equips its own items nor shouts (which would also draw zombies). Matches isKey(String, Integer); the KeybindId
 * overload delegates to it.
 */
@Patch(className = "zombie.core.Core", methodName = "isKey")
public class Patch_MuteBinding {
    @OnExit
    public static void exit(String keyName, Integer key, @Return(readOnly = false) boolean match) {
        if (match && SteveControl.mutes(keyName)) match = false;
    }
}

