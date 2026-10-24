-- PzCraft: Steve mines PZ furniture and places it again, through PZ's own moveables code (ISMoveableSpriteProps), so the
-- item is exactly the one PZ's "Pick up" gives and placing follows PZ's own rules. Called from Java (FurnitureBridge).
require "Moveables/ISMoveableSpriteProps"

PzCraftMoveables = {}

-- Steve mined this object. Minecraft's mining stands in for PZ's checks (carry weight, skill, tools) and PZ's chance to
-- break it, so the pick-up is forced; what was inside spills onto the floor first (PZ would refuse a full container). The
-- items end up in the character's inventory, where the inventory mirror hands them to Steve: a multi-tile piece's parts,
-- which PZ leaves on the floor, are moved there too. Returns how many items were made, or -1 when PZ cannot move it.
function PzCraftMoveables.pickUp(character, square, object)
    local props = ISMoveableSpriteProps.fromObject(object)
    if not props or not props.isMoveable or not props.pickUpMoveable then return -1 end
    if props.isMultiSprite then
        local grid = props:getSpriteGridInfo(square, true)
        if not grid then return -1 end
        for _, member in ipairs(grid) do
            if member.object then member.object:dumpContentsInSquare() end
        end
    else
        object:dumpContentsInSquare()
    end
    local inv = character:getInventory()
    local before = inv:getItems():size()
    local items = props:pickUpMoveable(character, square, true, true)
    local made = inv:getItems():size() - before
    if type(items) == "table" then
        for _, item in ipairs(items) do
            local worldItem = item and item:getWorldItem()
            if worldItem then
                worldItem:getSquare():transmitRemoveItemFromSquare(worldItem)
                worldItem:getSquare():removeWorldObject(worldItem)
                item:setWorldItem(nil)
                inv:AddItem(item)
                made = made + 1
            end
        end
    end
    return made
end

-- Steve places a furniture item from his inventory on this square, its front facing `face` ("N", "E", "S" or "W", PZ's
-- own facing names): PZ's own placing checks and code, as its placing cursor does after a rotation. Returns true when the
-- item was placed (it has then left the inventory).
function PzCraftMoveables.place(character, square, item, face)
    if not instanceof(item, "Moveable") or not item:getWorldSprite() then return false end
    local inv = character:getInventory()
    if not inv:contains(item) then return false end
    character:removeFromHands(item) -- PZ looks for the item outside the hands
    local original = item:getWorldSprite()
    local props = ISMoveableSpriteProps.new(original)
    if not props or not props.isMoveable then return false end
    local spriteName = original
    if face and props:hasFaces() then
        spriteName = props:getFaces()[face] or original
    end
    local placing = spriteName == original and props or ISMoveableSpriteProps.new(spriteName)
    if not placing or not placing:canPlaceMoveable(character, square, item) then return false end
    placing:placeMoveable(character, square, original)
    return not inv:contains(item)
end

-- A wall or fence from Minecraft's creative tabs, built the way PZ builds a player's wall (ISWoodenWall:create and
-- buildUtil.setInfo): an IsoThumpable with health, so PZ's zombies thump and break it like any built wall. A low fence
-- can be vaulted. Java has chosen the sprite for the edge, checked the edge is free and worked out the health.
function PzCraftMoveables.placeWall(square, sprite, north, health, fence, low, name)
    local wall = IsoThumpable.new(getCell(), square, sprite, north, {})
    wall:setCanPassThrough(false)
    wall:setCanBarricade(false)
    wall:setThumpDmg(8)
    wall:setIsDismantable(true)
    wall:setBlockAllTheSquare(false)
    wall:setIsThumpable(true)
    wall:setIsHoppable(fence and low)
    wall:setName(name)
    wall:setMaxHealth(health)
    wall:setHealth(health)
    wall:setBreakSound("BreakObject")
    square:AddSpecialObject(wall)
    square:RecalcAllWithNeighbours(true)
    wall:transmitCompleteItemToClients()
    return true
end
