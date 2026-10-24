-- PzCraft's half on a PZ server (multiplayer). The host runs this PZ server and a Minecraft server; each player's PZ game
-- and hidden Minecraft join both. What starts in a player's PZ game and must count for everyone is asked for here, with
-- sendClientCommand(player, "PzCraft", command, args), and answered or told to the others with sendServerCommand:
--   "hello"  (a player's game has joined): the Minecraft server's port (PzCraft.Server, Zomboid/Lua/pzcraft-server.properties),
--            and who already plays as Steve
--   "steve"  this player plays as Steve now, or no longer ({on, mc = their Minecraft name}): told to everyone, so each game
--            hides that player's PZ body (Minecraft draws their Steve)
--   "falls"  this player's falls are Minecraft's ({ours}): the server's own fall count is held at nothing, every tick,
--            or the next real floor under a player standing on Minecraft blocks would land them as from the height they rose
-- Every tick, paused or not (an empty server pauses, and the Minecraft server's commands still come), it also runs
-- PzCraft's Java half on the server's game thread (PzCraft.Server.tick: what the Minecraft server is sent, the world
-- around every player, PZ's clock and weather).
if not isServer() then return end

local MODULE = "PzCraft"
local steves = {}       -- username -> Minecraft name, for the players who play as Steve now
local ownFalls = {}     -- usernames whose falls Minecraft counts
local anyOwnFalls = false

local function minecraftPort()
    if PzCraft and PzCraft.Server then return PzCraft.Server.minecraftPort() end
    return 25565
end

local function minecraftAddress()
    if PzCraft and PzCraft.Server then return PzCraft.Server.minecraftAddress() end
    return ""
end

local function online(name)
    local players = getOnlinePlayers()
    for i = 0, (players and players:size() or 0) - 1 do
        if players:get(i):getUsername() == name then return true end
    end
    return false
end

local function onClientCommand(module, command, player, args)
    if module ~= MODULE or not player then return end
    local name = player:getUsername()
    args = args or {}
    if command == "hello" then
        local who = {}
        for user, mc in pairs(steves) do
            if online(user) then table.insert(who, { who = user, mc = mc }) else steves[user] = nil end
        end
        sendServerCommand(player, MODULE, "server", { port = minecraftPort(), address = minecraftAddress(), steves = who })
    elseif command == "steve" then
        local on = args.on == true
        steves[name] = on and (type(args.mc) == "string" and args.mc or "") or nil
        sendServerCommand(MODULE, "steve", { who = name, on = on, mc = steves[name] or "" })
    elseif command == "falls" then
        ownFalls[name] = args.ours == true or nil
        anyOwnFalls = false   -- (PZ's Lua has no next(): look through the table)
        for _ in pairs(ownFalls) do anyOwnFalls = true break end
    end
end

Events.OnClientCommand.Add(onClientCommand)

Events.OnTickEvenPaused.Add(function()
    if PzCraft and PzCraft.Server then PzCraft.Server.tick() end
end)

Events.OnTick.Add(function()
    if not anyOwnFalls then return end
    local players = getOnlinePlayers()
    for i = 0, (players and players:size() or 0) - 1 do
        local player = players:get(i)
        if ownFalls[player:getUsername()] then
            player:setLastFallSpeed(0)
            player:setFallTime(0)
        end
    end
end)

print("[Project-ZombCraft] server half ready: Minecraft server on port " .. tostring(minecraftPort()))
