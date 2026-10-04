package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.*;
import zombie.characters.animals.IsoAnimal;

@Patch(className="zombie.characters.animals.IsoAnimal",methodName="update")
public class Patch_AnimalRiderUpdate {
    @OnEnter(skipOn=true)
    public static boolean enter(@This IsoAnimal self){return PassengerBridge.holds(self);}
}

