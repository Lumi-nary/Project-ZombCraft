# Project ZombCraft 0.17.0

Project ZombCraft now comes for two Minecraft versions. The Project Zomboid mod is the same for both.

| Minecraft | What it is |
|---|---|
| **26.3** | **Vanilla Minecraft.** The main version: the full game, no other Minecraft mods needed. |
| **1.20.1** | **Supports other Fabric mods, but buggy.** For playing with Fabric mods that stop at 1.20.1. Most of the game works; expect rough edges, above all with other mods. |

## New: Minecraft 1.20.1, with other mods

- The game ported to 1.20.1: Steve in Project Zomboid's world, blocks and mobs drawn by Project Zomboid in 3D, inventory and containers, mining and tree felling, fire, sleeping, cars, lights, and our own 3D guns (M1A, JS-14, Trapper, M9, M1911, B-F, M16 and their magazines). Other Project Zomboid firearms are held as Project Zomboid's own 3D models.
- Other mods' key bindings and screens work from Project Zomboid's window, and so does Shift-click in inventories.
- **Create (Fabric):** shafts, cogwheels and other moving parts are drawn by Project Zomboid in 3D and turn; no more doubled, standing-still cogwheels.
- **Full-screen effects from other mods** (for example Viltrumite's flight and punch effects) no longer turn the screen black. They show over what Minecraft draws; they cannot bend Project Zomboid's own picture.
- **Area abilities of other mods** that smash blocks (Viltrumite's punch craters, super punch, thunderclap) also smash Project Zomboid's walls, doors, windows, furniture and floors, like an explosion.
- **Zombies can be grabbed and carried** by mods that pick up mobs (Viltrumite's grab); a held zombie can't bite, and one thrown hard enough is knocked down.
- **Power hits and big pushes knock zombies down and ragdoll them**, as TNT does; zombies they kill fall as ragdolls.
- Viltrumite's flight pose shows on Steve in third person.
- For mod authors: a small API, `pzcraft.api.PzCraftApi` (is Project Zomboid connected, damage Project Zomboid's objects in an area, carry a zombie).

Tested with Create (Fabric) 6.0.8 and Viltrumite Core 1.11.0 + Viltrumite Flight 1.6.8. Other mods may work, or may not.

## Both versions

- The M16 uses its finished hand-made model and animations; all seven guns and four magazines are our own 3D models.
- Guns are held upright, Steve's arms follow the gun's own hand positions, aiming down the sights lines up, and racking no longer plays twice.
- Minecraft sounds no longer go silent after many sounds at once.
- No more strip of missing ground along houses that have a basement.
- Items from big texture packs and mods reach Project Zomboid at full resolution.
- Car entry and exit, the car camera and dashboard, explosions breaking props, floors and roofs, 3D corpses and blast ragdolls: fixed and checked.

## Known issues

- **1.20.1:** Better Combat is not supported. Firing and reloading motion of Project Zomboid's own (non-ZombCraft) guns is rough. Other mods that draw with their own renderer may show flat or not at all. Effects that brighten the whole screen can wash out Project Zomboid's picture.
- Single player only. Multiplayer is in development.
- Windows only.
