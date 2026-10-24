# Releasing Project ZombCraft

Owner's plan (2026-10-14): release the mods first, publish the source later. Until the source of a version is published,
its release files are **All Rights Reserved** (`LICENSE-ARR`); the source will go out under Apache-2.0 (`LICENSE`, `NOTICE`).
Don't push to the public GitHub repo and don't make a GitHub release for these.

| File | What it is |
|---|---|
| `workshop/workshop.txt` | Steam Workshop title, description (Steam BBCode), tags and visibility, read by PZ's uploader. `@VERSION@` is filled in by the packaging script. |
| `workshop/preview.png` | Workshop preview: a plain title card. Replace it with real art if you like (PNG, square, 256 or 512 px, under 1 MB; PZ refuses anything else). |
| `fabric-page.md` | Page text for Modrinth and CurseForge (both take Markdown). |
| `changelog-<version>.md` | Version notes for all three pages. |

## 0. Before releasing: playtest and debug

0.16.0 is the 0.15.0 build that was never released, plus the fixes for what its playtest found (`BUGS.md`: B1, B3-B10;
B2 is parked with the guns) and support for Viewpoint 0.1.6 (HANDOFF journal 2026-10-15). The 0.15.0 build was playtested
at maple on 2026-10-14; 0.16.0 has not run in game yet. Do these in order.

**Playtest 0: what changed since the 0.15.0 playtest.** The checks are in `HANDOFF.md` (journal entries 2026-10-15 10:18,
11:16 and the start-flow entry after them): above all, a save without Project-ZombCraft in its mod list must not cost Steve his Minecraft items on a save switch (B8),
and with Viewpoint 0.1.6 PZ's console must show no `[!] Method ... not found` for Project-ZombCraft and the world must stay lined up
with Minecraft's picture through shots, hits and explosions.

**Get the files to maple.** The newest work exists only on the VPS until it is committed. Either commit and push it to the private repo and rebuild at maple, or copy `release-staging/0.16.0/` over and test those files: they are what players get.

**Back up first:**
- `Zomboid/Saves`
- the Minecraft instance's `saves` folder

The first PZ save you load takes over the old `pzcraft` Minecraft world, with Steve's inventory and blocks.

**Playtest 1: the 0.15.0 fixes, again on 0.16.0**
1. **Main menu:** the box says "start Minecraft" before Minecraft is up and "Minecraft is connected" after. Check it's readable at your resolution.
2. **Per-save worlds:**
   - Load your main save first. It must keep the old world (Steve's inventory and blocks).
   - Load a second save. It must get a new, empty world.
   - Go back to the first save. Its world must reopen.
3. **In game:** the "press O" box shows, and O starts Steve.
4. **Pausing:**
   - Space and the Esc menu freeze hands, gun and HUD, and unpausing resumes them.
   - A Minecraft chest still opens and works.
   - The Esc menu dims the Minecraft layer, and PZ's map covers it.
5. **M1A, JS-14, Trapper, M9, M1911, B-F, M16** in hand: idle, fire, reload (with rounds left and empty), magazine out (Shift+R), rack, inspect (I), sprint. The M1A, JS-14 and Trapper are racked by the right hand (also at the end of an empty reload) while the left stays on the forend; no arm shows the flat end of its box anywhere (release/BUGS.md B2). Their **9mm, .45, .44 and 5.56 magazines** in hand: rounds in and out one at a time.
6. **Effects:** muzzle flash, shells, tracers, blood and debris on hits, crosshair.

**Playtest 2: as a player** (the real release test)
1. Rename `~/.pzcraft/private/pointblank-26.1.2-2.2.0/guns` and `~/.pzcraft/items/weapon-models` so the private sets are out of the way. Rename them back afterwards.
2. Make a new Prism instance by following `fabric-page.md` word for word. Add Fabric Loader, Fabric API, GeckoLib and the staged jar.
3. Remove the dev `Project-ZombCraft` (and any old `PzCraft`) from `Zomboid/mods`; two mods with the same id clash. Then install the staged one, either the zip or the Workshop folder's `Contents/mods/Project-ZombCraft`.
4. Use ZombieBuddy's normal profile, not the dev approvals. Its prompt must appear, and approving Project-ZombCraft must work.
5. **Guns:** the M1A, JS-14, Trapper, M9, M1911, B-F and M16 and their four magazines are 3D, every other gun is a flat icon, and nothing anywhere shows a purple-and-black checkerboard.
6. **Versions:** PZ's mod list shows Project-ZombCraft 0.16.0 with author and link. Mod Menu in Minecraft shows 0.16.0 and All Rights Reserved.
7. **Optional, multiplayer** (`release/multiplayer-test.md`): joining a server shows the "Project-ZombCraft multiplayer is a test" box for 20 s, then the usual hints; Minecraft joins the host's Minecraft server and Steve can be played.

**Debugging: what the logs should say**
- **`Zomboid/console.txt`:** `Minecraft connected`, and no Project-ZombCraft errors or stack traces.
- **Minecraft `logs/latest.log`:**
  - in the player setup: `Gun visuals: 11 kinds (11 our own, 0 private)` (seven guns and four magazines);
  - no `missing or changed`;
  - no missing-texture lines for `pzcraft:`.
- If something is wrong, keep both logs and a screenshot.

**Then:**
1. Decide what 0.16.0 includes and trim `changelog-0.16.0.md` to match.
2. Send `vic-permission-request.md` to Vic. Wait for the answer only if you want the M9, M1911, B-F Pistol and M16 in this release, or want the rifles' MK14 joint layout cleared first.
3. Take screenshots for the pages, and swap the preview art if you like.

## 1. Build the files

```
python tools/package-release.py 0.16.0
```

This writes `release-staging/0.16.0/`:
- `Project-ZombCraft-Fabric-0.16.0.jar`
- `Project-ZombCraft-Zomboid-0.16.0.zip`
- `Workshop/ProjectZombCraft/`

The release is All Rights Reserved by default; `--license apache` builds it as Apache-2.0 once its source is published.

## 2. Steam Workshop (the PZ mod)

1. Copy `release-staging/0.16.0/Workshop/ProjectZombCraft` into `C:\Users\<you>\Zomboid\Workshop\`.
2. In PZ's main menu, open *Workshop*, choose *Create and update items*, then *ProjectZombCraft*.
3. Check the title, description and tags. Add more tags if they fit. Upload; the visibility starts as **private**.
4. On the item's Steam page:
   - Under *Add/Remove Required Items*, add **ZombieBuddy** (3619862853) and **Project Viewpoint** (3809306528).
   - Add screenshots.
   - Switch the visibility to **public**.
5. PZ writes the new item id into the `workshop.txt` in your Zomboid folder. Copy that `id=` line into `release/workshop/workshop.txt` too, so the next version updates the same item instead of creating a new one.

## 3. Modrinth (the Minecraft mod)

| Field | Value |
|---|---|
| Name | Project ZombCraft (slug `project-zombcraft`) |
| Summary | Play Project Zomboid as Steve: the Minecraft half of Project ZombCraft. |
| Description | `fabric-page.md` |
| Categories | Game Mechanics, Adventure |
| Client / server | client: required; server: unsupported |
| License | All Rights Reserved |
| Links | Issues: https://github.com/Lumi-nary/Project-ZombCraft/issues, Source: leave empty until the source is published |
| Icon | `workshop/preview.png` |

Version: `Project-ZombCraft-Fabric-0.16.0.jar`, version number 0.16.0, channel **Beta**, loader Fabric, game version 26.3. Dependencies: Fabric API and GeckoLib required, Better Combat optional. Changelog: `changelog-0.16.0.md`.

## 4. CurseForge (the Minecraft mod)

Under Minecraft → Mods, use the same name, summary, description and icon. Main category: Miscellaneous; license: All Rights Reserved. For the file: release type **Beta**, Minecraft 26.3, Fabric. Relations: Fabric API and GeckoLib as required dependencies, Better Combat as optional. Changelog: `changelog-0.16.0.md`.

## 5. Link the pages

Once all three pages exist:
- **Workshop description:** in `workshop/workshop.txt`, replace `MODRINTH-LINK` and `CURSEFORGE-LINK`. Then update the Workshop item, or edit the Steam page directly.
- **Modrinth and CurseForge descriptions:** in `fabric-page.md`, replace `WORKSHOP-LINK` and paste the updated text into both pages.
