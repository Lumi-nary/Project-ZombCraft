-- PzCraft: before a new character is made in single player, the player picks a Zomboid survivor or Steve.
-- The screen itself is PzCraft's Java half (ChoiceScreen); this file only joins it to PZ's menu flow, and does nothing
-- when the Java half is not loaded (ZombieBuddy off or the jar not approved).
if not PzCraft or not PzCraft.Start then return end
local Start = PzCraft.Start

local panel, original, joypad

-- Every way into PZ's character creation shows MainScreen.instance.charCreationProfession: ask first instead.
local function hook()
    if original then return true end
    local main = MainScreen and MainScreen.instance
    if not main or not main.charCreationProfession then return false end
    panel = main.charCreationProfession
    original = panel.setVisible
    panel.setVisible = function(self, visible, joypadData)
        if visible and not isClient() and Start.wanted() then
            joypad = joypadData
            Start.ask()
            return
        end
        return original(self, visible, joypadData)
    end
    return true
end

-- Steve: what PZ's "Next" on its last creation screen does, without the screens.
local function startAsSteve()
    local main = MainScreen.instance
    Start.prepareSteve(main.desc)
    if not getWorld():getMap() then
        getWorld():setMap("Muldraugh, KY")
    end
    if main.createWorld then
        createWorld(getWorld():getWorld())
    end
    GameWindow.doRenderEvent(false)
    forceChangeState(LoadingQueueState.new())
end

-- Back: PZ's own Back button of the creation screen knows which screen came before.
local function back()
    local previous = panel.previousScreen
    panel:onOptionMouseDown(panel.backButton, 0, 0)
    if not previous and MainScreen.instance.bottomPanel then
        MainScreen.instance.bottomPanel:setVisible(true)
    end
end

Events.OnFETick.Add(function()
    if not hook() then return end
    local pick = Start.take()
    if pick == "pz" then
        original(panel, true, joypad)
    elseif pick == "mc" then
        startAsSteve()
    elseif pick == "back" then
        back()
    end
end)

Events.OnNewGame.Add(function(player, square)
    Start.newCharacter(player)
end)
