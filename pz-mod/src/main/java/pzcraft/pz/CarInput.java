package pzcraft.pz;

import pzcraft.protocol.InputState;
import zombie.core.physics.CarController;

/** Public call boundary for the inlined ZombieBuddy advice. */
public final class CarInput {
    private CarInput() {}
    public static void apply(CarController self) {
        var player = PuppetDriver.localPlayer();
        int mask = InputOverride.mask(System.currentTimeMillis());
        if (mask == 0 || player == null || player.getVehicle() != self.vehicleObject || self.vehicleObject.getDriver() != player) return;
        var controls = self.getClientControls();
        controls.forward = (mask & InputState.FWD) != 0;
        controls.backward = (mask & InputState.BACK) != 0;
        controls.brake = (mask & InputState.JUMP) != 0;
        controls.steering = ((mask & InputState.RIGHT) != 0 ? 1 : 0) - ((mask & InputState.LEFT) != 0 ? 1 : 0);
    }
}
