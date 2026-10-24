# Multiplayer test (step M1: moving together)

Draft, 2026-10-15. Multiplayer is being built in steps, and this is the first one players can try. Both servers have
been checked on the VPS without players: they start with the mods and pair, and clock and weather go both ways.
Nobody has joined one yet, so expect rough edges. Please send both servers' logs and every player's logs.

## How it works

The host runs two servers on one PC: a **Project Zomboid server** and a **Minecraft server**. Both have Project
ZombCraft. They talk to each other directly on that PC. Each player plays as in single player, with Project Zomboid
and their own Minecraft beside it. When they join the Project Zomboid server, their Minecraft joins the host's
Minecraft server by itself.

## What works in this step

- Everyone moves as Steve together, sees the others' Steve, and stands on Project Zomboid's floors, stairs and roofs on
  every PC. The Minecraft server knows Project Zomboid's walls and floors around every player, so it neither kicks
  anyone for "flying" nor makes them fall.
- Minecraft blocks that anyone places are everyone's, because they belong to the Minecraft server.
- Project Zomboid hides the Project Zomboid body of a player who plays as Steve, so only their Steve shows.
- Falls are Minecraft's: the Project Zomboid server ignores the height a Steve climbed to.
- Time and weather are the Project Zomboid server's. Minecraft's `/time set`, `/weather` and a night slept through
  change them for everyone.
- Nothing pauses: Project Zomboid's Esc menu no longer pauses Minecraft in multiplayer.

## Not yet (later steps)

- **M2:** fighting zombies as Steve (zombies don't take Steve's hits yet), and zombie damage through Minecraft's health.
- **M3:** felling trees, digging ground and explosions changing the Project Zomboid world for everyone.
- **M4:** Steve's items in Project Zomboid's pockets, and Project Zomboid containers opened as Minecraft chests. The
  inventory mirror and container screens are off in multiplayer until then.
- **M5:** the Steve or Zomboid survivor choice in multiplayer, and hosting from Project Zomboid's Host button.

## The host

1. **Project Zomboid server** (dedicated, Build 42.21):
   - ZombieBuddy runs in the server's Java, as ZombieBuddy's own instructions for servers say. At start it asks on the
     server console, "Allow this Java mod to load? [y/n]". Answer `y` for Project-ZombCraft.
   - Its mod list holds ZombieBuddy, Project Viewpoint and Project-ZombCraft (`Mods=` in the server ini; it was `PzCraft` before the rename).
   - Run it with Steam, as the players' games run (the usual way: Steam players can't join a `-nosteam` server, and their
     game only says "The Server Failed to Respond"). A server started with `-nosteam` ignores Workshop subscriptions and
     loads its mods only from `<cachedir>/mods`, and its players must start PZ with `-nosteam` too.
   - If the server stops at start with a `NullPointerException` in ZombieBuddy's `workshopItemIdFromInfPath`, its
     `-cachedir` sits too near the top of a drive (seen with `E:\PzCraftMP\pz-cache`): use a deeper folder, such as
     `C:\Users\<you>\.pzcraft\mp\pz-server-cache`.
   - In the server's settings (`Zomboid/Server/<name>.ini`), set `AntiCheatSpeed=4`. That turns off the speed check,
     which otherwise kicks a Steve who runs or glides fast. Leave `AntiCheatNoClip=4`, the default.
   - Optional: `Zomboid/Lua/pzcraft-server.properties` with `minecraft.port=25565` (the Minecraft server's port). Add
     `minecraft.address=<name>` only when players must reach Minecraft under another address than the one they use
     for Project Zomboid.
   - Its log says `[Project-ZombCraft] Project-ZombCraft loaded in a PZ server process` and `server half ready`.
2. **Minecraft server** (Fabric, Minecraft 26.3) on the same PC:
   - Its mods are Fabric API, GeckoLib and Project ZombCraft (the same jar as the players').
   - In `server.properties`, before the world is made, set the world to an empty one, as in single player:
     ```
     level-type=minecraft:flat
     generator-settings={"layers":[{"block":"minecraft:air","height":1}],"biome":"minecraft:plains"}
     ```
     If the world isn't empty, the server log says so at start. Set `server-port` to the same port as
     `minecraft.port`.
3. Start both, in either order. Within a second, the Minecraft log says `PZ server connected` and the Project Zomboid
   log says `Minecraft server connected`.
4. Open these ports on the host: Project Zomboid's (UDP 16261 and 16262 by default) and Minecraft's (TCP 25565).

## The players

1. Start Project Zomboid and your Minecraft as in single player: Project Zomboid's main menu shows when Minecraft is
   ready.
2. Join the Project Zomboid server. Your Minecraft leaves its own world and joins the host's Minecraft server by
   itself, at the address you joined Project Zomboid with. Its log says
   `joining the multiplayer Minecraft server <address>:<port>`.
3. Press O for first person as usual. Steve is put where your Project Zomboid character stands; the Minecraft server's
   log says `<name> put at their PZ character`.
