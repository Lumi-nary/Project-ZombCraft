package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.*;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.IsoAnimal;

/** Animals inherit this method; the camera puppet still uses its ordinary postupdate. */
@Patch(className="zombie.characters.IsoPlayer",methodName="postupdate")
public class Patch_AnimalRiderPostUpdate {
    @OnEnter(skipOn=true)
    public static boolean enter(@This IsoPlayer self){return self instanceof IsoAnimal animal&&PassengerBridge.holds(animal);}
}

