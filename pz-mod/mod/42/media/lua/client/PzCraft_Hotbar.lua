-- PzCraft: PZ's belt hotbar (ISHotbar, the numbered slot boxes at the bottom of the screen) is off while Steve plays.
-- Minecraft owns Steve's inventory and its hotbar sits where PZ's does. Hiding the bar from Java (HudBridge) did not
-- hold: PZ's own update() turns it back on every frame ("controller unplugged" when the moodles are visible), so it
-- still drew over Minecraft's slots. Its keys are already muted for Steve (Patch_MuteBinding). PZ's bookkeeping still
-- runs (attached items, refresh); only the drawing and the item tooltip are off. Shown again when Steve hands back.
if not PzCraft or not PzCraft.Multiplayer then return end
require "Hotbar/ISHotbar"

local Multiplayer = PzCraft.Multiplayer

local function steve(bar)
    return bar.playerNum == 0 and Multiplayer.drivesSteve()
end

local update = ISHotbar.update
function ISHotbar:update()
    local hide = steve(self)
    if not hide and self.pzcraftHidden then
        -- before PZ's update, so it can hide the bar again for its own reasons (driving a car)
        self.pzcraftHidden = nil
        self:setVisible(true)
    end
    local result = update(self)
    if hide then
        if self:isVisible() then self:setVisible(false) end
        if self.toolRender then self.toolRender:setVisible(false) end
        self.pzcraftHidden = true
    end
    return result
end

local render = ISHotbar.render
function ISHotbar:render()
    if steve(self) then return end
    return render(self)
end
