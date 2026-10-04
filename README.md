# Project ZombCraft

Project ZombCraft connects **Project Zomboid Build 42** with a real **Minecraft Fabric client**. Project Zomboid remains the world and zombie simulation; Minecraft supplies Steve, movement, blocks, inventory, survival and the HUD. The two clients communicate through a local shared-memory link.

This release is an experimental **single-player** integration. It does not include Project Zomboid, Minecraft, Viewpoint, ZombieBuddy, GeckoLib, private reference files, or licensed model and audio assets.

## Player requirements

- Project Zomboid **Build 42.21**
- **ZombieBuddy 2.3.4** and **Viewpoint**, installed separately in Project Zomboid
- **Prism Launcher** with a Minecraft **26.3** instance
- Fabric Loader **0.19.5**, Fabric API **0.161.0+26.3**, and GeckoLib **5.5.7** for Fabric/Minecraft 26.3 in that Prism instance
- Java **25**
- Windows, for the current shared-memory bridge implementation

**Better Combat is optional.** Install a version compatible with Minecraft 26.3 and Fabric Loader 0.19.5 if you want its melee animations. PzCraft still supports vanilla combat without it.

These versions describe the release build. Multiplayer and other game/loader versions are not verified.

## Install

1. In Prism Launcher, create a Minecraft 26.3 instance **with Fabric**: in the *New Instance* dialog pick version 26.3 and, under *Mod loader*, select **Fabric** 0.19.5. For an instance that already exists, open *Edit → Version → Install Loader* and choose Fabric. The *Version* tab must list **Fabric Loader** next to Minecraft; putting mod jars in the `mods` folder is not enough, because without the loader Minecraft starts as plain vanilla and ignores them.
2. Add Fabric API 0.161.0+26.3 and GeckoLib 5.5.7 for Fabric/Minecraft 26.3 to that instance's `mods` folder. Better Combat is optional.
3. Copy `Project-ZombCraft-Fabric-0.13.1.jar` from the release into the same `mods` folder. Keep Prism's Java runtime set to Java 25.
4. Extract `Project-ZombCraft-Zomboid-0.13.0.zip` into your Project Zomboid user `mods` folder. It should create `PzCraft/42/mod.info` and `PzCraft/42/media/java/PzCraft.jar`.
5. Enable **ZombieBuddy**, **Viewpoint**, and **PzCraft** in the Project Zomboid mod list.
6. Start the Prism instance, then start Project Zomboid and load a single-player world. Minecraft needs no clicks: about a second after its title screen appears it opens its own empty `pzcraft` world and waits for Project Zomboid. If PzCraft tries to launch a locally configured Minecraft command, set `autolaunch=false` in `Zomboid/Lua/pzcraft.properties` and start Minecraft from Prism first.
7. In Viewpoint's 3D view, press **O** to enter first person and capture the mouse. Minecraft's HUD and Steve should appear in the Project Zomboid scene.

The mod sets this instance's **Music** slider to 0% each time it starts, so only Project Zomboid's soundtrack plays; other Minecraft sounds are unchanged.

Minecraft closes itself, saving its world, a few seconds after Project Zomboid exits. To keep it open, add `-Dpzcraft.autoexit=false` to the instance's Java arguments.

If Minecraft crashes or closes, launch it again from the same Prism instance. Once it has loaded, return to the Project Zomboid world and re-enter Viewpoint's 3D view to reconnect. If the link does not recover, reload the single-player world after Minecraft is running.

## Troubleshooting

**Minecraft opens to its normal title screen and nothing else happens.** The mod is not loaded. Check the instance's *Version* tab in Prism: if it shows only Minecraft and LWJGL, install Fabric Loader as in step 1. A working launch log begins with `Loading Minecraft 26.3 with Fabric Loader 0.19.5` and later prints `Shared link open`; a log that says `Main class: net.minecraft.client.main.Main` is a vanilla launch.

**Minecraft is in the `pzcraft` world but Project Zomboid shows no Steve.** Check that ZombieBuddy, Viewpoint and PzCraft are all enabled, that PzCraft's jar was approved in ZombieBuddy's prompt, and that you are in Viewpoint's 3D view. `Zomboid/console.txt` prints `waiting for Minecraft` while the link is down and `Minecraft connected` once it is up.

To uninstall, remove PzCraft from Project Zomboid's `mods` folder and remove its Fabric jar from the Prism instance's `mods` folder. Keep or back up both games' saves before changing mod setups.

## Controls

| Input | Action |
|---|---|
| W/A/S/D, Space, Shift, Ctrl | Walk, jump, sprint, sneak |
| Mouse | Look |
| Left mouse | Mine or melee; fire a supported held Project Zomboid pistol |
| R with a supported pistol | Reload from a loaded native magazine; handle jams when applicable |
| Right mouse | Interact, place/use blocks, or aim with a supported pistol |
| 1–9, mouse wheel | Select hotbar slot |
| E | Open Minecraft inventory |
| Q | Drop selected item |
| T or / | Open Minecraft chat or commands |
| F5 | Change Minecraft camera perspective |

## Commands

| Command | Action |
|---|---|
| `/weather clear\|rain\|thunder [duration]` | Minecraft's weather command; PZ's weather follows (PZ draws the sky) |
| `/weather drizzle\|showers\|heavy\|storm\|tropical\|blizzard\|snow [duration]` | PZ's weather variations |
| `/weather fog [0..1]` | PZ fog density until `/weather clear` or `/weather fog 0` |
| `/pzgive <item> [count]` | Give Steve an item exported from PZ; tab-complete item IDs |

## What is included

- `fabric-mod/`: Fabric client integration and mixins
- `pz-mod/`: Project Zomboid mod source and runtime metadata
- `protocol/`: shared bridge protocol source
- GitHub release assets: the Fabric jar and a Project Zomboid mod zip

The bridge currently covers movement and terrain collision, native-world rendering, blocks, survival/HUD, interactions, selected combat, weather/time, entities and vehicles. This is an active project; inventory capacity/overflow, crafting, clothing appearance, several gun types and some edge cases remain incomplete. The M9 visual model/assets are not bundled; M9 rendering requires separately obtained compatible assets and GeckoLib.

## Planned features

- Support more Project Zomboid guns and weapon types.
- Expand Project Zomboid vehicle support.
- Integrate more items from both games, including cross-game crafting.
- Add multiplayer support.
- Improve performance and optimize the bridge.

## Build source

The repository contains the mod source and minimal Gradle project files. Build tools and developer automation are intentionally omitted. The Project Zomboid module compiles against local Project Zomboid and ZombieBuddy APIs supplied by the player; it does not bundle those APIs. The Fabric build uses Minecraft/Fabric dependencies and may require a local GeckoLib jar for optional M9 code. The published release jars are the ready-to-install artifacts.

## Credits

- Viewpoint and ZombieBuddy for the Project Zomboid rendering/mod integration ecosystem.
- Vic Point Blank for reference on selected weapon mechanics, models and animation behavior.
- Fabric Loader, Fabric API, Prism Launcher, and GeckoLib.
- Development assistance: OpenAI Codex and Anthropic Claude.

Project ZombCraft is an independent community project and is not affiliated with The Indie Stone, Mojang, Viewpoint, ZombieBuddy, or Vic Point Blank. Their names and assets remain their respective owners' property.

## License

The Project ZombCraft code in this repository is licensed under the MIT License. This does not grant rights to third-party game files, mods, trademarks, or assets.
