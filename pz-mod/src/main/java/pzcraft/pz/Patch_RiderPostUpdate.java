package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.*;
import zombie.characters.IsoZombie;

@Patch(className="zombie.characters.IsoZombie",methodName="postupdate")
public class Patch_RiderPostUpdate {
    @OnEnter(skipOn=true)
    public static boolean enter(@This IsoZombie self){return PassengerBridge.holds(self);}
}

