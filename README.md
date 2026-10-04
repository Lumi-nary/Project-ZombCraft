# Project ZombCraft

Project ZombCraft connects **Project Zomboid Build 42** with a real **Minecraft Fabric client**. Project Zomboid remains the world and zombie simulation; Minecraft supplies Steve, movement, blocks, inventory, survival and the HUD. The two clients communicate through a local shared-memory link.

This release is an experimental **single-player** integration. It does not include Project Zomboid, Minecraft, Viewpoint, ZombieBuddy, GeckoLib, private reference files, or licensed model and audio assets.

## Player requirements

- Project Zomboid **Build 42.21**
- **ZombieBuddy 2.3.4** and **Viewpoint**, installed separately in Project Zomboid
- **Prism Launcher** with a Minecraft **26.3** instance
- Fabric Loader **0.19.5** and Fabric API **0.161.0+26.3** in that Prism instance
- Java **25**
- Windows, for the current shared-memory bridge implementation

These versions describe the release build. Multiplayer and other game/loader versions are not verified.

## Install

1. In Prism Launcher, create a Minecraft 26.3 instance and install Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3.
2. Copy `Project-ZombCraft-Fabric-0.13.0.jar` from the release into that instance's `mods` folder. Keep Prism's Java runtime set to Java 25.
3. Extract `Project-ZombCraft-Zomboid-0.13.0.zip` into your Project Zomboid user `mods` folder. It should create `PzCraft/42/mod.info` and `PzCraft/42/media/java/PzCraft.jar`.
4. Enable **ZombieBuddy**, **Viewpoint**, and **PzCraft** in the Project Zomboid mod list.
5. Start the Prism instance, then start Project Zomboid and load a single-player world. If PzCraft tries to launch a locally configured Minecraft command, set `autolaunch=false` in `Zomboid/Lua/pzcraft.properties` and start Minecraft from Prism first.
6. In Viewpoint's 3D view, press **O** to enter first person and capture the mouse. Minecraft's HUD and Steve should appear in the Project Zomboid scene.

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

## What is included

- `fabric-mod/`: Fabric client integration and mixins
- `pz-mod/`: Project Zomboid mod source and runtime metadata
- `protocol/`: shared bridge protocol source
- GitHub release assets: the Fabric jar and a Project Zomboid mod zip

The bridge currently covers movement and terrain collision, native-world rendering, blocks, survival/HUD, interactions, selected combat, weather/time, entities and vehicles. This is an active project; inventory capacity/overflow, crafting, clothing appearance, several gun types and some edge cases remain incomplete. The M9 visual model/assets are not bundled; M9 rendering requires separately obtained compatible assets and GeckoLib.

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

