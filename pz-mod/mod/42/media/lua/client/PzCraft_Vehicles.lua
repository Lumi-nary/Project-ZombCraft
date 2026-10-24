-- PzCraft: PZ's cars for Steve, the Lua half (called from Java: CarBridge, SpawnBridge, PzMenus).
require "Vehicles/ISUI/ISVehicleMenu"

PzCraftVehicles = {}

-- How much of the screen's height PZ's dashboard covers at the bottom (0 when it is not shown): Minecraft lifts its hotbar
-- and bars above it.
function PzCraftVehicles.dashboardFraction()
    local dashboard = getPlayerVehicleDashboard and getPlayerVehicleDashboard(0)
    if not dashboard or not dashboard:getIsVisible() then return 0 end
    local screen = getPlayerScreenHeight(0)
    if not screen or screen <= 0 then return 0 end
    return dashboard:getHeight() / screen
end

-- Minecraft's creative vehicle item: PZ's script `script` on this square, facing `dir` ("N", "E", "S", "W"), repaired, with a
-- full tank and a charged battery, and its key in the player's inventory (the mirror hands it to Steve). Returns the
-- script's name, or why nothing was made.
function PzCraftVehicles.spawn(script, x, y, z, dir)
    local square = getCell():getGridSquare(x, y, z)
    if not square then return "no square there" end
    if not getScriptManager():getVehicle(script) then return "PZ has no vehicle " .. tostring(script) end
    local car = addVehicleDebug(script, IsoDirections[dir] or IsoDirections.N, nil, square)
    if not car then return "PZ could not place it" end
    pcall(function() car:repair() end)
    pcall(function()
        local tank = car:getPartById("GasTank")
        if tank and tank:getContainerCapacity() > 0 then tank:setContainerContentAmount(tank:getContainerCapacity()) end
    end)
    pcall(function()
        local battery = car:getPartById("Battery")
        local item = battery and battery:getInventoryItem()
        if item then item:setCurrentUsesFloat(1) end
    end)
    local player = getPlayer()
    if player then pcall(function() player:getInventory():AddItem(car:createVehicleKey()) end) end
    return car:getScript():getName()
end
