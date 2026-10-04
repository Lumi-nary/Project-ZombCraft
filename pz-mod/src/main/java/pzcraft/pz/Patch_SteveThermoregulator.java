package pzcraft.pz;
import me.zed_0xff.zombie_buddy.Patch;
import me.zed_0xff.zombie_buddy.Patch.OnEnter;
import me.zed_0xff.zombie_buddy.Patch.This;
import zombie.characters.BodyDamage.Thermoregulator;
@Patch(className = "zombie.characters.BodyDamage.Thermoregulator", methodName = "update")
public class Patch_SteveThermoregulator {
    @OnEnter(skipOn = true) public static boolean enter(@This Thermoregulator thermo) { return SteveVitals.owns(thermo); }
}

