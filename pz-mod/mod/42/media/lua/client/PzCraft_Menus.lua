-- PzCraft: PZ's own right-click menus for Steve. G (PzMenus.java) frees the cursor where the crosshair was and opens the
-- menu PZ opens for a right-click there (Viewpoint picks what is under the cursor in 3D); Shift+G opens the menu of the
-- PZ item in Steve's hand. Steve walks with Minecraft, so while he plays PZ's body never walks off on its own: a walk PZ
-- queues before an action ends at once when the target is within reach (else "Too far away" and nothing happens), and the
-- entries that only move PZ's body (walk to, sit, sleep, climb) or put things in its hands are left out of the menus.
if not PzCraft or not PzCraft.Multiplayer then return end
require "ISUI/ISContextMenu"
require "ISUI/ISWorldObjectContextMenu"
require "ISUI/ISInventoryPaneContextMenu"
require "TimedActions/WalkToTimedAction"
require "TimedActions/WalkToTimedActionF"
require "Vehicles/TimedActions/ISPathFindAction"

local Multiplayer = PzCraft.Multiplayer
local REACH = 3.0   -- tiles from Steve to where PZ would have walked (the target is a tile further: Minecraft's reach)
local CAR_REACH = 4.0

PzCraftMenus = {}

local function steve(character)
    return character ~= nil and character == getPlayer() and Multiplayer.drivesSteve()
end

-- ---- opening and closing (called from Java) ----

function PzCraftMenus.isOpen()
    local context = getPlayerContextMenu(0)
    return context ~= nil and context:isAnyVisible() and not context:isEmpty()
end

function PzCraftMenus.close()
    local context = getPlayerContextMenu(0)
    if context then context:closeAll() end
end

-- What a right-click on this object would open (PZ's own handler, with Viewpoint's 3D picks for doors, windows and trees).
function PzCraftMenus.openWorld(object, x, y)
    if not object or not ISObjectClickHandler then return false end
    ISObjectClickHandler.doRClick(object, x, y)
    return PzCraftMenus.isOpen()
end

-- What a right-click on this item in the inventory would open.
function PzCraftMenus.openItem(item, x, y)
    local player = getPlayer()
    if not item or not player or not player:getInventory():containsRecursive(item) then return false end
    ISInventoryPaneContextMenu.createMenu(0, item:getContainer() == player:getInventory(), { item }, x, y)
    return PzCraftMenus.isOpen()
end

-- Shift+right-click on a car in Minecraft: what PZ's own radial menu outside a car offers (open or close the hood and trunk,
-- lock, mechanics, siphon, ...) as a right-click menu at x, y. The radial is filled into a stand-in that collects its slices.
function PzCraftMenus.openVehicle(vehicle, x, y)
    local player = getPlayer()
    if not vehicle or not player or player:getVehicle() then return false end
    local slices = {}
    local proxy = { sounds = {} } -- the native radial records its close sound here
    function proxy:isReallyVisible() return false end
    function proxy:isEmpty() return #slices == 0 end
    function proxy:addSlice(text, texture, func, ...)
        if text and func then table.insert(slices, { text = text, func = func, args = { ... } }) end
    end
    for _, name in ipairs({ "clear", "setX", "setY", "addToUIManager", "setHideWhenButtonReleased", "undisplay", "setJoypadFocus" }) do
        proxy[name] = function() end
    end
    function proxy:getWidth() return 0 end
    function proxy:getHeight() return 0 end
    local radial, target = getPlayerRadialMenu, ISVehicleMenu.getVehicleToInteractWith
    getPlayerRadialMenu = function() return proxy end
    ISVehicleMenu.getVehicleToInteractWith = function() return vehicle end
    local ok, err = pcall(ISVehicleMenu.showRadialMenuOutside, player)
    getPlayerRadialMenu, ISVehicleMenu.getVehicleToInteractWith = radial, target
    if not ok then print("PzCraft: the car menu failed: " .. tostring(err)) end
    if #slices == 0 then return false end
    local context = ISContextMenu.get(0, x, y)
    for _, s in ipairs(slices) do
        local a = s.args
        context:addOption(s.text, a[1], s.func, a[2], a[3], a[4], a[5], a[6])
    end
    return PzCraftMenus.isOpen()
end

-- ---- entries that would move PZ's body or hands on their own ----

local function prune(context, blocked)
    if not context or not context.options then return end
    for i = #context.options, 1, -1 do
        local option = context.options[i]
        if option and blocked[option.onSelect] then
            context:removeOptionByName(option.name)
        elseif option and option.subOption then
            prune(context:getSubMenu(option.subOption), blocked)
        end
    end
end

local function worldBlocked()
    local W = ISWorldObjectContextMenu
    local list = { W.onWalkTo, W.onSitOnGround, W.onSleep, W.onRest, W.onClimbOverFence, W.onClimbThroughWindow,
        W.onClimbSheetRope, W.onGetOnBed }
    local set = {}
    for _, f in pairs(list) do set[f] = true end
    return set
end

local function itemBlocked()
    local I = ISInventoryPaneContextMenu
    local set = {}
    for _, f in pairs({ I.OnPrimaryWeapon, I.OnSecondWeapon, I.OnTwoHandsEquip }) do set[f] = true end
    return set
end

Events.OnFillWorldObjectContextMenu.Add(function(player, context, worldobjects, test)
    if test or not steve(getSpecificPlayer(player)) then return end
    prune(context, worldBlocked())
end)

Events.OnFillInventoryObjectContextMenu.Add(function(player, context, items)
    if not steve(getSpecificPlayer(player)) then return end
    prune(context, itemBlocked())
end)

-- ---- walks PZ queues before an action ----

local function near(character, x, y, z, reach)
    if not x or not y then return true end   -- nowhere to measure: do it where Steve stands
    if z and math.abs(z - character:getZ()) > 1 then return false end
    local dx, dy = x - character:getX(), y - character:getY()
    return dx * dx + dy * dy <= reach * reach
end

local function tooFar(character)
    HaloTextHelper.addBadText(character, "Too far away")
end

-- Steve stands where Minecraft put him: a walk ends at once when he is close enough, and fails when he is not.
local function settle(action, x, y, z, reach)
    action.pzcraftSteve = true
    action.pzcraftNear = near(action.character, x, y, z, reach or REACH)
end

local function settled(action)
    if action.pzcraftNear then
        action:forceComplete()
    else
        tooFar(action.character)
        action:forceStop()
    end
end

local walkStart, walkUpdate = ISWalkToTimedAction.start, ISWalkToTimedAction.update
function ISWalkToTimedAction:start()
    if not steve(self.character) then return walkStart(self) end
    local l = self.location
    if l then settle(self, l:getX() + 0.5, l:getY() + 0.5, l:getZ()) else settle(self) end
end
function ISWalkToTimedAction:update()
    if self.pzcraftSteve then return settled(self) end
    return walkUpdate(self)
end

local walkFStart, walkFUpdate = ISWalkToTimedActionF.start, ISWalkToTimedActionF.update
function ISWalkToTimedActionF:start()
    if not steve(self.character) then return walkFStart(self) end
    local l = self.location
    if l then settle(self, l:x(), l:y(), l:z()) else settle(self) end
end
function ISWalkToTimedActionF:update()
    if self.pzcraftSteve then return settled(self) end
    return walkFUpdate(self)
end

-- Where a path-find action was headed, as x, y, z and the reach that counts there.
local function goalOf(action)
    local g = action.goal
    if type(g) ~= "table" then return nil end
    local kind, character = g[1], action.character
    if kind == "LocationF" then return g[2], g[3], g[4] end
    if kind == "Nearest" or kind == "NearestPreferred" then
        local best, bx, by, bz
        for _, list in ipairs({ g[2], kind == "NearestPreferred" and g[3] or nil }) do
            for i = 1, #list - 2, 3 do
                local dx, dy = list[i] - character:getX(), list[i + 1] - character:getY()
                local d = dx * dx + dy * dy
                if not best or d < best then best, bx, by, bz = d, list[i], list[i + 1], list[i + 2] end
            end
        end
        return bx, by, bz
    end
    if kind == "SitOnFurniture" or kind == "GrabCorpse" then
        local object = g[2]
        if object and object:getSquare() then return object:getX() + 0.5, object:getY() + 0.5, object:getZ() end
        return nil
    end
    if kind == "VehicleArea" and g[2] then
        local c = g[2]:getAreaCenter(g[3])
        if c then return c:getX(), c:getY(), g[2]:getZ() end
    end
    if (kind == "VehicleArea" or kind == "VehicleAdjacent" or kind == "VehicleSeat") and g[2] then
        return g[2]:getX(), g[2]:getY(), g[2]:getZ(), CAR_REACH
    end
    return nil
end

local pathStart, pathUpdate = ISPathFindAction.start, ISPathFindAction.update
function ISPathFindAction:start()
    if not steve(self.character) then return pathStart(self) end
    local x, y, z, reach = goalOf(self)
    settle(self, x, y, z, reach)
end
function ISPathFindAction:update()
    if self.pzcraftSteve then
        if not self.pzcraftNear and self.onFailFunc then   -- as when PZ finds no path: its own fallback runs
            local args = self.onFailArgs
            self.onFailFunc(args[1], args[2], args[3], args[4])
            if self.runActionsAfterFailing then return self:forceComplete() end
        end
        return settled(self)
    end
    return pathUpdate(self)
end
