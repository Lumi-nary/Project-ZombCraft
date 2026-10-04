package pzcraft.pz;

import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnExit;
import me.zed_0xff.zombie_buddy.Patch.Return;

@Patch(className="zombie.GameTime",methodName="getMinutesPerDay")
public class Patch_ClockRate {
    @OnExit public static void exit(@Return(readOnly=false) float minutes){minutes=ClockBridge.minutes(minutes);}
}

