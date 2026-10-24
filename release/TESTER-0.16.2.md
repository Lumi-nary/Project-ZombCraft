# Brief for the 0.16.2 playtest (maple-pc)

You are the playtester and debugger for the **0.16.2** build of Project ZombCraft (PzCraft). Another Claude session wrote the code and built it on a VPS that cannot run either game. **Nothing in 0.16.2 has run in game yet.** A build that compiles, a protocol test or an offline check is not a test.

Read `HANDOFF.md` first (section 1, then the journal from 2026-10-15 13:37 on, especially the entries 2026-10-16 12:07 to 18:04), then this file. The rules in `release/TESTER.md` still apply: log every bug in `release/BUGS.md` before you touch code; fix only when the cause is clear and the fix is small and local; mark each result *seen in game*, *read from logs only* or *not tested*; announce anything that takes the desktop; back up `Zomboid/Saves` and the Minecraft instance's `saves` before loading a save; do not change the licence files or the release pages.

## 1. The build

The VPS staged the release files in `release-staging/0.16.2/`. That folder is git-ignored, so copy these three files to maple-pc yourself and check their hashes against `SHA256SUMS`:

| File | SHA-256 |
|---|---|
| `Project-ZombCraft-Fabric-0.16.2.jar` (Minecraft mod) | `b03f94e3fa6e11a58cc8bc5d6cb86c724a90787c09ace761418eb0dd9840edb0` |
| `Project-ZombCraft-Zomboid-0.16.2.zip` (PZ mod folder) | `c53c01484a5181667fa6e9adbbecbbc1feacb257038db061a45bf77920731576` |
| `SHA256SUMS` | (the file itself) |

The zip contains `Project-ZombCraft/42/media/java/Project-ZombCraft.jar`, which must have hash `455a1de5ea1e3e0c295b8a17d9b072b03504deb4321cd4db68189e5454411241`. `mod.info` says `modversion=0.16.2`.

**This build also contains the 0.16.1 draft fixes** (`release/changelog-0.16.1.md`). Test them here too; whether they become 0.16.1 is the owner's call.

## 2. Setup (as a player, with the rename)

1. Delete the old `Zomboid/mods/PzCraft` folder. In each Minecraft instance, remove every `pzcraft-fabric-*.jar`.
2. Unzip the zip into `Zomboid/mods/` (the folder is `Project-ZombCraft`). Put the Fabric jar into the Minecraft instance's `mods` folder. A fresh Prism instance is best.
3. Enable **Project-ZombCraft** in the test save's mod list. For the multiplayer test server, put it in `Mods=` too. Approve the new id and jar once in ZombieBuddy's prompt.
4. Check: the PZ console shows `[Project-ZombCraft]` and version 0.16.2 (B15); Minecraft's Mod Menu lists Project-ZombCraft 0.16.2 with GeckoLib as a required dependency (B10).
5. A save that still lists `PzCraft` makes PZ ask for the missing mod: enable Project-ZombCraft instead. Old Minecraft worlds keep their items through the old `pzcraft:` ids (aliases).

## 3. What to test, in order

Give each item one result: seen in game, read from logs only, or not tested.

### 3.1 Start-up and saves (B5, B6, B8, B9, B11, B16, B17; 0.16.1 draft)
- PZ does not close at start; the start screen draws (B11).
- PZ does not start Minecraft by itself; **Play** on the start screen (or your launcher) starts it. Loading screen with Minecraft's progress.
- The start-up box is not drawn over New Game, spawn, trait or character screens (B5); it says what is missing or to press O in game.
- The belt hotbar is hidden in Steve's game and back after (B6).
- Each PZ save has its own Minecraft world; the first save loaded takes over the old `pzcraft` world.
- A save without Project ZombCraft in its mod list: no Minecraft items lost on a save switch, and a box says so (B8).
- **Crashes when loading worlds** (B16, owner report on 0.16.0): load two or three saves, new and old. Note the exact step if it happens. Read the PZ and Minecraft logs for the abort.
- Steve or Zomboid survivor is chosen before character creation; a survivor is never Steve; both start in first person; the choice is kept.

### 3.2 Guns (the 0.16.1 draft; B2, B3)
- Our own set (M1A, JS-14, Trapper, M9, M1911, B-F, M16): the M1A, JS-14 and Trapper charging handles are pulled with the **right** hand, and the left arm does not fill the view (B2).
- The M16's left arm at rest and on the charging handle, and the M9, M1911 and B-F left hand racking the slide, do not show a flat block end.
- Fire, recoil, aim-down-sights, muzzle flash, tracers, shells, blood and debris on hits, gun crosshair.
- `I` inspects, `Shift+R` takes the magazine out, `R` reloads, an empty magazine drops into the inventory.
- Logs: no "Missing texture references ... particle" lines (B3); `Gun visuals: 11 kinds (11 our own, 0 private)`.

### 3.3 Mining the world (W0, W2a; the mining flow in HANDOFF 12:27)
- Grass, bushes, loose stones (drop cobblestone), twigs, logs, boulders and ores can be mined. Each drops only after PZ confirms it is gone; a spot PZ refuses gives nothing.

### 3.4 Walls, doors, windows, fences, barricades (W2b; HANDOFF 12:35)
- Walls, window frames, doors, windows, fences and barricades mine with Minecraft's breaking animation, and drop what PZ drops.
- Corner posts of fences are not mined as a fence piece (edge case: note it if seen).

### 3.5 Furniture and placing (W2c, W3; HANDOFF 12:50)
- Mining furniture gives the PZ furniture item. Right-click a floor with it: the piece is placed facing you with PZ's own rules. "It does not fit there" when it cannot go there.

### 3.6 Seats, beds, couches (W4; HANDOFF 12:57)
- Right-click a seat or couch: Steve sits. Check the seat's height and which way he faces.
- Right-click a bed: Steve sleeps; wake up normally.

### 3.7 Cars (W5; HANDOFF 13:05)
- Right-click a car door to get in; drive with the PZ car controls; get out.
- Punching a car window breaks it.
- Known risks to look at hard: the seat height, the yaw sign (turning left and right), the order of getting out (position and teleport).

### 3.8 Arrows (W1; HANDOFF 12:07)
- An arrow fired at a PZ wall, door or furniture stops in it. It falls out when that PZ box is gone.

### 3.9 Creative tabs (W6; HANDOFF 13:25)
- Fourteen "Zomboid" tabs after Minecraft's, on the next page: walls, fences, furniture, guns, melee, attachments, ammo and magazines, food, clothing, bags, medical, tools and materials, literature, items.
- A wall or fence item places PZ's sprite on the tile edge nearest the click (PZ's Brush Tool way). In survival one is used up.

### 3.10 Zombies break what Steve built (W7; HANDOFF 13:35)
- A zombie chasing Steve, pressed against a Minecraft block for about two seconds, cracks it; the breaking animation shows (Minecraft's stages); the PZ thump sound plays; the block breaks with its drops. Cracks heal after 30 s without a thump.
- Dirt goes in about 3 thumps, planks about 14, stone about 18; obsidian is not expected to break. A crowd breaks faster than one zombie.

### 3.11 Menus on G (HANDOFF 17:34)
- G over an object opens PZ's own right-click menu where the crosshair is; the cursor is free; Minecraft does not move meanwhile.
- Choosing an entry runs it where Steve stands.
- Shift+G opens the menu for the PZ item in hand (eat, read, wear, open).
- Over nothing PZ knows, G gives the held item's menu; with nothing held: "Hold a Zomboid item" / "Nothing to use there".
- An entry that opens a window (radio, map): the cursor stays free until it closes.
- G again gives the mouse back at any time (not while a PZ text box is focused).
- Walk to, sit on ground, sleep, rest, climb and equip entries are not in the menu. An action out of reach says "Too far away".
- Note the object and what opened when the menu seems to show the wrong thing.

### 3.12 The auto loot panel (HANDOFF 18:04, item 1)
- As Steve, Viewpoint's automatic loot/interact panel does not pop up on containers or doors.
- As a Zomboid survivor, Viewpoint's own behaviour is back.

### 3.13 The first-person hand at night (HANDOFF 18:04, item 2)
- At night the first-person hand and held item are as dark as the scene, lit by a lamp or torch nearby, and a held torch's flame stays bright.
- If the hand is too dark or bright against the world, note the look. Dev setups only: `handlight <gain>` through the control API (0.1 to 4, default 1) changes it live; record the value that matched.

### 3.14 Minecraft light and underground (HANDOFF 18:04, items 3 and 6)
- Minecraft torches light nearby PZ walls and floors.
- A dug hole or tunnel: dark inside; a Minecraft torch lights it in its warm colour; the entrance is lit by day.
- A Minecraft-roofed hut above ground: its inside is shaded like a PZ roof.
- Outdoor daylight blocks look as before; Steve's body at night matches.
- Watch the frame rate near many lights (note it if it hitches).

### 3.15 Fire (HANDOFF 18:04, items 4 and 5)
- A zombie set on fire by Steve (fire aspect, flame arrow, lava) burns, its flames show, and it dies by PZ's burn.
- A molotov-lit zombie also shows flames.
- Flint and steel (or a fire charge) on PZ grass, a wooden floor or a wooden wall starts PZ fire, which spreads by PZ's rules.
- PZ fire sets Minecraft planks next to it alight; Steve standing in PZ fire catches fire.
- A burning arrow stuck in a PZ house starts PZ fire there.
- Note any frame drop with big fires or lava nearby.

### 3.16 Multiplayer (release/multiplayer-test.md)
- Both players appear as Steve and move together (M0, M1). Known limit: mining, furniture, seats and cars are not synced in multiplayer (W2 to W5). Log only what crashes or disconnects.

## 4. Known limits (not bugs for this build)
- Survivor mode (Steve as a full PZ survivor) is future work.
- Multiplayer does not sync W2 to W5.
- The `handlight` dev command exists only on dev setups.

## 5. Reporting
- Each bug: `release/BUGS.md`, the next free B-number (B18 on), with the setup, steps, what you saw, the log lines, and the status.
- Each session: a dated `HANDOFF.md` journal entry with exact evidence (the log paths and the lines you read), what is still not tested, and one next step.
- Do not commit or push. The owner decides what goes into a release.
