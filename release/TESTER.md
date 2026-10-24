# Brief for the playtest and debug session (maple-pc)

You are the playtester and debugger for the 0.15.0 release of Project ZombCraft (PzCraft). Another Claude session wrote the code on a VPS that cannot run either game; **nothing in 0.15.0 has run in game yet**. Your job is to run it, find what breaks, and log it. The owner is at maple-pc and plays along.

Read `HANDOFF.md` first (section 1, then the journal entries from 2026-10-14 15:30 on), then `release/README.md` section 0, which is your test plan.

## Rules

- **Log first, fix second.** Every bug goes into `release/BUGS.md` (format at the top) before you touch code. Fix a bug only if the cause is clear and the fix is small and local; otherwise leave it logged with your diagnosis.
- **Never claim more than you saw.** Mark each item *seen in game*, *read from logs only* or *not tested*. A build that compiles is not a test.
- **Announce anything that takes the desktop**: launching a game, input injection, a restart. Prefer the control API (`tools/pz-mcp/pzctl.py`) over desktop input, and check for running PZ/Minecraft processes first. The owner may be playing.
- **Back up before touching saves** (`Zomboid/Saves`, the Minecraft instance's `saves`). The first PZ save loaded takes over the old `pzcraft` Minecraft world.
- **Do not commit, push, upload or publish** anything unless the owner says so. No Workshop, Modrinth, CurseForge or GitHub release. No licensed game files or Point Blank files into the repo.
- **Do not change the licence files, the release pages or `release/vic-permission-request.md`.** Those are the owner's decisions.
- Log what you do in `HANDOFF.md` as you go (dated entry, exact evidence, one next step), as `CLAUDE.md` asks.

## What to test, in order

1. The 0.15.0 fixes with the dev setup: start-up box, per-save worlds, pause freeze, draw order over the pause menu and map, the three rifles, the effects. (`release/README.md`, playtest 1.)
2. The same as a player: private folders renamed away, fresh Prism instance, the staged jar and zip (`release-staging/0.15.0/`, hashes in `SHA256SUMS`), ZombieBuddy's normal approval prompt. (Playtest 2.)
3. Logs: `Zomboid/console.txt`, Minecraft `logs/latest.log`. Expected: `Minecraft connected`, `Gun visuals: 11 kinds (11 our own, 0 private)` (0.16: seven guns and four magazines), no errors, no missing textures.

Known weak spots to look at hard (code reading, never seen in game):
- the JS-14 and Trapper charging-handle pull (the arm covers it);
- the M1A, JS-14 and Trapper first-person placement and sights (the framing was tuned from offline renders);
- the new effects (flash size, tracer width, blood and debris scale, shell flight), which were drawn blind;
- the per-save world switch (`WorldBootstrap`), especially switching saves without restarting Minecraft;
- the pause freeze while a Minecraft screen is open;
- a player's setup with no private sets: any missing-texture (purple-and-black) anywhere.

## Report back

When you finish a pass, give the owner: what you ran, what you saw, the bug list (ids from `release/BUGS.md`), and which bugs block the release (crash, data loss, Steve unusable, a visible checkerboard) versus which can wait. Attach screenshots and the relevant log lines.
