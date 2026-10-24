# PZMC 0.2 compared with Project ZombCraft 0.16.0

Studied 2026-10-15 (VPS) from PZMC's Workshop item 3818476863 (downloaded with steamcmd), its `PLAYER-GUIDE.md` and
`CHANGES.md`, and its decompiled jar and Lua. PZMC is GPL-3.0 and our release files are All Rights Reserved: **nothing of
its code may go into ours.** This compares features and ideas only. The download and the decompiled copy are in the
git-ignored `.pz-jars/rival-pzmc-0.2/`.

## The one big difference

| | PZMC 0.2 | Project ZombCraft |
|---|---|---|
| How Minecraft is there | **No Minecraft runs.** Minecraft's rules are written again inside PZ (about 29,000 lines of Java on ZombieBuddy, as decompiled, and 10,500 of Lua). Textures, sounds and the skin are read from the player's own Minecraft install. | **A real Minecraft 26.3 (Fabric) runs hidden** next to PZ and plays Steve: physics, blocks, inventory, combat and HUD are Minecraft's own. |
| What that gives them | One game process, so it's lighter. Multiplayer through PZ's own server. Blocks are PZ world objects, saved in the PZ save. | Everything vanilla works exactly (redstone, mobs, water, boats, enchanting, every block), plus Fabric mods. |
| What it costs them | Every Minecraft feature has to be rebuilt by hand: no fences, gates, glass panes, crossbow or double chests yet; water is solid blue with no swimming. | A second game process (RAM, CPU), a Fabric instance to set up and start, and multiplayer is much harder. |

Their guide sells exactly this: "PZMC is **not a passthrough mod**. No Minecraft runs behind it". Our answer is the reverse:
it is real Minecraft, so everything in it works.

## Feature by feature

| Area | PZMC 0.2 | Project ZombCraft 0.16.0 | Who leads |
|---|---|---|---|
| Install | Subscribe to 3 Workshop items. Needs Minecraft: Java Edition installed and started once. Finds the official launcher, Prism, MultiMC, PolyMC, CurseForge and Modrinth by itself. | The PZ half on the Workshop (once published), plus a Fabric Minecraft instance with Fabric API, GeckoLib and our jar, started by hand before loading a world. | **PZMC** |
| First start | A window if Minecraft isn't found, listing where it looked. An options screen opens by itself in a new world. | The start-up box on PZ's menu and in game. | PZMC |
| Multiplayer | **Hosted and dedicated servers.** Shared blocks, chests, inventories, other players drawn as Steve with their skins, PvP by server rules, server-side balance settings. The server trusts each player's game. | Single player only (it switches itself off in MP and says so). Plan: the host runs a PZ server and a Minecraft server. | **PZMC** |
| Views | First and third person, and building in PZ's top-down view too. | First person and Minecraft's two third-person views, only in Viewpoint's 3D. | PZMC (top-down) |
| Your own skin | Yes, from Mojang's skin service or a `skin.png`. | Yes, natively: Minecraft draws the account's skin. | Even |
| Blocks | A hand-made set: dirt, stone, planks, logs, glass, TNT, slabs, stairs, ores, doors, ladders, torches, beds, chests, furnace and more. | All of Minecraft's blocks, drawn in Viewpoint's world with PZ lighting and shadows. | **Us** |
| Mining PZ's world | Ground, trees, loose stones (cobblestone by size), deposits, **built floors** (as floor tiles), **free-standing furniture** (as an item you place again), **PZ doors** (as an item), windows; walls with a sledgehammer. PZ's own pickaxes and axes mine as Minecraft tools. | Trees (topple), ground layers under natural floors, explosions through walls, doors, windows and furniture. Furniture opens as a chest; it can't be picked up. | **PZMC** |
| Water and lava | "Young": solid blue, steps instead of slopes, no swimming. | Vanilla flow, swimming, drowning, burning. | **Us** |
| Redstone, mobs, boats, minecarts | None. | Vanilla redstone on PZ floors (it opens PZ doors), summoned mobs, boats and minecarts that carry zombies. | **Us** |
| Elytra and fireworks | Yes (new in 0.2), one in every military base. | Yes, Minecraft's own (elytra measured at 33 blocks/s with no fall-through). | Even |
| Guns | PZ's guns stay PZ's and fire where Viewpoint's crosshair points. No models. | Every PZ firearm with PZ's ammo, magazines, racking and jams, recoil, flashes, shells and tracers; 3D M1A, JS-14 and Trapper. | **Us** (paused for now) |
| Melee | Minecraft's attack timing and reach with PZ and Minecraft weapons; stomping zombies on the ground. | Better Combat animations with PZ's own damage, skills and wear; vanilla weapons on Minecraft's path. No stomp. | Even, stomp to PZMC |
| Being a PZ survivor | Hearts from PZ health; PZ hunger as shanks, **PZ thirst** as water drops, **cold and heat** icons, Minecraft healing and hunger as options; Zomboid's eating and smoking. | Minecraft owns health, hunger and saturation as Steve. PZ's hunger, thirst and cold pause and come back when Steve mode ends. | **PZMC** |
| Armour and clothing | Minecraft's armour points; all 355 PZ armour pieces count as a Minecraft tier. Steve wears no PZ clothes. | Minecraft armour; PZ clothing armour applies. | PZMC (the PZ armour mapping) |
| Weight and bags | PZ's weight rule with 2x capacity; a backpack slot; bags open as chests and lighten by PZ's numbers. | No PZ weight limit as Steve. Bags keep their contents through the mirror. | **PZMC** |
| PZ items and crafting | One inventory for both games' items. About 80 recipes turn PZ things into Minecraft ones (metal to ingots, sheets to beds, firewood to logs...). PZ's hand recipes work in Minecraft's crafting grid. | PZ items travel as their own stacks with icons and whole state. A hidden PZ copy of Steve's inventory lets PZ use keys and its own recipes. No PZ materials in Minecraft's recipes. | **PZMC** |
| Containers | PZ containers open as Minecraft chests (fridge and freezer as two pages). | PZ furniture, corpses and floor items open as Minecraft chest screens with PZ names and icons. | Even |
| Help and settings | **F7 guide in the game** (pictures, recipe grids, your own key binds), **F9 options screen in Minecraft's look**, chat commands, `/help`. | Minecraft's own options and commands; README. | **PZMC** |
| Sounds | Minecraft's footsteps on PZ ground by surface, block sounds, sound sliders. | Minecraft's own sounds; PZ's soundtrack (Minecraft music off). | PZMC (footsteps on PZ ground) |
| One world per save | Natural: their blocks are in the PZ save. | Yes, one Minecraft world per PZ save (new in 0.15). | Even |
| Surviving updates | **Checks at start** that every PZ and Viewpoint name it uses still exists, and lists any missing ones in the console. | An offline check on the dev machine (this session: 56 hooks against Viewpoint 0.1.6); nothing at run time. | PZMC |
| Its jar | Signed for ZombieBuddy by its author ("approve again, now signed by PZMCdev"). | Unsigned; approved by hash. | PZMC |
| Popularity | 72 subscribers on 2026-10-15, three days after release. | Not on the Workshop yet. | |

## What to take from it (ideas, not code), most playability first

1. **Starting has to be one step.** Today a player builds a Fabric instance with three jars and starts it before loading a
   world. Have PZ find and start the player's Minecraft instance itself (we already do this for the dev setup with
   `pzcraft.properties` and `McLauncher`; Prism has `--launch <instance>`), and say clearly when no instance is found.
2. **Multiplayer.** Their 0.2 has it. Ours is a large project (the host runs both servers); decide the scope and date.
3. **In-game help.** A guide window (keys, what works, first minutes) and a settings screen instead of the README.
4. **A run-time self-check** of our Viewpoint and PZ hooks with a clear message, so a Viewpoint update can't break PzCraft
   silently. Today's offline check (`patchcheck`) is most of it already.
5. **Steve as a Zomboid survivor:** an option to keep PZ's thirst, temperature and hunger running and shown on the
   Minecraft HUD; PZ armour as Minecraft armour tiers; PZ's carry weight.
6. **PZ's world in Minecraft's hands:** pick up and place furniture, take doors off, loose stones as cobblestone, PZ's
   pickaxes and axes as Minecraft tools, PZ materials in Minecraft recipes.
7. **Small things players notice:** stomping downed zombies, Minecraft footsteps on PZ ground, signing our jar for
   ZombieBuddy.

## Where we are ahead, and should say so on the pages

Real Minecraft: every block, redstone, mobs, water and swimming, boats and minecarts, any Fabric mod. Minecraft blocks
lit and shadowed by Viewpoint. A full gun system with PZ's mechanics. Explosions that go through PZ's walls by vanilla
resistances. The player's own skin with nothing to set up.
