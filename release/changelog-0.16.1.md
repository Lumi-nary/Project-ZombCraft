# Project ZombCraft 0.16.1

## Fixed

- **Project Zomboid no longer closes at startup.** Its start screen drew before the game had an OpenGL context on that thread, which aborted the whole game. It now draws where the game swaps frames, on the thread that owns the context, and waits until the context is ready.
- **Project Zomboid no longer starts Minecraft by itself**, so the automatic start that crashed for some players is gone. Minecraft is started by **Play** on the start screen, by your launcher, or by hand.
- **No command-prompt window** appears when Minecraft is started by the development scripts.
- **No more errors on every frame of a loaded world.** Project Viewpoint's camera called a part of Project ZombCraft it was not allowed to reach, and the world's drawing threw an error each frame (very likely the "crashes upon loading worlds" of 0.16.0, together with the startup fix above).
- **The M1A Rifle, JS-14 Rifle and Trapper Carbine are racked with the right hand**, as their handle is on the right: the right hand leaves the grip, pulls the handle back and lets it go, and the left hand stays on the forend (also at the end of an empty reload). Before, the left hand reached over the top, and the end of the arm filled the middle of the screen.
- **No arm shows the flat end of its block any more:** the M16's left arm at rest, when drawn and on the charging handle, and the M9's, M1911's and B-F's left hand racking the slide, now reach from below and behind, as a real arm does.
- **Multiplayer:** the server no longer throws a Lua error when a player joins, which could keep that player's Minecraft from being told where to join. In game, the box on a server now says multiplayer is a test (it said "single player only"), then shows the usual hints, such as Minecraft still joining.
- Project Zomboid's log shows the installed version of the mod (it always said 0.13.0).
