package pzcraft.pz;

import java.util.Set;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

/**
 * Whether Steve is driving the local PZ player, and which PZ controls must stay quiet meanwhile.
 *
 * <p>While Steve drives, Minecraft owns movement, jumping, falling and combat. PZ must not run its own versions on top:
 * its animations (falling, landing, shoving, climbing) move the head bone that Viewpoint's camera follows, and that eye
 * height is also Steve's eye in Minecraft, so they shake the view through blocks and throw off aiming, mining and
 * placing. PZ keeps everything Minecraft has no equivalent for: Interact (E, doors), inventory, map, crafting menus.
 */
public final class SteveControl {
    /**
     * PZ bindings on keys and buttons Minecraft uses for Steve (mouse buttons, Space, Shift, Ctrl, Q, 1-8) or whose PZ
     * action would fight Minecraft's movement or eye height (sprint, crouch, floor attack, firearm actions).
     */
    private static final Set<String> MUTED = Set.of(
            "Attack/Click", "Aim", "Melee", "Run", "Sprint", "Crouch", "ManualFloorAtk",
            "Shout", "Emote", "ReloadWeapon", "Rack Firearm", "SharpenWeapon",
            "Hotbar 1", "Hotbar 2", "Hotbar 3", "Hotbar 4", "Hotbar 5", "Hotbar 6", "Hotbar 7", "Hotbar 8");

    /** Refreshed once per frame on the game thread; the key-binding patches read it many times per frame. */
    private static volatile boolean active;
    private static volatile boolean survival;
    private static boolean savedThirdPerson;
    private static IsoPlayer autoVaultOff;
    private static final pzcraft.protocol.PlayerState camera = new pzcraft.protocol.PlayerState();

    private SteveControl() {}

    /** Game thread, once per frame. */
    static void update(IsoPlayer player) {
        // A placement handshake may briefly hold Steve. It must not hand hunger back to PZ or overwrite the snapshot.
        // In a car Steve still owns survival and PZ's own keys drive the car (W5, CarBridge); PuppetDriver leaves the position to the car.
        boolean owns = player != null && ViewpointBridge.viewEnabled() && LinkService.connected()
                && CharacterType.allowsSteve(player);   // a Zomboid survivor is never Steve
        if (owns && !survival) savedThirdPerson = ViewpointBridge.thirdPerson();
        if (!owns && survival) ViewpointBridge.setThirdPerson(savedThirdPerson);
        survival = owns;
        boolean now = survival && LinkService.minecraftReady();
        if (now != active) Log.info("Steve " + (now ? "drives the PZ player: PZ's own falls, climbs and combat keys are off"
                : "no longer drives: PZ controls are back"));
        active = now;
        ViewpointBridge.lootPanel(now);
        SteveVitals.update(player, survival);
        // On foot Minecraft's own camera is the view (first or third person). In a car Viewpoint's camera follows the car (W5),
        // so Minecraft's F5 switches Viewpoint's own third person, its chase camera behind the car.
        if (now) ViewpointBridge.setThirdPerson(player.getVehicle() != null && LinkService.pullInto(camera)
                && camera.cameraMode != pzcraft.protocol.PlayerState.CAMERA_FIRST);
        // PZ vaults fences and climbs walls by itself when you push against them; Steve jumps with Minecraft physics.
        if (now && autoVaultOff != player) {
            restoreAutoVault();
            if (!player.isIgnoreAutoVault()) {
                player.setIgnoreAutoVault(true);
                autoVaultOff = player;
            }
        } else if (!now) {
            restoreAutoVault();
        }
    }

    private static void restoreAutoVault() {
        if (autoVaultOff != null) {
            autoVaultOff.setIgnoreAutoVault(false);
            autoVaultOff = null;
        }
    }

    /** Public: ZombieBuddy inlines the advice that calls this into PZ's classes. */
    public static boolean drives(IsoGameCharacter c) {
        return active && c != null && c == IsoPlayer.getInstance();
    }

    public static boolean ownsSurvival(IsoGameCharacter c) {
        return survival && c != null && c == IsoPlayer.getInstance();
    }

    /** Public: ZombieBuddy inlines the advice that calls this into PZ's classes. */
    public static boolean mutes(String binding) {
        return active && binding != null && (GuiInput.ownsInput() || MUTED.contains(binding));
    }
}
