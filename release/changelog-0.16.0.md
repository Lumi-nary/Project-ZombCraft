DRAFT: trim to the release scope after testing at maple. 0.15.0 was never released: 0.16.0 carries all of it. Most lines below
were seen in the 0.15.0 playtest at maple (2026-10-14); the 0.16.0 build itself has not run in game yet.
Check the guns in a player's setup, not the dev one: a player has our own M1A, JS-14, Trapper, M9, M1911, B-F and M16 with their 9mm, .45, .44 and 5.56 magazines (in the jar) but neither the
private Point Blank set nor the PZ meshes (`tools/pz-mcp/build-weapon-models.py` output), so every other gun is its flat PZ
icon. Rename the folders `~/.pzcraft/private/pointblank-26.1.2-2.2.0/guns` and `~/.pzcraft/items/weapon-models` for that test.

# Project ZombCraft 0.16.0

## New

- **Starting is one step.** Project Zomboid's main menu finds your Minecraft instances that have the mod and starts yours
  with **Play** (Prism Launcher, MultiMC, PolyMC). Project Zomboid never starts Minecraft by itself; with other launchers,
  start it yourself and Project Zomboid waits. While Minecraft loads, Project Zomboid shows the Mojang Studios loading screen with
  Minecraft's real progress.
- **Steve or Zomboid survivor.** Before character creation you choose: **Steve** skips character creation and starts with
  Minecraft's starter kit and nothing of Project Zomboid's; a **Zomboid survivor** is Project Zomboid's own character in
  first person and never becomes Steve. Both start in first person by themselves. A character keeps its choice.
- Every Project Zomboid firearm works as Steve: pistols, revolvers, rifles, shotguns, lever and bolt actions, with Project Zomboid's own ammo, magazines, round-by-round loading, racking and jams. The **M1A Rifle**, **JS-14 Rifle**, **Trapper Carbine**, **M9 Pistol**, **M1911 Pistol**, **B-F Pistol** and **M16 Assault Rifle** are full 3D models with their own animations (reload, magazine change, charging handle or slide racked by hand, inspect, sprint), and their 9mm, .45, .44 and 5.56 magazines are 3D magazines you fill round by round; the other guns show as their Project Zomboid icon in the hand for now, and more 3D guns come later.
- Gun feel: recoil, aim-down-sights motion, muzzle flash, tracers, ejected shells, blood and debris on hits, and a gun crosshair.
- **I** inspects the held gun; **Shift+R** takes the magazine out; an empty magazine drops into the inventory by itself.
- Each Project Zomboid save has its own Minecraft world. Your existing world goes to the first save you load.
- Start-up guidance: Project Zomboid's main menu says whether Minecraft is connected, and in game a box says what's missing or to press O.
- Works with Project Viewpoint 0.1.6 (needed): Steve's eye height follows Minecraft's again, and Viewpoint's new camera shake, wide-angle correction, free look and third-person keys stay off while you play as Steve, so the Minecraft picture and the world stay lined up.

## Fixed

- Pausing Project Zomboid (Space or the Esc menu) now also freezes Steve's hands, gun and animations.
- Project Zomboid's pause dimming and its map now cover the Minecraft view instead of being drawn under it.
- Project Zomboid wounds no longer stay on screen while you play as Steve.
- Firing no longer dips the held gun, and nothing flashes in the off-hand while a gun is held.
- A save that doesn't have Project ZombCraft in its mod list no longer loses Steve's Minecraft items when you switch saves, and says that the mod isn't enabled for it.
- Minecraft no longer crashes at start when GeckoLib is missing: GeckoLib is now a required mod, and Fabric says so.
- Project Zomboid's belt hotbar no longer sits on Minecraft's hotbar while you play as Steve.
- The start-up box no longer covers Project Zomboid's new-game, trait and character screens.
- Fewer errors in the logs (while a save loads, and missing-texture warnings for the 3D guns).

## Changed

- Multiplayer is in testing (`release/multiplayer-test.md`): the host runs a Project Zomboid server and a Minecraft server, and players move together as Steve. Fighting zombies, shared world changes and items through the server come in later steps. A Project Zomboid server no longer tries to start Minecraft.
- Both mods now show their author and links in the games' mod lists.
- The local test interface that the developer tools use no longer runs on players' computers.
- 0.16.0 is released as All Rights Reserved. 0.13.1 and earlier stay MIT.
