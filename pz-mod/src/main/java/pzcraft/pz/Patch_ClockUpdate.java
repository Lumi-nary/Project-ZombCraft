package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.OnExit;

/** Scope the clock rate to calendar advancement, leaving actor movement and simulation speed untouched. */
@Patch(className="zombie.GameTime",methodName="update")
public class Patch_ClockUpdate {
    @OnEnter public static void enter(){ClockBridge.beginAdvance();}
    @OnExit public static void exit(){ClockBridge.endAdvance();}
}

