# 0.15.0 bug log

One entry per bug, newest at the bottom. Keep ids stable (B1, B2, ...). Update `Status` instead of deleting.

Entry format:

```
### B<n> <one-line title>
- Severity: blocker (crash, lost data, Steve unusable, visible checkerboard) | major | minor | cosmetic
- Status: open | fixed (how, where) | won't fix | cannot reproduce
- Seen: in game | logs only | code reading
- Setup: dev | player setup; PZ and Minecraft versions; which save
- Steps: 1. ... 2. ...
- Expected / actual:
- Evidence: screenshot path, log lines (file and time)
- Diagnosis / fix:
```

### B1 package-release.py cannot find gradlew.bat when the shell hides the current directory
- Severity: minor (build tooling only; the game and the release files are not affected)
- Status: fixed in source 2026-10-15 (VPS): `gradle()` in `tools/package-release.py` runs `cmd /c` with the full path of the project's `gradlew.bat`, so `cmd` no longer has to search the current directory. Not re-run in a hardened shell yet (needs maple).
- Seen: in this session's build run (tool, not game)
- Setup: maple-pc, PowerShell started with `NoDefaultCurrentDirectoryInExePath=1` (Windows hardening that stops `cmd` searching the current directory); `python tools\package-release.py 0.15.0`
- Steps: 1. Run `package-release.py 0.15.0` in a shell with that variable set. 2. It prints `> fabric-mod build ...`, then `'gradlew.bat' is not recognized`, then `CalledProcessError ... returned non-zero exit status 1`.
- Expected / actual: the build runs / it stops at the first Gradle call. With the variable cleared for that one command the whole script passed (Fabric jar, PZ jar, zip, Workshop folder, `SHA256SUMS`).
- Evidence: `%TEMP%\package-release-0.15.0.log`, 2026-10-14 ~22:20.
- Diagnosis / fix: `tools/package-release.py:34` runs `cmd /c gradlew.bat` with `cwd=ROOT/project`, which relies on `cmd` searching the current directory. Fix: `['cmd', '/c', str(ROOT / project / 'gradlew.bat')]` (same on the other `gradle()` callers). The owner's normal shell does not set the variable, so this only matters in hardened shells such as the agent's.

### B2 Left arm's end faces fill the view during the charging-handle pull (M1A, JS-14, Trapper)
- Severity: major (a big black-and-white-faced block in the middle of the screen on every rack and empty reload; not a crash or a checkerboard, so not a blocker by the owner's definition, but the owner should decide)
- Status: fixed in source 2026-10-16 (VPS), offline only (not seen in game): the M1A, JS-14 and Trapper are racked by the **right** hand, as their handle is on the right (owner: "the charging handle is on right side. it doesn't makes sense to pull it by left hand"). See "Fix (2026-10-16)" at the end of this entry. Was: open, parked when the owner stopped gun work on 2026-10-15.
- Seen: in game (frame sheets)
- Setup: Prism instance `Project ZombCraft` (Minecraft 26.3, Fabric Loader 0.19.5, release jar 0.15.0 `e824b198…`), PZ 42.21 with the 0.15.0 PzCraft build, ZombieBuddy normal profile, private sets present; the arm shows the account's own skin (profile LumiMaple), so the colours may differ with another skin
- Steps: 1. Hold the M1A, JS-14 or Trapper. 2. Press X (rack), or R on an empty gun (the empty reload ends with the same pull). 3. Watch 0.8-1.3 s into the clip.
- Expected / actual: the arm reaches over to the handle with the hand at the receiver and the arm mostly off screen / the arm swings up into the picture as a large red block whose top face is black and whose corner is white (the arm's open end), covering up to about 40 % of the screen. Worst on the JS-14 and Trapper (handle at the eye), smaller on the M1A.
- Evidence: `release/evidence/20261014/{m1a,js14,trapper}-rack.png` (frames at 1.22 s / 1.31 s / 1.21 s), `*-reloadempty.png` for the empty reload. Not committed.
- Diagnosis / fix: the `rack` and `reloadempty` clips from `tools/vic-guns/{m1a,js14,trapper}_anim.py` (`PULL`/`OVER` framing) bring the left arm's shoulder end into the camera, and that end's faces use the skin's unused cap region. Options: keep the arm further from the camera in the pull poses, or give the arm bone a cap that is not drawn. Known weak spot in HANDOFF (2026-10-14 17:20 / 18:10 entries). Not fixed.
- Note (2026-10-15, VPS, offline only): reproduced with `geoview.py --view fp` (the arm as its 8x24x8 placeholder box, scale 1.2/1.4/1.2, matches the in-game idle frame). Measured through the rack (camera space, 16:9): the arm's shoulder end sits inside the picture all through the pull (screen y -0.4 to -0.65, the bottom edge is -1), because `reach()` aims the arm from `OVER` (forward and high: M1A (22, 20, -30), JS-14 (22, 12, -20), Trapper (22, 13.5, -24.4)) and the 2.1-block arm ends short of the screen edge; then `FROM_HANDLE` (up, left and about 5 units back from the pulled handle) brings the hand to 1.2 (JS-14), 1.35 (M1A) and 1.5 (Trapper) blocks from the eye with its end face turned to the camera, which is the big black-capped block. Tried: aiming from near the rest shoulder (x about 16, y low, z 0 to 12) puts the shoulder end off the bottom edge and hides both end faces, but the forearm then crosses the receiver and hides the handle it pulls. Not yet tried: a shoulder further left and back (x 28-40, z 12-36) scored by how much of `action`/`action2` stays visible, and a `FROM_HANDLE` that drops away to the left instead of coming back towards the face.
- Fix (2026-10-16, VPS; offline, geoview's camera, the built jar set):
  - Cause: the end in view is the arm's **hand** end, not the shoulder (the tester's frames: the red block's black face with the white corner is the fist's flat end). The left arm was aimed from `OVER`, a point in front of the handle (M1A z -30 against the handle's -24.9), so the arm pointed back at the eye.
  - The motion was wrong too: these handles are on the right. PZ's own rifle rack (`RackRifle.xml` plays `Bob_Reload_Bolt_Rack` for every rifle; parsed from `~/pz-dedicated/media/anims_X/Bob/*.x`) keeps the left hand fixed on the gun and moves the right hand about 7 cm along it; the aimed rack (`Bob_AttackRifle_AimBoltRack`) likewise.
  - `m1a_anim.py`: the rig works for either arm; `rack` and the end of `reloadempty` are the right hand's (`right_hand_rack`: from the wrist to the handle, the pull, let go, back), the left hand stays on the forend (in the empty reload it goes back to it from the magazine). The view is rolled and turned to show the rifle's right side (`RACK` (-6, 12, 28, 1, 1, -3), shared). Right shoulder points: M1A (-16, -20, 30); JS-14 and Trapper (-24, 0, 40) (their handle is near the eye and comes back towards it, so the arm must come from well behind it). Hand on the handle `ON_HANDLE` (-1.6, -1, 1.4); approach `TO_HANDLE` (-5, -8, -2) and drop-away `FROM_HANDLE` (-5, -7, 0), both under the right side rather than back towards the eye (that showed the fist for a frame). Timings, sound marks and reload phases unchanged; the old `OVER`, `PULL`, `TO_HANDLE_SHUT` are gone.
  - Measured, most arm-end pixels in any frame (192x108, both arms, every 0.05 s; `tools/vic-guns/armcheck.py --before`): rack M1A 647 -> 0, JS-14 1094 -> 0, Trapper 584 -> 0 (the same for `racknomag`); `reloadempty` M1A 615 -> 36, JS-14 1043 -> 84, Trapper 560 -> 6, which is what their plain `reload` already shows while the old magazine is taken out at the screen's bottom edge (unchanged). Sampled every 0.01 s through the rack and the empty reload's end: M1A 0, JS-14 0 and 25 (one frame at the release), Trapper 0.
  - The M16 (not in the 0.15.0 test) had the same defect: its rest arm aimed from (14, 2, 14) showed the shoulder end at the screen's edge at rest, and its rack's hand end faced the eye. `m16a1.py` LEFT shoulder (10, -14, 10) (the hand unchanged), `m16a1_anim.py` `OVER` (8, -15, 40); it keeps the left hand on its rear T-handle (central, not on the right). idle 101 -> 0, draw 188 -> 0, fire 107 -> 0, running start/end 86 -> 0, rack 784 -> 0, inspect 496 -> 73, reload 771 -> 278 (left: the hand carrying the magazine out and in; a shoulder change there only brought it to about 180, not kept).
  - The three pistols (M9, M1911, B-F; also not in the 0.15.0 test) had it too, about 1320 px at the slide's release in `rack` and `reloadempty`: the left hand (right for a slide racked overhand) reached the slide from `OVER` (20, 16, -18), in front of it, and dropped away back towards the eye (`FROM_SLIDE` (6, 29, 10)). `pistol_anim.py` (shared): `OVER` (40, -5, 10), well out to the left, low, a little behind the slide (the forearm comes over from the lower left), `FROM_SLIDE` (12, 20, -4). Every 0.01 s through the rack and the empty reload: 0 for all three; the left arm covers at most 16.5 % of the view (13.5 % before).
  - Review sheet: `tools/vic-guns/viewer/right-hand-rack.png` (rows: M1A, JS-14, Trapper rack; M1A empty reload's end; M16 rack; M9 rack; flat colours per bone). The committed viewer pages `m1a-viewer.html`, `js14-viewer.html`, `trapper-viewer.html` still show the old left-hand rack.
  - Still open, same kind, smaller: the held magazines' left hand while feeding a round (200-350 px of 192x108 in `load`/`unloadround`; `build.py` `recipe_feed`), and the M16's while carrying its magazine out and in (278-410 px).
  - To check in game (maple): the rack (X) and an empty reload of the three rifles, with Steve's own arm skin; the right arm's look as it leaves the grip; the M16 at rest, drawn, inspected and racked; the pistols' rack and empty reload.

### B3 Every gun item model logs "Missing texture references ... particle"
- Severity: minor (log only, no visible effect seen; but it breaks the release check "no missing-texture lines for pzcraft:")
- Status: fixed in source 2026-10-15 (VPS): the `_base` item models get `"textures":{"particle":"minecraft:item/iron_sword"}` (as `PzWeaponModels`' `pzmodel_base`; constant `build.PARTICLE`, used by `build_own.py` and `build.py`). The bundled set was regenerated (`build_own.py`: only the three `_base.json` files and their manifest hashes changed, every clip, model and texture byte-identical) and is in the staged Fabric jar. The private set at maple gets it on its next `build.py` run, so a dev machine still logs the 20 private kinds until then. Check at maple (player setup): no `Missing texture references` line for `pzcraft:` in `latest.log`.
- Seen: logs only (Minecraft `latest.log` 22:24:32, 22 lines, one per `pzcraft:item/gun_<kind>_base`, including `gun_m1a_base`, `gun_js14_base`, `gun_trapper_base`)
- Setup: Prism instance as in B2. First with the private sets present (23 kinds, 22 lines). **Confirmed in the player setup** (private folders hidden, whole `~/.pzcraft` moved aside so PZ regenerated it, staged zip installed, `Gun visuals: 3 kinds (3 our own, 0 private)`): exactly 3 lines, `gun_m1a_base`, `gun_js14_base`, `gun_trapper_base`, each followed by `particle` (Minecraft `latest.log` 22:58:34; copy in `release/evidence/20261014/minecraft-latest-player-firstrun.log`).
- Steps: 1. Start the Prism instance with the 0.15.0 jar. 2. Read `logs/latest.log` after the resource reload.
- Expected / actual: no warnings / `Missing texture references in model pzcraft:item/gun_m1a_base:` followed by `particle`, for every gun model.
- Evidence: `E:\PrismLauncher\Instances\Project ZombCraft\minecraft\logs\latest.log`; the generated model `~/.pzcraft/items/packs/pzcraft-items/assets/pzcraft/models/item/gun_m1a_base.json` is only `{"gui_light":"front","display":{...}}`.
- Diagnosis / fix: the `_base` item models have no `textures` block, so the model's `particle` slot is unresolved. Adding `"textures":{"particle":"<any texture that exists>"}` where `build_own.py` / `build.py` write them should silence it; the manifest SHA-256 lists then need regenerating and the jar rebuilt. Not fixed.

### B4 The shipped PZ jar opens the local control API (/lua, /input, /cmd, /screenshot) by default
- Severity: major for a public release (a decision for the owner, not a crash)
- Status: fixed in source 2026-10-15 (VPS), PZ jar compiled: `Control` starts the API only when `Zomboid/Lua/pzcraft.properties` exists (the dev machines have it, a player's install has none) and does not say `control.enabled=false`; otherwise it logs `control API off (no <path>)`. The dev tools at maple keep working unchanged. README's automation line says so. Not run yet: check at maple that nothing listens on 47600 without the file, and that `pzctl state` still works with it.
- Seen: in game (PZ console `control API on http://127.0.0.1:47600`, `Get-NetTCPConnection` shows PZ listening on 127.0.0.1:47600 with no `pzcraft.properties` present)
- Setup: PZ with the 0.15.0 jar installed the way a player would, `E:\Zomboid\Lua\pzcraft.properties` moved away
- Steps: 1. Start PZ with PzCraft. 2. `Get-NetTCPConnection -State Listen -LocalPort 47600`.
- Expected / actual: a player's install runs no test endpoints (the public 0.13.0 release left them out) / an HTTP server on 127.0.0.1 accepts `/lua` (runs Lua in PZ), `/input`, `/cmd`, `/screenshot`, protected only by a token in `Zomboid/Lua/pzcraft-control.token` (readable by any process of the same Windows user).
- Evidence: `pz-mod/src/main/java/pzcraft/pz/Control.java` lines 75-105 (`enabled = true` unless `control.enabled=false`), 186-208 (endpoints); PZ `console.txt`.
- Diagnosis / fix: default `enabled` to false unless `pzcraft.properties` says `control.enabled=true` (the dev installer would have to write it), or leave the class out of the release jar. Not fixed: it changes how the dev tools start, so it is the owner's call.

### B5 PzCraft's start-up box also covers PZ's New Game, spawn, traits and character screens
- Severity: cosmetic
- Status: fixed in source 2026-10-15 (VPS), PZ jar compiled: on `MainScreenState` the box is drawn only while the Lua `MainScreen.instance.bottomPanel` (the main menu's list of buttons) is visible; if that panel cannot be found it shows as before. Not run yet: at maple, check the box on the main menu and its absence on Solo / sandbox / traits / character screens (the field name `bottomPanel` is the tester's, not checked against B42's Lua from the VPS).
- Seen: in game
- Setup: Prism instance + PZ as in B2, Minecraft connected
- Steps: 1. On PZ's main menu choose Solo, Apocalypse, Next, Next. 2. Look at the top centre on each screen.
- Expected / actual: the box only belongs to the main menu's root / it stays on every `MainScreenState` screen; on "Select Occupation and Traits" it hides "Available Traits" and the first trait row (the box ends over "Speed Demon"), on "Customise Character" it overlaps the top of the model preview.
- Evidence: screenshots taken during creation of the save `ZombCraftWorldGenTest` (22:25), not saved as files; reproduce with the steps above.
- Diagnosis / fix: `StartupHint` draws whenever `GameWindow.states.current` is `MainScreenState`; hide it unless the main-menu bottom panel is visible (`MainScreen.instance.bottomPanel:isVisible()` in Lua, or the Java equivalent). Not fixed.

### B6 PZ's belt hotbar, speed controls and version text draw over Minecraft's HUD while Steve drives
- Severity: minor (visual clutter; probably a side effect of the draw-order fix, since the Minecraft layer used to be drawn last and covered them)
- Status: fixed in source 2026-10-15 (VPS), PZ jar compiled: `HudBridge` also hides the belt hotbar (`getPlayerHotbar(0)`) while Steve drives and shows it again after. **The hotbar part did not hold in game (2026-10-17, owner's screenshot): see B32.** Kept on purpose (my call, the owner may overrule): PZ's clock and speed controls (top right, clear of Minecraft's HUD; PZ time and pause still matter while Steve drives) and the build string (PZ draws it itself). Not run yet.
- Seen: in game
- Setup: Prism instance + PZ as in B2, new save `ZombCraftWorldGenTest`, Steve driving (3D on)
- Steps: 1. Press O. 2. Look at the bottom centre and the corners.
- Expected / actual: only the Minecraft HUD / PZ's hotbar widget (three boxes numbered 1, 2, 3 with item icons and an item-name tooltip such as "Belt Left") sits on top of Minecraft's hotbar slots 4-6; PZ's speed buttons and clock sit at the top right and the build string "42.21.0 4a0e9546ec pzBullet(1.0.0.28)" at the bottom right.
- Evidence: PZ screenshots after pressing O (this session 22:28), e.g. `release/evidence/20261014/m1a-idle.png` (top right and bottom right); the hotbar overlap is visible in every sheet's bottom row.
- Diagnosis / fix: `HudBridge` hides some PZ HUD parts but not the hotbar widget; with the layer now drawn before PZ's UI pass nothing covers the rest. Hide the hotbar (`ISHotbar`) while Steve drives, and decide about the speed controls. Not fixed.

### B7 Dev log line in McLauncher is garbled by a `\r` escape
- Severity: cosmetic
- Status: fixed in source 2026-10-15 (VPS), PZ jar compiled: the line now reads `no <path>, so no auto-launch; start Minecraft with the PzCraft Fabric mod yourself` (`McLauncher`). Moot since 2026-10-15 13:37 (maple): `McLauncher` was deleted with the auto-launch.
- Seen: logs only (PZ `console.txt`: `... start Minecraft yourself (scripts`)
- Setup: any run without `pzcraft.properties`
- Steps: 1. Start PZ without `Zomboid/Lua/pzcraft.properties`. 2. Read `console.txt`.
- Expected / actual: `no <path>; start Minecraft yourself (scripts\run-minecraft.bat)` / the text stops after `scripts`, because the Java string `"scripts\run-minecraft.bat"` contains `\r`, a carriage return.
- Evidence: `pz-mod/src/main/java/pzcraft/pz/McLauncher.java:43`.
- Diagnosis / fix: write `scripts\\run-minecraft.bat` or reword the message for players (they have no such script). Not fixed.

### B8 A save without PzCraft in its mod list loses Minecraft items on a save switch, and PzCraft says nothing
- Severity: major, possible blocker (lost data: Steve's Minecraft-only items; the setup is easy to hit when a player creates a world and does not tick PzCraft in "Choose Mods...")
- Status: fixed in source 2026-10-15 (VPS), PZ jar compiled; not run yet. Two guards in `MirrorBridge.tick` and a warning box: see "Fix" at the end of this entry.
- Seen: in game (inventory diff, Minecraft log, PZ console), one clean repro of the loss; the no-switch reload (A -> menu -> A) did not lose anything
- Setup: Prism instance + PZ as in B2. Save `ZombCraftWorldGenTest` (my first new save) was made with PZ's screens driven from Lua and ended up with an empty `mods { }` list (see "Cause of the setup" below), so PzCraft's Java side ran (ZombieBuddy loads it from the default list) but its item script did not: `ScriptManager.getItem("PzCraft.MCItem")` was nil, `ActiveMods.getById("currentGame")` empty.
- Steps: 1. Load a save whose `mods.txt` has no PzCraft. 2. Play (it takes over the old `pzcraft` world, so Steve carried iron sword, 61 planks, 64 stone, 32 glass, 15 cooked beef, iron pickaxe, 5 cherry logs, 4 sticks). 3. Quit to the main menu, load another save, quit, load the first save again.
- Expected / actual: Steve's inventory and blocks return / the blocks returned (diamond marker present, the other world's gold marker absent), but all 8 Minecraft-only stacks were gone and only PZ items were left. Minecraft log 22:42:23: `inventory mirror: applied 716692729278445: 0 added, 183 removed, 0 changed (rem [{"key":"mc|58b699b577298e01","n":64},{"key":"mc|ffcb2072160a3e2d","n":15}, ...`. The PZ mirror's last message was `PZ has no PzCraft.MCItem (is the script installed?)`.
- Also seen as a side effect: loading the owner's save `2026-10-14_21-40-29PZTest_1` right after that mod-less save printed 8 times `InventoryItemFactory.CreateItem() cannot create item: ... fulltype = "PzCraft.MCItem" ... removed = true` / `InventoryItem.loadItem() item == null, skipped bytes` (PZ console lines 3434-3448): its stand-in items were dropped on load. That save was backed up before the session (`pz-Saves` in the backup folder named in HANDOFF).
- Evidence: Minecraft `latest.log` 22:42:17-22:43:12; PZ `console.txt` 3434-3448; `getActivatedMods()` / `ActiveMods` dump (currentGame empty, default `[ZombieBuddy, Viewpoint, PzCraft]`); `E:\Zomboid\Saves\Apocalypse\ZombCraftWorldGenTest\mods.txt` is `mods { }`.
- Control run: the same round trip (save C -> existing save B -> C) with PzCraft in every save's mod list (new save `ZombCraftWorldGen2`, mods.txt = ZombieBuddy, Viewpoint, PzCraft): inventory identical before and after, including 7 TNT, a PZ M1A and an axe; emerald marker present (22:48:38).
- Cause of the setup: my first scripted creation called PZ's SOLO handler too early (`MainScreen.instance.delay > 0` makes it return), so it skipped `ActiveMods.getById("currentGame"):copyFrom(ActiveMods.getById("default"))`. A real player who clicks through PZ's menus gets the default list, but can untick PzCraft in "Choose Mods...", or load an older save without it.
- Diagnosis / fix: nothing checks that PzCraft's script is active before the mirror runs. Suggested: in `MirrorBridge` do not send removals while `ScriptManager.getItem("PzCraft.MCItem")` is missing (treat the mirror as off), and make `StartupHint` show "PzCraft is not enabled for this save: tick it under Mods" instead of "press O". Not fixed.
- Fix (2026-10-15, VPS; from code reading, the dev-run logs of the repro are still at maple): the 183 removals equal the 8 Minecraft-only stacks exactly, so PZ sent them as "removed" from a ledger that listed them while the pockets had no stand-ins. That happens in a save whose stand-ins were dropped on load (no `PzCraft.MCItem` script), and the mirror ran as soon as Steve drove after the load, while Minecraft still had the previous save's world open (it switches worlds a few seconds later), so the removal could land in the other save's world. `MirrorBridge.tick` now stays off (and drops any state it received) (1) while `ScriptManager.instance.getItem("PzCraft.MCItem")` is null, status `off: PzCraft's items are not loaded in this save`, and (2) while `WorldIdentity.minecraftHasThisSave()` is false, status `waiting for this save's Minecraft world` (the same check that holds back placing Steve). `load()` also forgets the session's per-kind notes (`unmirrorable`, `localOnly`, `refused`), which came from the previous save. `StartupHint` in game: amber "PzCraft is not enabled for this save / Its items are not loaded, so Minecraft's inventory is not mirrored here. / Quit to the menu, add PzCraft to this save's mods, and load it again." Check at maple: repeat the steps (mod-less save, switch, back) and compare Steve's inventory; `pzctl state` → mirror status; a normal save switch still mirrors after Minecraft has opened the new world.

### B9 HudBridge calls Lua from the wrong thread while a save is loading
- Severity: minor (an ERROR with a stack trace in `console.txt` on every save load, no visible effect seen)
- Status: fixed in source 2026-10-15 (VPS), PZ jar compiled: `HudBridge.tick` returns unless the state is `IngameState` (the loading thread owns Lua while a save loads); the error also counted as a failure, and after 6 of them the bridge stopped hiding PZ's windows for the session. Not run yet: load a save at maple and search `console.txt` for `wrong thread`.
- Seen: logs only (6 times in this session, once per load; PZ console lines 2830, 3421, 6050, 8678, 9225, 10223)
- Setup: Prism instance + PZ as in B2; every `GameLoadingState`
- Steps: 1. Create or load any save. 2. Search `console.txt` for `Lua code called from the wrong thread`.
- Expected / actual: no errors / `ERROR: Lua ... KahluaThread.pcall > Lua code called from the wrong thread, owner=GameLoadingThread current=MainThread` with the trace `pzcraft.pz.HudBridge.run(HudBridge.java:69)` <- `HudBridge.tick(HudBridge.java:51)` <- `FrameTick.run(FrameTick.java:31)`.
- Evidence: PZ `console.txt` (E:\Zomboid), e.g. around line 3421.
- Diagnosis / fix: `FrameTick` runs `HudBridge.tick` on the main thread during loading, when the loading thread owns Kahlua. Skip the Lua call while `GameWindow.states.current` is `GameLoadingState` (or catch and wait for `IngameState`). Not fixed.

### B10 Minecraft crashes at start-up if GeckoLib is missing, and the mod only says it "recommends" GeckoLib
- Severity: major (a player who skips one jar gets a crash instead of a clear message; the release pages must list GeckoLib as required, and the loader should enforce it)
- Status: fixed in source 2026-10-15 (VPS): `fabric.mod.json` has `"geckolib": "*"` under `depends` (the `recommends` block is gone); the store pages already list GeckoLib as required (`release/README.md`, `release/fabric-page.md`). Needs the rebuilt Fabric jar; check at maple that Fabric shows its missing-dependency screen without GeckoLib.
- Seen: in game (Prism, real launch, crash report written)
- Setup: Prism instance `Project ZombCraft` with Fabric API and the 0.15.0 jar, GeckoLib's jar moved out of `mods` (put back afterwards; size-checked)
- Steps: 1. Remove `geckolib-fabric-26.3-5.5.7.jar` from the instance. 2. Start the instance.
- Expected / actual: Fabric shows its "missing dependency" screen for a required mod / the loader logs only `Mod 'PzCraft' (pzcraft) 0.15.0 recommends any version of geckolib, which is missing!` and starts; then `java.lang.NoClassDefFoundError: com/geckolib/animatable/GeoItem` at `pzcraft.mc.PzItems.init(PzItems.java:56)` <- `PzCraftMain.onInitialize(PzCraftMain.java:11)`, "Could not execute entrypoint stage 'main'", Minecraft closes.
- Evidence: `crash-reports/crash-2026-10-14_23.01.17-client.txt` in the instance, copy in `release/evidence/20261014/crash-no-geckolib.txt`.
- Diagnosis / fix: `fabric-mod/src/main/resources/fabric.mod.json` lists geckolib under `recommends` (changed in the 2026-10-15 00:40 entry). The mod's item classes implement GeckoLib's `GeoItem` and load unconditionally, so GeckoLib is a hard requirement: move `"geckolib": "*"` from `recommends` to `depends` (and set it as a required dependency on Modrinth and CurseForge). Alternative (more work): load the gun items only when GeckoLib is present.

## 0.16.x findings (maple tests, 2026-10-15; logged here 2026-10-16)

The maple sessions of 2026-10-15 (HANDOFF journal 13:37 to 23:10) found these while testing 0.16.0/0.16.1 and fixed most in
source; they were not logged here at the time.

### B11 PZ closes at start-up, before the menu (JVM abort in PzCraft's screens)
- Severity: blocker
- Status: fixed in source 2026-10-15 14:00 (maple): `Patch_PzUiFrame` draws from `Display.update(boolean)` on the render thread, and `PzUi.glReady()` refuses to touch GL without a current context. Seen in game: PZ stays on its main menu with the start screen drawn. Not yet seen: the Zomboid-or-Steve choice and the Mojang loading screen drawn after the fix.
- Seen: in game (maple, `pz-launch.log`)
- Setup: dev, PZ 42.21 with the 0.16.0 PzCraft jar, Minecraft not connected
- Steps: 1. Start PZ.
- Expected / actual: the main menu / `FATAL ERROR in native method: ... No context is current` at `ImGuiImplGl3.init` <- `PzUi.create` <- `PzUi.frameEnd` <- `Display.imguiEndFrame` on `MainThread`; the JVM aborts.
- Evidence: `pz-launch.log` 2026-10-15 21:46 (maple clock); HANDOFF journal "2026-10-15 14:00 CST — PZ closes at startup".
- Diagnosis / fix: PzUi made its ImGui context on the first frame any of its screens was shown, on the main loop thread, which has no GL context. Every one of its screens hit this: the start screen (Minecraft not connected), the Mojang loading screen (Minecraft connecting or loading) and the Zomboid-or-Steve choice (New Game). So with Minecraft already connected, 0.16.0 aborted PZ as soon as character creation opened (`PzCraft_Start.lua` asks the choice there: New Game, or a save whose character is gone), or while Minecraft loaded: very likely the owner's "crashes upon loading worlds" (B16).

### B12 IllegalAccessError on every frame of the world: Viewpoint's camera calls a package-private PzCraft class
- Severity: blocker (the world's render throws every frame)
- Status: fixed in source 2026-10-15 23:10 (maple): `CharacterType` and `locksView()` are public. 2026-10-16 (VPS): every patch checked for the same mistake by the new `tools/patchcheck.py` (access check over the built classes): 56 patch classes, no other case; the check reports this one when the 0.16.0 `CharacterType` is compiled back in. Not yet seen in game after the fix.
- Seen: logs only (maple, MP client log)
- Setup: dev, 0.16.0/0.16.1 PZ jar, in a world
- Steps: 1. Load or join a world.
- Expected / actual: no errors / every frame `IsoWorld.renderInternal> Exception thrown` `IllegalAccessError: failed to access class pzcraft.pz.CharacterType from class viewpoint.camera.ThirdPerson`.
- Evidence: HANDOFF journal "2026-10-15 23:10 CST — multiplayer: player 1 in the MP world".
- Diagnosis / fix: ZombieBuddy copies an advice's code into the patched method (`Patch_SteveThirdPerson` into `viewpoint.camera.ThirdPerson.poll`), so whatever it touches must be public. Run `python tools/patchcheck.py` (build pz-mod first) before a PZ jar goes to a game.

### B13 PZ server Lua error on every "falls" command: PZ's Lua has no `next()`
- Severity: major (multiplayer: the error may also lose the "hello" reply that tells a player's Minecraft where to join)
- Status: fixed in source 2026-10-15 23:10 (maple): `PzCraft_Server.lua` looks through the table with `pairs`. No other `next(` in the mod's Lua (checked 2026-10-16). Not yet seen in game after the fix.
- Seen: logs only (maple, MP server log)
- Setup: dev, PZ dedicated server with PzCraft, one player joined
- Steps: 1. Join the server.
- Expected / actual: the client log says `multiplayer: this server's Minecraft server is 127.0.0.1:25565` / `Object tried to call nil in onClientCommand` at `PzCraft_Server.lua:54`; the client never gets the Minecraft server's address, so its Minecraft never joins.
- Evidence: HANDOFF journal "2026-10-15 23:10 CST".

### B14 Multiplayer test: "The Server Failed to Respond" (a `-nosteam` server, Steam players)
- Severity: major for the test (nobody can join), not a mod bug
- Status: fixed 2026-10-15 15:20 (maple): `scripts/mp/run-pz-server.bat` runs the server with Steam. 2026-10-16 (VPS): `release/multiplayer-test.md` tells hosts to run it with Steam, what `-nosteam` changes (no Workshop mods, players need `-nosteam` too), and the ZombieBuddy `NullPointerException` seen with a shallow `-cachedir`.
- Seen: in game (maple)
- Diagnosis / fix: a `-nosteam` server uses ZNetNoSteam, a Steam client ZNetJNI; they do not talk, and RakNet times out.

### B15 PZ's log says "PzCraft 0.13.0 loading" whatever version is installed
- Severity: cosmetic (misleads whoever reads a player's log)
- Status: fixed in source 2026-10-16 (VPS): `Main.version()` reads `modversion` from the `mod.info` beside the jar (the release packaging sets it), in both the client and the server log line. Not run yet.
- Seen: logs only (maple `console.txt`, 0.16.0)

### B16 "Crashes upon loading worlds" with 0.16.0 (owner report)
- Severity: blocker
- Status: not reproduced; probably B11 (PZ aborted when the Zomboid-or-Steve choice first drew, which is the moment a new world reaches character creation, or when the loading screen did) plus B12 (every frame of a loaded world threw). Both fixed in source; needs the owner's check at maple: New Game with Minecraft connected (the choice screen), loading a save, a world rendered for a minute with no `IllegalAccessError` in `console.txt`.
- Seen: owner, in game (no crash report kept: the JVM abort writes only to `pz-launch.log` in the dev launch)
- Evidence: HANDOFF journal "2026-10-15 13:37 CST — 0.16.0 test".

### B17 In multiplayer, PzCraft's in-game box says "single player only for now"
- Severity: minor (wrong since step M1, and it hid the other hints, so a player whose Minecraft never joined was told nothing)
- Status: fixed in source 2026-10-16 (VPS): `StartupHint` shows "PzCraft multiplayer is a test" for 20 s, then the usual hints, with multiplayer wording for "not enabled on this server" and "Minecraft is joining this server's Minecraft". `release/README.md` playtest 2 step 7 updated. Not run yet.
- Seen: code reading (the box the maple MP join would have shown)

## 0.16.2 findings (owner's own play test, 2026-10-16 evening; logged here 2026-10-16)

Logged from the owner's chat report. Where the owner gave no steps, the entry says so; nothing here is fixed or reproduced yet.

### B18 PZ crashes: NullPointerException when a zombie attacks Steve, PZ leaves the world
- Severity: blocker
- Status: guard built, NOT run in game. `BoredomGuard` + `Patch_BoredomSquare` (2026-10-16 23:50, maple) skip PZ's boredom tick while the character has no current square, so the NullPointerException does not happen and the world stays open. Built: pz-mod jar `BA96800F72361513…` (487,176 B); `tools/patchcheck.py` 57 patch classes, 0 problems. **Root cause NOT found:** why Steve's current square becomes null is still open. Checked: the attacked character is Steve's `IsoPlayer` (PZ's `AttackState.triggerPlayerReaction` casts `zombie.target` to `IsoPlayer`, by `javap`); `PuppetDriver.apply` only sets the square when `findCurrentGridSquare()` finds one; no `setCurrent(null)` on the movement path in `IsoMovingObject`/`IsoGridSquare`/`IsoGameCharacter`/`IsoPlayer`/`IsoZombie` bytecode (only `IsoWorld.KillCell` and `resetForReuse`). Candidate only: mining at tile (10792,10235) (`block removed … square solid=false`) just before the fight. The guard hides a symptom: check the `boredom tick skipped` log lines in the next play to see how often it happens.
- Seen: owner, in game (the crash); logs read by Claude (maple). **Live test with the fix jar (2026-10-16 23:40-23:49, Claude via the PZ MCP):** the owner's save loaded, Minecraft started from Prism, Steve placed in 3D; the game was unpaused by setting the game speed to 1 and hiding the Esc panel. Zombies spawned at 2-4 tiles (`spawn 2 4`, `spawn 3 2`) chased Steve and stayed 0.6-0.9 tiles away in `PathFindState`/`LungeState` for about four minutes without attacking: `zombieAttacksForwarded` 0, `pzDamageEvents` 0, Minecraft health 20. No NPE in the session. Steve's square forced to nil once from Lua (`setCurrent(nil)`): PZ restored it in the same frame; no NPE and no skip line. **The guard is verified at the crash site (23:51:14):** `pz_lua` cleared Steve's square and called `UpdateBoredom()` in the same Lua call (so PZ could not restore it between): the console logged `boredom tick skipped: the character has no current square (1 so far; B18)` and the call returned with no NPE. **The attack path itself is still not reproduced:** the zombie never gets to fire the attack event (see B31).
- Setup: maple, 0.16.2 release files (PZ jar `40e04bae…`, Fabric `720a8992…`), PZ started with `scripts\run-pz.bat`, Minecraft from the Prism instance. The owner's save was not recorded; `latestSave.ini` names `Apocalypse/2026-10-15_21-57-10` (unconfirmed).
- Steps: not recorded (owner's report: mining, then a zombie fight).
- Expected / actual: no error / `IngameState.updateInternal> Exception thrown`: `NullPointerException: ... IsoGameCharacter.getCurrentSquare()` is null at `BodyDamage.UpdateBoredom(BodyDamage.java:1994)`, reached from `AttackState.triggerPlayerReaction` (a zombie's attack event). `STATE: exit zombie.gameStates.IngameState` 4 s later; Minecraft logs `PZ disconnected` at 23:29:13 and closes.
- Evidence: `E:\Zomboid\Logs\2026-10-16_23-13_DebugLog.txt` lines 3068-3110 and the exit line; `E:\Zomboid\console.txt` lines 3075-3110; Minecraft `E:\PrismLauncher\Instances\Project ZombCraft\minecraft\logs\latest.log` 23:28:49-23:29:16. No new Minecraft crash report (the newest is 2026-10-14).

### B19 Creative mode: a furniture piece needs more than one click to break
- Severity: minor
- Status: fixed in source 2026-10-17 (maple), built, NOT run in game. Cause: mining PZ things (`PzMining`, W2) runs vanilla's survival sum against the thing's stand-in block and its own hardness, with no creative case, so a chair took the same clicks as in survival. `PzMineables.Def.progressPerTick` now returns 1 in creative (breaks on the first click, as vanilla's creative does; the 5-tick pause between breaks is kept). Check: creative, left-click a chair, a couch (two tiles) and a bush once each.
- **Verified in game** (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): one 60 ms left-click on a two-tile low table picked it up (`pzMining.mined` 0 → 1, `picked up furniture_tables_low_01_8 (1 item)`, a `Base.Moveable` reached Steve's hotbar).
- Seen: owner, in game
- Steps: not recorded. Owner expects one click in creative.
- Expected / actual: one click breaks it / more than one click.

### B20 Creative mode: guns use up ammo (should be unlimited)
- Severity: minor
- Status: fixed in source 2026-10-17 (maple), built, NOT run in game. PZ owns the rounds (`GunBridge`), and nothing told it Steve's game mode. Minecraft now sets `PlayerState.FLAG_CREATIVE` (1 << 4) in Steve's per-frame state (`FrameHook`); `GunBridge.fire` puts the fired round back for a creative Steve (rounds, chamber, spent count, no jam) and, for pump/bolt/lever guns, the rack after the shot chambers a round without taking it from the magazine (`Rack.free`; it chambers one even from an empty magazine). A gun still has to be loaded once: an empty gun clicks in creative too. Reloading, loading magazines and unloading are unchanged. Check: creative, M9 with a loaded magazine, 30 shots: the count stays; same for a pump shotgun and a revolver; survival still uses rounds.
- **Verified in game** (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): Steve's frame flags read 17 (on ground + creative); `tools/pz-mcp/check-creative-gun.py 5` with a `Base.Pistol` at 15+1: five shots `fired`, the gun stays 15+1 after each. Not tested: a pump/bolt gun (the free rack) and a revolver; survival.
- Seen: owner, in game
- Steps: not recorded.
- Expected / actual: unlimited rounds in creative / rounds are used up.

### B21 Steve cannot sit on PZ furniture
- Severity: major (a W4 feature does not work)
- Status: fixed in source 2026-10-17 (maple), **verified in game** (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`).
- Cause 1 (every seat, every time): Minecraft 26.3's server refuses to let anyone ride an entity whose type cannot be saved (`Entity.startRiding`: `!this.level().isClientSide() && !entityToRide.type.canSerialize()`), and the seat entity `PzSeat` was registered `noSave()`. So `PzRest.sit` made a seat, `startRiding` failed, and the seat was discarded. PZ's car seats (W5) use the same entity, so getting into cars was broken the same way (cars not retested).
- Cause 2 (at night): PZ gives every seat its bed flag (it lets you sleep in a chair; all 15 seats near the test house have it), and the rule was "a seat that is also a bed is slept on when the night allows", so after dusk every chair became a bed.
- Fix: `PzSeat` is a normal (serializable) type, kept out of the world and of the player's save by overriding `shouldBeSaved` and `saveAsPassenger`. `PzRest.use`: a seat is sat on; right-clicking it again while sitting lies down on it when the night allows sleep; a bed that is not a seat is slept in. While Steve sits, Minecraft's frame flags carry `PlayerState.FLAG_SEATED`, and PZ keeps him on the seat's storey (his feet hang 0.6 below the seat, under the floor for a couch at 0.51, which put PZ's character on storey -1, where outside basements there is no square; `PuppetDriver`).
- Verified: by day a right-click on the couch seats Steve (on ground false, flags 48 = creative + seated, PZ z 0.0005, square kept); a second right-click by day keeps him sitting; at night (`time set 13000`) a right-click on the seat while sitting lies him down (sleeping true). Sleeping then skips the night and PZ's clock follows (by design).
- Seen: owner, in game
- Steps: not recorded. Expected per HANDOFF 12:57: right-click a seat, Steve sits.
- Expected / actual: Steve sits / he does not.

### B22 A PZ flame is drawn on a zombie that is burning from Minecraft fire
- Severity: minor (visual)
- Status: fixed in source 2026-10-17 (maple), built, NOT run in game. PZ's `IsoGameCharacter.SetOnFire` attaches its flame as an attached anim sprite (`AttachAnim("Fire", "01", ...)`; `StopBurning` clears the whole `attachedAnimSprite` list, so it is a character's only one), while Minecraft draws its own flames on the zombie's stand-in (`PzFire.proxy` keeps it burning from `FLAG_BURNING`). `FireBridge.tick` now clears that list every frame for each burning zombie or animal Minecraft has a stand-in for (`Gameplay.exportedActors()`), whatever lit it (Minecraft fire or PZ's own). Counter `pzFlamesHidden` in `pz_status` (fire stats). A burning zombie beyond the 24-tile actor range keeps PZ's flame (no stand-in there).
- **Verified in game** (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): a zombie spawned 3 tiles away and set on fire from Lua (`SetOnFire`): its attached sprites went from 1 to 0 within a frame and the screenshot shows Minecraft's flames on it only.
- Seen: owner, in game
- Steps: not recorded. Owner: "pz flame shouldn't appear if it minecraft flame".
- Expected / actual: only Minecraft's flames / PZ's flame too.

### B23 A zombie entity shows up while zombies burn
- Severity: major (visual: an extra entity)
- Status: fixed in source 2026-10-17 (maple), built, NOT run in game.
- Evidence: the owner's Minecraft log `logs/2026-10-16-1.log.gz` 23:24:19, right after flint and steel near zombies: `native entity minecraft:zombie ...: 96 triangles (ZombieRenderState as ZombieRenderer; calls submitModel,submitFlame,submitShadow)` and `entity texture minecraft:textures/entity/zombie/zombie.png -> slot 21`: the whole vanilla zombie model, textured, was sent to PZ's scene.
- Cause: a stand-in is made invisible once (`ActorProxies`, `setInvisible(true)`), but Minecraft clears that flag on the mob's first tick: `LivingEntity.effectsDirty` starts true and `updateInvisibilityStatus()` sets invisible to false when the mob has no Invisibility effect. So every stand-in is really visible. The video layer still hides them (`EntityVisibilityMixin`), and the scene export skips them unless they burn (`EntitySceneExporter`: proxies and invisible entities only for their flames), so the model showed exactly while a zombie burned.
- Fix: `ActorProxies` sets the stand-in invisible again every tick it is synced (it stays invisible after the first tick); `EntitySceneExporter` exports a burning stand-in as flames only (render state invisible to the player, no name tag, no shadow), whatever its flag says. Check: set zombies alight (flint and steel, fire aspect, lava) and watch: Minecraft flames only, no green zombie, no PZ flame (B22).
- **Verified in game** (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): the burning zombie's stand-in is now captured as `native entity minecraft:zombie ...: 12 triangles (... calls submitFlame)` (Prism `latest.log` 08:00:52), no model and no zombie texture; the screenshot shows flames only.
- Seen: owner, in game
- Steps: not recorded.
- Expected / actual: one zombie burning / an extra zombie entity appears.

### B24 Some items were not visible
- Severity: major (every item PZ draws for Minecraft could be untextured)
- Status: a cause found, fixed in source and **verified in game** 2026-10-17 (maple); the owner's own cases are still unknown (no item names or places were given).
- Evidence: Prism `latest.log` 08:00:35: `entity texture minecraft:textures/atlas/items.png (8192x4096) cannot be published: format RGBA8_UNORM, needs 134217728 bytes, 134193152 bytes free` and `entity texture read-back failed ... entity texture space exhausted`.
- Cause: with PZ's ~5,000 item icons in the resource pack, Minecraft's item atlas is 8192x4096, 128 MiB, which is the whole shared texture space PZ draws Minecraft entities and items from (`EntityLink`, 128 MiB). As soon as any other texture (Steve's skin) was published first it never fitted, so items PZ draws (held in first person, dropped, carried by entities) had no texture.
- Fix: `EntitySceneExporter.readBack` publishes a texture over 32 MiB at half size or less (box filter weighted by alpha, so cut-out edges keep their colour); UVs are relative, so the same mapping holds. Verified (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): `entity texture minecraft:textures/atlas/items.png -> slot 3 (4096x2048, from 8192x4096)` and the held flint and steel shows in first person. Item icons are drawn at half their resolution in PZ's picture (Minecraft's own hotbar is unchanged).
- Seen: owner, in game

### B25 Underground: a delay after digging before the hole or tunnel is lit
- Severity: moderate
- Status: likely cause fixed in source 2026-10-17 (maple), built; the lag itself was **not reproduced** (an open-sky two-block dig was lit in the first screenshot, `tools/pz-mcp/check-dig-light.py`). The tunnel case is untested. Related to B26.
- Cause (code): Minecraft's blocks reach PZ as meshes with Minecraft's light baked into each face (`BlockSceneExporter`), exported when a block changes and otherwise once a second. Minecraft's light follows a block change a few ticks later (the server's light engine, then its packet), so the export made at the change carried the old light until the next 1 s rescan.
- Fix: after an export caused by a block change, three more follow at 0.2 s intervals, then the 1 s rescan again. Verified that they run: in the dig check the scene's mesh revision now changes between 0.0 s and 0.3 s after the dig (before: only at the 1 s rescan).
- Seen: owner, in game
- Steps: not recorded (dig up to a cave or tunnel).
- Expected / actual: the cave is lit as soon as it is open / a delay.

### B26 Lighting updates lag behind block changes
- Severity: moderate
- Status: as B25 (same cause and fix, built; not reproduced; check a torch placed and broken in a dark room). Minecraft's lamps reach PZ every 10 server ticks (`EnvironmentBridge`), unchanged.
- Seen: owner, in game
- Steps: not recorded (placing or removing blocks near lights).

### B27 The newer Viewpoint messed things up again
- Severity: major
- Status: open, needs details (what broke). Checked 2026-10-17 (maple): the Steam copy is Viewpoint `modversion=0.1.6.1` (`mods/Viewpoint/common/mod.info`). `python tools/patchcheck.py --viewpoint <Steam Viewpoint.jar> --pz <projectzomboid.jar>`: 57 patches, 0 problems (every patched class and method is still there, arguments fit); every Viewpoint class and member PzCraft reaches by reflection (`View`, `Look`, `ShadowPass`, `MeshArena`, `ThirdPerson`, `SceneDrawer`, `FP`, `ChunkBuilds` and their fields/methods) exists in 0.1.6.1. So nothing fails to hook; what changed is behaviour, and no 0.1.6 jar is kept to diff against. Needs the owner's description (what looks or acts wrong, in which view). The Steam copy of Viewpoint was updated at 21:37 today (`E:\SteamLibrary\steamapps\workshop\content\108600\3809306528\mods\Viewpoint\42\media\java\client\Viewpoint.jar`, modified 2026-10-16 21:37). The PzCraft ports (HANDOFF 2026-10-15 11:16) were made for Viewpoint 0.1.6.
- Seen: owner, in game
- Steps: not recorded.

### B28 Minecraft blocks pass through PZ walls and roofs (noclip)
- Severity: major
- Status: roofs: fixed in source 2026-10-17 (maple), **verified in game** for sloped roof pieces; walls: not reproduced, needs the owner's description.
- Roofs, cause: PZ gives a roof square a flat floor at its storey (or no floor), while Viewpoint draws the roof sloped, so Minecraft had no roof at all: Steve stood inside the sloped roof (third-person screenshot: the eave through his head) and blocks could be placed through it.
- Roofs, fix: new `pz-mod/.../RoofShapes.java` asks Viewpoint for each `roofs_*` sprite's shape (`viewpoint.world.MapRoofs.piece`, `RoofPiece.height`, by reflection; without Viewpoint nothing changes) and `WorldExporter` sends the square as a 4x4 ramp following it (the same boxes stairs use: solid from the floor up to the roof surface). Verified (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): dropped onto the porch roof Steve lands on the shingles (y 3.98, not the flat 2.449) and walks up the slope to the ridge (y 4.91 → 5.82 → 6.43); third-person screenshot shows him on the roof surface.
- Roofs, limit: Viewpoint fills in roof between the sprites PZ places (squares that only have a `ceilings_*` floor, here x 10833-10836 at level 2); those keep the flat floor at their storey, because Viewpoint's assembled roof is internal to its mesh builder. Walking over a ridge onto such a square drops Steve onto that flat floor, 1.5 blocks below the ridge (he can jump or build out).
- Walls: a dirt block placed against a house's exterior wall from inside (`(10839, 0, 10156)`, wall on the north edge of y 10157) does not show through the wall from outside (screenshot). Placement is still refused where a block would sink more than 0.12 into PZ geometry (`BlockPlacementMixin`); a block beside a wall overlaps its 0.2-thick collision by 0.1, which is allowed. Needs the owner's description of what passed through.
- Seen: owner, in game
- Steps: not recorded.

### B29 Steve cannot walk up PZ stairs to the second floor
- Severity: major
- Status: fixed in source 2026-10-17 (maple), **verified in game** on the staircase that blocked (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`).
- Reproduced: the staircase at (10839, 10152-10154) rising north, entered from the floor at y 10156: Steve stopped on the first step at z 10154.80, y 0.411, holding forward for 3 s. (The staircase at (10835-10837, 10179) only worked because Steve was teleported onto its first step; its foot is a dug pit.)
- Cause: headroom. A storey is 2.449 blocks and Steve 1.8 tall. The storey above covers the tile in front of the stairs (its floor slab's underside at 2.349) and, here, has a wall over the stairwell's end edge starting at 2.449. While Steve's back (0.3 from his centre) is still under them, the first steps would lift his feet to 0.68, his head to 2.48: Minecraft refuses the step.
- Fix: protocol `CollisionSection.STAIRWELL` (flag bit 7) with bits in `heights`: on the storey above a staircase a floor slab stops 0.3 short of the sides where a staircase is below its neighbour (`TRIM_*`, `CollisionBoxes.STAIRWELL_GAP`, under Steve's 0.6 width so nobody falls through upstairs), and a wall on a tile's north or west edge with stairs below either side starts 0.6 above its floor (`LIFT_*`, `STAIRWELL_HEADROOM`). PZ side: `WorldExporter.stairwell`. Tests: `CollisionTest.floorOverAStairwell…`, `wallOverAStairwellEdge…`.
- Verified: from y 10156 Steve walks up to the second floor in about a second (y 0.13 → 1.37 → 2.10 → 2.449, PZ z 1.000) and back down (2.449 → 2.30 → 1.65 → 0.92 → 0).
- Seen: owner, in game
- Steps: not recorded.

### B30 Second floor, higher floors and the rooftop: block positions are miscalculated
- Severity: major
- Status: one cause fixed in source 2026-10-17 (maple), built, NOT run in game; the look of blocks on upper floors is a limit of the scale (below).
- Cause found (PZ side): a PZ storey is sqrt(6) = 2.449 blocks tall, so level 1's floor is at y 2.449 and a block placed on it is the one at y 2 (2 to 3; it sinks 0.45 into the floor slab, which is only 0.1 thick, so `BlockPlacementMixin` allows it). `BlockBridge.levelOf` filed a block under `floor(y / 2.449)` = level 0 for y 2: the ground-floor tile under it became solid in PZ (zombies and pathing blocked downstairs) while the upper floor stayed open. Now the storey of the block's centre, `floor(pzZ(y + 0.5))`, as `LightBridge` already does: y 2 and y 4 go to level 1, y 5 to level 2.
- Not fixable by the mapping: Minecraft blocks sit on whole blocks and PZ floors above the ground do not (2.449, 4.899, 7.348...), so a block on an upper floor always sits off its floor (0.45 sunk on level 1, 0.1 above on level 2, 0.35 sunk on level 3). Changing the storey height would break Viewpoint's scale and Steve's eye.
- Check: on a second floor, place a block, walk a zombie into it on that floor (blocked) and on the ground floor under it (not blocked); `console.txt` `block placed at tile (x,y) level 1`.
- **Verified in game** (2026-10-17 08:00-08:33, maple: save `Apocalypse/Test_0.16.2` (backed up first to `E:\PzCraftTest\backup-before-fixtest-20261017`), Minecraft from the Prism instance, Steve in creative, driven through the PZ MCP; final jars PZ `ad83c143…`, Fabric `af0b35b5…`): dirt placed on the second floor at y 2: `block placed at tile (10834,10179) level 1 -> square solid=true` (before the fix: level 0). Zombie pathing against it not tested.
- Seen: owner, in game
- Steps: not recorded.

Not logged as a bug: driving PZ vehicles (W5) is untested by the owner, so it stays "not tested" in the brief.

### B31 Zombies chase Steve and stand at contact without attacking (not seen by the owner: found by Claude on the fix jar)
- Severity: major (Steve takes no damage from zombies; it also hides B18, because the attack path that crashed is not reached)
- Status: **not a bug** (owner, 2026-10-17 after testing the 00:30 PZ build: "B31 is false bug report. zombies are attacking the players"). The 2026-10-16 runs were artefacts of the debug session (dev-spawned zombies, one forced into `AttackState` by hand, a pull to 1.00 tile, beyond PZ's 0.72 reach). The notes below are kept for the record.
- Was: open, not diagnosed. Observed 2026-10-16 23:40-23:49 on the B18 fix jar, via the PZ MCP.
- 2026-10-17 (maple, code reading of PZ 42.21): the attack transition (`media/actiongroups/zombie/{idle,lunge}/to_attack.xml`) needs the variable `bAttack` and `isFacingTarget`. `bAttack` is `IsoZombie.getShouldAttack()` (registered as `battack`), which for a standing zombie needs: `|target.z - zombie.z| < 0.2` (0.2 storey = 0.49 block: Steve on a slab or a block is out of every zombie's reach), the target not ghost, on the floor, in a car or reanimated, nothing between the two squares (`isSomethingTo`), and `vectorToTarget` at most **0.72** tiles. The 23:58 repo check pulled zombie 147 to 1.00 tile, and that zombie had been forced into `AttackState` by hand before, so `bAttack` could never be true there: that run tells nothing. Steve was on flat ground then (Minecraft eye y 1.62, PZ z 0.0005). `tools/pz-mcp/check-zombie-attack.py` now records each input of the gate per sample (`dz`, `between`, `bAttack`, `facing`, crawling, Steve's state) and has `--walk` (spawn 3 tiles away and let it come). Next: run `check-zombie-attack.py --walk` in a fresh session (not in the save where 147 was forced).
- Seen: Claude, logs and `pz_lua` probes (not in the owner's report)
- Setup: maple, the owner's test save `Apocalypse/2026-10-15_21-57-10`, Minecraft from Prism, Steve in 3D, Steve HP 20 (Minecraft owns health).
- Steps: `spawn 2 4`, then `spawn 3 2` (dev commands), then Steve steps back 1 s (`pz_input BACK 1000`) and waits about 15 s.
- Expected / actual: the zombie lunges and attacks (AttackState) and the hit reaches Minecraft (`HealthLink.zombieAttack`) / actual: zombies in `PathFindState` or `LungeState` at 0.6-0.9 tiles, `zombieAttacksForwarded` 0, no damage.
- Evidence: `pz_status` at 23:50 (`health.zombieAttacksForwarded: 0`, `pzDamageEvents: 0`); `pz_lua` probes at 23:47-23:49 (`z114 state=LungeState`, then `PathFindState`, distances 0.56-0.90, `target=true`); Minecraft `latest.log` `health=20.0` through 23:48.
- Checked (Claude, 23:52-23:56, the same session): the zombie's target is Steve's IsoPlayer (ID 4), `isTargetVisible()` true, the squares `isCouldSee(0)` true, `isVisibleToNPCs()` true, Steve not invisible, ghost or god. `spotted(p, false)` changes nothing (state stays `PathFindState`). Forcing `AttackState` (`z:changeState(AttackState.instance())`) sets `isAttacking() = true`, but the zombie stays in `AttackState` at 0.54 tiles for 40+ s with no hit: the attack animation event never fires. The zombie's animation scheduling (`IsoGameCharacter.postUpdateAnimating` returns early when `animationUpdatingThisFrame` is false) is the next thing to check. **Repo check `tools/pz-mcp/check-zombie-attack.py` (23:58, `zombie-attack-20261016-235845/result.json`):** the zombie has `AttackType = bite` and `attackOutcome = start`, but `bAttack` stays false for 30 s: the animator does not move into the attack clip. Alpha is 1 and `animationUpdatingThisFrame` is not the limit (alpha check), so the next suspect is the animator's state graph for this zombie. Not yet known whether a fight in normal play hits the same gate; the owner's 23:28 crash came from a real attack, so the gate depends on something in this session.

## 2026-10-17 findings (owner, maple)

### B32 PZ's belt hotbar (numbered slot boxes) still draws over Minecraft's hotbar while Steve plays
- Severity: minor (visual; the owner's screenshot 2026-10-17 shows PZ's slot "1" box over Minecraft's 4th slot; owner: "disable this project zomboid hotkey inventory ui")
- Status: fixed in source 2026-10-17 (maple), built, NOT run in game. B6's fix did not hold.
- Cause: `HudBridge` hid the bar (`getPlayerHotbar(0):setVisible(false)`) every 10 frames, but PZ's own `ISHotbar:update()` (`media/lua/client/Hotbar/ISHotbar.lua`) turns it back on every frame when it is hidden while the moodles are visible ("Controller unplugged").
- Fix: new `pz-mod/mod/42/media/lua/client/PzCraft_Hotbar.lua` wraps `ISHotbar:update` and `ISHotbar:render`: while `PzCraft.Multiplayer.drivesSteve()` the bar is hidden after PZ's update (its bookkeeping still runs), its item tooltip is hidden and nothing is drawn; when Steve hands back it is shown again before PZ's update (which hides it again itself in a car). `HudBridge` no longer touches the bar. Its keys were already muted for Steve (`Patch_MuteBinding`, Hotbar 1-8). It is Lua: it needs the mod folder installed (`installMod` or the release zip), not only the jar `run-pz.bat` copies. Check: Steve in 3D, no numbered boxes at the bottom; press O to go back to the PZ character: PZ's hotbar is back.
- **Verified in game** 2026-10-17 (owner's own test of the 00:30 PZ build, and Claude's screenshots 08:00-08:33): only Minecraft's hotbar at the bottom. Not checked: the bar coming back after O.
