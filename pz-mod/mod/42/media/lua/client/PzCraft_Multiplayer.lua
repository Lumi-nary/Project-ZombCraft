-- PzCraft on a multiplayer client: asks the PZ server which Minecraft server to join and who plays as Steve
-- (PzCraft_Server.lua answers), hands the answers to PzCraft's Java half (PzCraft.Multiplayer), and tells the server
-- when this player starts or stops playing as Steve ("steve", for the others; "falls", for the server's fall count).
if not PzCraft or not PzCraft.Multiplayer then return end
local Multiplayer = PzCraft.Multiplayer
local toldSteve = nil   -- what the server was last told: true, false, or nil (nothing yet)
local ticks = 0

Events.OnGameStart.Add(function()
    if isClient() and getPlayer() then
        toldSteve = nil
        sendClientCommand(getPlayer(), "PzCraft", "hello", {})
    end
end)

Events.OnTick.Add(function()
    if not isClient() then return end
    ticks = ticks + 1
    if ticks % 10 ~= 0 then return end
    local player = getPlayer()
    if not player then return end
    local steve = Multiplayer.drivesSteve()
    if steve == toldSteve then return end
    toldSteve = steve
    sendClientCommand(player, "PzCraft", "falls", { ours = steve })
    sendClientCommand(player, "PzCraft", "steve", { on = steve, mc = Multiplayer.minecraftName() })
end)

Events.OnServerCommand.Add(function(module, command, args)
    if module ~= "PzCraft" or type(args) ~= "table" then return end
    if command == "server" then
        Multiplayer.server(tonumber(args.port) or 25565, args.address or "")
        if type(args.steves) == "table" then
            for _, s in pairs(args.steves) do
                if type(s) == "table" and s.who then Multiplayer.steve(s.who, true, s.mc or "") end
            end
        end
    elseif command == "steve" and args.who then
        Multiplayer.steve(args.who, args.on == true, args.mc or "")
    end
end)
