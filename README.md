# Project-ZombCraft (PZ x Minecraft)

**Codex / Claude: read [HANDOFF.md](HANDOFF.md) first.** It is the shared current plan, progress log, evidence ledger and resume point. The older plan/log filenames redirect there; use this README for launch and controls.

Project Zomboid (B42.21) is the main game, drawn in 3D by the **Viewpoint** mod. A hidden, real **Minecraft 26.3**
(Fabric) process supplies Steve: his movement physics, blocks, hotbar, mining, combat and HUD. PZ keeps its world,
zombies and time. Minecraft owns Steve-mode survival; PZ's survival stats return when Steve mode ends.
Design follows [SkyCraft](https://github.com/chasmlol/SkyCraft) (Skyrim x Minecraft).

**Personal use only.** Viewpoint's authors allowed working with it on the condition that nothing derived from it is
distributed publicly. `ref/` holds decompiled game/mod sources for reading; none of it may be published.

## Experimental Minecraft 1.20.1 build

Use the `+mc1.20.1.jar` with Minecraft **1.20.1**, Fabric Loader **0.19.5**, Fabric API **0.92.12+1.20.1**, and **Java 17**. Start Prism's **Project ZombCraft 1.20.1** instance, then PZ with `scripts/run-pz.bat`; keep only one Minecraft client running. The default `start-all.bat` remains the 26.3 launcher. Keep each Minecraft version's saves separate.

The back-port includes native terrain and third-person Steve, inventory and chat, PZ item icons and creative tabs, food and melee attributes, inventory mirroring, containers, tree blocks, PZ mining, furniture/tile placement, seats/beds and fire hooks. PZ cars support entry, driving and exit, with Minecraft's bottom HUD above the dashboard. Minecraft light sources also light PZ's world, including redstone lamps switching on and off. Some gameplay paths still need broader playtesting.

**The edited M16 is the first supported custom 3D gun on 1.20.1**, using GeckoLib **4.8.4**. Its draw, reload, fire, rack, magazine removal and inspect clips follow native PZ results. Left click fires; hold right click to aim; **R** reloads, **Shift+R** removes the magazine, **X** racks, **I** inspects, and **B** switches single/auto. Reloading needs a loaded 5.56 magazine in the inventory. PZ owns ammunition, damage and sounds. The M16's reload, 30 shots, dry fire, magazine removal, inspect and auto burst have been checked in the linked games.

Other custom gun models, PZ weapon mesh rendering and Better Combat integration are still pending on 1.20.1. Other firearms' controls have not had the same gameplay checks. Keep each version's Minecraft worlds separate; an existing PZ crater needs its matching Minecraft world. The updated PZ mod may need its usual one-time ZombieBuddy approval on your next normal launch.

## Start it

**Renamed (0.16.x):** the mods used to be called PzCraft. Remove the old `PzCraft` folder from `Zomboid/mods` and the old
`pzcraft-fabric-*.jar` from Minecraft's `mods` folder before installing `Project-ZombCraft`; enable **Project-ZombCraft** in a
save's mod list (and in a server's `Mods=`) and approve it once in ZombieBuddy's prompt. Saved Minecraft items and blocks keep
working (their old `pzcraft:` ids still load).

1. Don't use the Steam / `E:\ProjectZomboid64.exe.lnk` shortcut for this. **Run `scripts\start-all.bat`**: it starts
   Minecraft with no window, then PZ. Steam must be running. (`scripts\run-pz.bat` starts PZ alone.)
   The current setup uses ZombieBuddy 2.3.4 and Viewpoint on PZ 42.21. The compatibility shim is optional and disabled by default.
2. If ZombieBuddy asks to approve a Java mod, select **Allow**, save the decision and continue. Development rebuilds use an exact-hash Project-ZombCraft approval in the separate private development profile; the normal approval policy remains enabled.
3. In PZ, continue your intended save with ZombieBuddy, Viewpoint and Project-ZombCraft enabled. Development checks use `PzCraftFeatureTest-20261002`.
   PZ never starts Minecraft by itself: start it with `start-all.bat`, your launcher, or **Play** on PZ's start screen, and leave it running.
4. If PZ shows "Game Paused" (it pauses when its window loses focus), unpause it.
5. Press **O** (Viewpoint's first person). Steve appears where your PZ character stands.
   The mouse must be captured (Viewpoint does that; middle-click toggles cursor/camera).

**Players without the dev scripts:** start PZ. Its main menu (Project-ZombCraft's start screen) lists the Minecraft instances that
have the Project-ZombCraft Fabric mod (Prism Launcher, MultiMC, PolyMC, CurseForge, the Modrinth App, Mojang's launcher); **Play**
starts one through its launcher where the launcher allows it (Prism, MultiMC, PolyMC), PZ never starts Minecraft by itself:
otherwise the player starts it and PZ waits, or plays without Minecraft. While Minecraft loads, PZ shows a Mojang Studios loading screen with Minecraft's real progress (the logo comes
from the player's own Minecraft). Before character creation the player picks **Steve** (no PZ character creation, no PZ
items, Minecraft's starter kit) or a **Zomboid survivor** (PZ's creation, first person only, never Steve); the choice is
kept in the character's mod data (`PzCraftType`) and changes only with a new character. Both start in Viewpoint's first
person and its O key cannot switch it off; Viewpoint's setup has to be finished first (the start screen says so).
Characters made before 0.16.0 have no type and work as before (press **O**).

**One Minecraft world per PZ save.** Steve's inventory, placed blocks and the rest belong to the PZ save you load. Minecraft
waits on its title screen until a PZ save is loading, then opens that save's world (creating it the first time); load
another save and it saves and leaves the first one. PZ shows *Minecraft is opening this save's world* until it is ready.
Which world is whose is in Minecraft's `saves/pzcraft-worlds.properties`; the single world older versions used
(`saves/pzcraft`) went to the first save loaded after the update.

While PZ is paused (its pause key or the Esc menu), Minecraft pauses with it and the picture, hands and gun animations
hold still; gun keys (R, I, B, X) do nothing until PZ runs again.

The PZ launcher installs the most recent successful local Project-ZombCraft build before starting PZ. It refuses to replace
the jar while PZ is running. Development feature tests use the separate `PzCraftFeatureTest-20261002` save.

Minecraft's Music slider is set to 0% on every start, so only PZ's soundtrack plays. Minecraft closes itself, saving
its world, about 3 s after PZ's process ends (20 s if PZ is still running but has stopped responding).
`-Dpzcraft.autoexit=false` or `PZCRAFT_AUTOEXIT=0` keeps it open.

## Controls (while in Viewpoint 3D with the mouse captured)

| Input | Does |
|---|---|
| W A S D, Space, Shift, Ctrl | Minecraft movement: walk, jump, sneak, sprint (Minecraft's physics) |
| Mouse | look (Viewpoint's camera) |
| Left mouse | melee attack / hold to mine; fire a held PZ M9 |
| R with a PZ M9 | reload from a loaded native magazine; rack or clear a jam when applicable |
| Shift+R with the pistol | take the magazine out by hand; it also drops out by itself when the last round is fired or an empty gun is clicked, empty magazine back in the inventory |
| R with a 9mm magazine | insert one round from 9mm ammunition held in the off-hand |
| Shift+R with a 9mm magazine | unload; switching slots stops the action |
| Hold right mouse with a PZ M9 | aim down its sights; sprinting cancels aiming |
| I with a PZ M9 | inspect the pistol; firing, aiming, sprinting, reloading or switching slots ends it; needs a free off-hand |
| Right mouse | interact with PZ doors/windows, light switches and lamps; enter a car; use Minecraft blocks/entities, place blocks or hold to eat |
| Shift+right mouse on a car | Project Zomboid's vehicle menu (stand near the hood, trunk or door for that part's actions) |
| Shift while seated | leave the seat or car |
| G | Project Zomboid's own right-click menu for what the crosshair is on (fill water, light a stove, barricade, open a radio, Ã¢â‚¬Â¦); the mouse is free until the menu closes, and while a window it opened is up. Over nothing PZ knows: the menu of the PZ item in hand. G again: back to looking. Walk to, sit, sleep and climb are left out (Steve does those with Minecraft); an action out of reach says "Too far away" |
| Shift+G | Project Zomboid's menu for the PZ item in hand (eat, read, wear, open, recipes, Ã¢â‚¬Â¦) |
| 1-9, mouse wheel | hotbar |
| Q | drop |
| T, / | Minecraft chat / command entry; Tab completes commands, Enter submits |
| E | Minecraft inventory; click, drag, right-click and Shift-click use Minecraft's screen handlers |
| E in inventory, Escape in a Minecraft screen | close the screen and recapture the mouse |
| F5 (or Minecraft's rebound perspective key) | cycle first person, rear third person, front third person |
| `/weather clear\|rain\|thunder [duration]` | Minecraft's weather command; PZ's weather follows (PZ draws the sky) |
| `/weather drizzle\|showers\|heavy\|storm\|tropical\|blizzard\|snow [duration]` | PZ's weather variations |
| `/weather fog [0..1]` | PZ fog density until `/weather clear` or `/weather fog 0` |

Minecraft screens temporarily own keyboard and mouse input. PZ hotkeys and Steve's movement are suppressed while
typing or moving items. E is reserved for Minecraft inventory while Steve drives. PZ inventory and Minecraft inventory
use a hidden PZ mirror while Steve drives. Container transfers and stage-one mirror reconciliation are verified; worn state, capacity, crafting and other remaining inventory work are recorded in HANDOFF.md.

In a car, PZ handles driving and F5 selects its vehicle chase camera. Minecraft's movement is suppressed and its hotbar
sits above PZ's dashboard. The **Zomboid Vehicles** creative tab contains the zombie spawn egg and vehicle spawners;
spawned vehicles have full condition, fuel and battery, and their key goes into your inventory.

Steve starts with: iron sword, iron pickaxe, 64 planks, 64 stone, 32 glass, 16 cooked beef.

## Testing site

The saved development world has a lit, fenced testing platform at **2327.5, 13, 6269.5**.
Return with `/tp @s 2327.5 13 6269.5`. Stand on the green firing line in the matching lane:
blue targets are 8 blocks away, yellow 16, and red 24. The orange pad provides flat space for
movement and held-item checks. The platform has a bedrock base and backstop; no enemies were added.

## Current implementation (0.17.0)

Two Minecraft versions are released: **26.3** (vanilla Minecraft, the main version) and **1.20.1** (supports other Fabric
mods, but buggy; `fabric-mod/versions/1.20.1`, built with `-Pmc1201`). Both use the same Project Zomboid mod. The 26.1.2
build in `fabric-mod/versions/26.1.2` is not released. What 0.17.0 changes: `release/changelog-0.17.0.md`.

Where the work stands, with exact evidence, is in [HANDOFF.md](HANDOFF.md). What changed in each release is in
`release/changelog-*.md`. The development launcher uses a stable offline player, `PzCraftSteve`, so inventory and bed
spawn survive restarts.

Items marked **(new in 0.16)** have partial in-game coverage. Recent checks include seats, stairs, roof collision,
vehicle entry and cameras, the hood menu, light switches, creative spawners and sound forwarding. Driving, crashes,
passenger seats and the full release checklist still need testing; see `release/TESTER-0.16.2.md` and `HANDOFF.md`.

### Steve in Project Zomboid's world

- **Walking:** Project Zomboid's terrain (floors, walls, closed doors and windows, furniture, solid tiles, stairs) is
  collision for Minecraft. Steve's physics runs in Minecraft, and the PZ player follows him, so Viewpoint's camera
  moves with him. Stairs and upper floors are exported but not yet fully exercised in game.
- **Fast movement:** PZ exports complete terrain columns ahead of Steve along his velocity. He is held at a column that has
  not arrived ("PZ is loading terrain...") instead of falling through it.
- **Mining (new in 0.16):** grass, bushes, stones, logs, boulders and ores; walls, window frames, doors, windows,
  fences and barricades. A drop comes only after PZ has removed the piece. Furniture mines into Project Zomboid's own
  item, which can be placed back by right-clicking a floor, with PZ's own placement rules.
- **Seats and beds (new in 0.16):** right-click a seat or couch to sit; a bed to sleep. Beds set spawn and skip the night.
- **Cars (new in 0.16):** get in, drive with PZ's car controls, get out. Punching a car window breaks it.
- **Trees, TNT and ground:** PZ's trees are punchable with Minecraft's tools and topple when they break. Explosions are
  traced through PZ's walls, doors and furniture at Minecraft's block resistances. Under every PZ floor with a natural
  surface there is ground that becomes real where you dig; a hole shows the layers below.
- **Zombies (new in 0.16):** a zombie chasing Steve cracks and breaks Minecraft blocks he built, with Minecraft's
  breaking animation and Project Zomboid's thump sound.

### Combat and guns

- **Melee:** PZ actors have invisible hit proxies. PZ melee weapons use Better Combat animations with Project Zomboid's
  damage, skills, critical hits and condition wear. Minecraft owns Steve's health.
- **Firearms:** every Project Zomboid firearm works as Steve, with its own ammo, magazines, round-by-round loading,
  racking and jams. Our M16, M1A, JS-14, Trapper Carbine, M9, M1911 and B-F use their own custom models and animations. `I` inspects the held
  gun, `Shift+R` takes the magazine out, `R` reloads (see Controls). The M1A, JS-14 and Trapper are racked with the right
  hand (0.16.1 draft, not yet tested in game).
- **Survival:** Minecraft owns health, damage, regeneration, falls, hunger and death; Project Zomboid mirrors Steve. Its
  injuries reach Minecraft, its moodles are hidden, and its survival stats are saved with the character. Respawn
  streams the terrain at the bed or saved spawn before releasing Steve.

Weapons without a custom model use Project Zomboid's own 3D weapon mesh. Generic firearm reload, loading, unloading and rack motion is adapted from the locally installed game's animation tracks and follows PZ's action timing. Both skin-textured arms follow the weapon's grip and support points. These fallback meshes are single pieces; their magazines and pumps do not move separately. Generic melee swings also use PZ motion; installing Better Combat selects its melee animation instead. Custom weapons use their own models and clips.

To prepare or refresh the local PZ weapon fallback, run `python tools/pz-mcp/build-weapon-models.py` (Python and NumPy required), then restart Minecraft. The converted meshes, textures and motion stay on your machine; they are not included in the mod. Weapons without an available mesh retain their icon.

Optional Viewpoint addons: [sour_kisel's model pack](https://steamcommunity.com/sharedfiles/filedetails/?id=3810302175)
and [Living Props](https://steamcommunity.com/sharedfiles/filedetails/?id=3816442251). Enable ZombieBuddy, Viewpoint,
the model pack and Project-ZombCraft, then Living Props last. In Viewpoint's settings, enable **Corpses as 3D models**
for nearby bodies; the normal distance and corpse-count limits still apply.

### Menus, inventory and creative mode

- **Project Zomboid's menus on G (new in 0.16):** G opens Project Zomboid's own right-click menu for what the crosshair
  is on, with the mouse free until it closes; Shift+G opens the menu for the Project Zomboid item in hand. Walk to, sit,
  sleep, climb and equip entries are left out, because Steve does those with Minecraft.
- **Inventory:** Project Zomboid furniture, corpses and floor sources open as chests with native names and icons. A
  hidden Project Zomboid inventory mirror reconciles Steve's slots both ways. Still open: worn appearance, capacity,
  floor and drop lifecycle, crafting and overflow (HANDOFF records the coverage).
- **Creative menu (new in 0.16):** fifteen "Zomboid" tabs after Minecraft's own, with every Project Zomboid item,
  gun, attachment, food, piece of clothing, container and literature item, plus walls, fences and furniture. A wall or
  fence item places Project Zomboid's sprite on the tile edge nearest the click.
- **Old saves:** items from the old `pzcraft:` ids keep working through Fabric registry aliases.

### Looks, light and fire

- **Native blocks and entities:** Minecraft's baked geometry, atlas, tint and emission are drawn in Viewpoint's
  g-buffer with Project Zomboid's light grid, shadows and fog, and cast sun shadows into Viewpoint's cascades. Outlines,
  the first-person hand and held item, mining cracks and flames are native too. Only the HUD (hearts, hotbar, crosshair,
  chat and screens) is a video layer.
- **Minecraft light in Project Zomboid (new in 0.16):** Minecraft blocks, entities and Steve carry Minecraft's own
  light. Underground, that light decides, so caves are dark and a torch lights them. Above ground it combines with
  Project Zomboid's light, so a Minecraft roof shades and a torch lights. The first-person hand is lit by Project
  Zomboid's light at Steve's square, capped by Minecraft's light under roofs and in caves.
- **Viewpoint's loot panel (new in 0.16):** Viewpoint's automatic loot and interact panel stays off while Steve plays.
- **Fire between the worlds (new in 0.16):** Minecraft fire sets Project Zomboid zombies burning, and they burn the
  Project Zomboid way with Minecraft's flames. Flint and steel and fire charges used on Project Zomboid ground, floors
  and walls start Project Zomboid fire, which spreads by Project Zomboid's rules; that fire lights nearby Minecraft
  blocks and sets Steve alight if he stands in it.
- **Lamps and redstone:** Minecraft torches, glowstone, lanterns and lava light Project Zomboid's world. Redstone runs
  on Project Zomboid floors; powered Project Zomboid doors open and close.
- **Weather and time:** `/weather` sets Project Zomboid's weather, which is mirrored into Minecraft. Minecraft's day
  follows Project Zomboid's clock.

### Input, cameras and multiplayer

- **Direct input:** movement, actions and the camera read shared memory where Minecraft consumes input.
- **Cameras:** Minecraft computes first, second and third person; Viewpoint draws from that final camera.
- **Multiplayer (in testing):** players move together as Steve (`release/multiplayer-test.md`). Mining, furniture,
  seats and cars are not synced between players yet.

## Known limits (honest list)

- Native rendering includes fluids, mobs, items, block entities, moving blocks and particles. Animated block/fluid atlas
  refresh is 4 Hz. Water currently uses the opaque g-buffer; sorted transparency/refraction remains pending.
  See-through particles are dithered (smooth with TAA on, grainy with it off). Only the HUD still uses video, without
  PZ depth. Unusual entity renderers need more coverage. In fast third-person flight Steve can leave a faint temporal
  trail (Viewpoint's TAA); the owner judged it negligible and it is deliberately not being fixed further.
- Weather: Minecraft's own rain is never drawn (PZ draws rain, snow, fog and lightning). PZ snow counts as rain for
  Minecraft's rules. A `/weather` command holds PZ's precipitation for its duration; the time is saved with the PZ world.
- Walking is still Minecraft's 20 Hz tick physics (smoothed by interpolation), so movement starts ~25-50 ms after the key.
- PZ container looting is partially connected to Minecraft inventory. Full inventory mirroring, capacity/loot rules,
  persistence coverage and the PZ weapon adapter are pending. See [HANDOFF.md](HANDOFF.md) for current evidence and gun scope.
- Blocks cast sun shadows, but not shadows into individual lamp/flashlight maps.
- Actor proxies include crawler and animal shapes; max ~120 within 24 tiles. Animal bounds are approximations.
  Vehicle height is a body approximation, rather than separate hood/roof/wheel shapes. Upper-floor respawn passes;
  extensive stairs, moving vehicle and passenger persistence/reconnect cases need more coverage.
- Minecraft's world (`pzcraft`) is saved separately from PZ saves: blocks stay at their coordinates across PZ saves. A hole you dig
  is a hole in both (PZ saves the removed floor); playing a different PZ save in the same Minecraft world can leave ground
  blocks and holes that do not match it.
- Minecraft still renders (to a minimized window) and reads back one HUD layer. Normal launches use the prepared Java
  runtime directly (no Gradle). A permanently hidden window (`-Dpzcraft.hiddenWindow=true`) measured the same latency
  with more Minecraft CPU (58 % against 39 % of a core), so minimized stays the default.
- While flying fast, PZ's frame rate drops (about 33-39 fps in the 0.12 flight runs against ~70 idle) because Viewpoint's
  mesh budget is raised to keep ground from going missing; the budget is `Streaming.buildBudget` if it needs tuning.
- All three cameras and clipping against placed Minecraft blocks and PZ walls pass. Third-person combat needs further
  coverage.
- Multiplayer is in testing: the host runs a PZ server and a Minecraft server, and players move together as Steve
  (step M1). Zombie fighting, shared world changes and items through the servers come in later steps. How to host:
  [release/multiplayer-test.md](release/multiplayer-test.md).

## Troubleshooting

- `E:\Zomboid\console.txt` has `[Project-ZombCraft]` lines every 5 s: link state, Steve's pose, input state, zombie count.
  - `waiting for Minecraft`: it is still starting (about 20-40 s), or `pz-launch.log` / `fabric-run-live.log` say why.
  - `input[... captured=false live=false]`: Viewpoint hasn't captured the mouse (middle-click), or the game is paused.
  - `no Java mods` / no `[Project-ZombCraft]` at all: you started PZ via Steam instead of `run-pz.bat`.
- Rebuild/reinstall after code changes: `scripts\install.bat` with PZ stopped. It also prepares the direct Minecraft
  launcher. Normal starts use one Minecraft JVM with a 2 GB heap cap; `scripts\run-minecraft-dev.bat` retains Loom's
  development launch. After dependency/configuration changes, regenerate with
  `.\gradlew.bat prepareDirectLaunch --offline --no-configuration-cache` from `fabric-mod`.
- Dev commands: write a line into `E:\Zomboid\Lua\pzcraft-dev.txt` (it runs when the file changes), or send them through
  the control API / `pzctl cmd`: `spawn N dist`, `pull dist`, `tp dx dy`, `hurt N`, `hunger 0..1`, `warp on|off`,
  `spin degPerSec ms`, `look yaw pitch`, `overlay shader|ff`, `where`, `native on|off`, `shadows on|off`,
  `third on|off`, `eye steady|follow` (A/B for the camera eye), `weather KIND [intensity] [hours]` (as `/weather`).
  Test fixtures: `heal` heals through Minecraft; `block x y z minecraft:block_id` creates/removes a block;
  `mc COMMAND` runs a Minecraft command as Steve (give, summon, setblock, tick rate...).
- Stop both games before installing a rebuilt jar; replacing a running PZ jar can break later class loading.
- Current validation and reproducible tests are in `HANDOFF.md`. The 0.6 latency measurements above
  describe the historical video path, now used for effects and fallback. Native blocks use PZ's current camera.
- Agent/automation control: on a development machine (one with `Zomboid/Lua/pzcraft.properties`; a player's install has none,
  so the API stays off there) Project-ZombCraft serves a token-protected API on 127.0.0.1:47600 (state, screenshots of what PZ rendered,
  injected input, Lua, menu; `control.enabled=false` in that file turns it off). `tools/pz-mcp/pzctl.py` is its CLI (`launch --restart`, `state`, `shot`, `input`, `cmd`, `lua`,
  `logs`), and `tools/pz-mcp/server.py` is an MCP server (`.mcp.json` registers it as `pz`). OpenRouter helpers
  (`vision`, `decide`, `digest`) read the key from `OPENROUTER_API_KEY` or `~/.pzcraft/openrouter.key`.
- Tests: protocol unit tests `protocol\gradlew test`; physics against a real Minecraft with a fake PZ:
  start Minecraft (`scripts\run-minecraft.bat`) with PZ closed, then `scripts\test-walk.bat`.

## Credits

- Viewpoint and ZombieBuddy for the Project Zomboid rendering/mod integration ecosystem.
- Fabric Loader, Fabric API, Prism Launcher, and GeckoLib.
- Development assistance: OpenAI Codex and Anthropic Claude.

Project ZombCraft is an independent community project and is not affiliated with The Indie Stone, Mojang, Viewpoint, ZombieBuddy. Their names and assets remain their respective owners' property.

## License

Project ZombCraft is by Lumi-nary. Its source code is licensed under the [Apache License 2.0](LICENSE) from version 0.15.0 on. Releases up to and including 0.13.1 were published under the MIT License, and copies of those stay MIT. Release files put out before their source (from 0.15.0, on the Steam Workshop, Modrinth and CurseForge) are All Rights Reserved ([LICENSE-ARR](LICENSE-ARR)).

If you share Project ZombCraft or a modified version of it, the license requires you to:

- include the [NOTICE](NOTICE) file, which names the original project and its author;
- keep the copyright and attribution notices that are in the source;
- mark every file you changed as changed.

The license does not let you use the names "Project ZombCraft" or "PzCraft" (section 6). A modified version needs its own name, and it may say it is based on Project ZombCraft. The license also grants no rights to third-party game files, mods, trademarks, or assets.

## Layout

```
protocol/     shared wire protocol (memory-mapped link, seqlock slots, rings, coords, collision sections) + tests
fabric-mod/   Minecraft side (Fabric, MC 26.3): physics glue, overlay capture, camera match, picking, proxies, vitals
pz-mod/       PZ side (ZombieBuddy Java mod): exporter, puppet, input, overlay renderer, gameplay, block bridge
shim/         optional historical ZombieBuddy compatibility agent (disabled by default)
scripts/      .bat launchers (PowerShell scripts are blocked on this PC)
HANDOFF.md    shared engineering plan and validation ledger     ref/  private references
LICENSE       Apache License 2.0                                NOTICE  attribution that every copy must carry
LICENSE-ARR   terms of release files whose source is not out    release/  store pages, Workshop item, upload steps
```
