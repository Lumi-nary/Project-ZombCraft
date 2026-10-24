"""Steve and dropped items drawn natively by PZ: dropped items lit and shadowed, Steve's shadow in first person, Steve's
model in third person. Nearby zombie AI is suspended for the check and restored afterwards; nothing is left behind except
the dropped items (Steve can pick them up again)."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
FIND_OUTSIDE = """
local p = getPlayer()
local cell = getCell()
local px, py = math.floor(p:getX()), math.floor(p:getY())
local function clear(x, y)
  for dx = -2, 2 do for dy = -2, 2 do
    local sq = cell:getGridSquare(x + dx, y + dy, 0)
    if not sq or not sq:isOutside() or not sq:isFree(false) or sq:getObjects():size() > 1 then return false end
  end end
  return true
end
for r = 0, 25 do
  for dx = -r, r do for dy = -r, r do
    if math.max(math.abs(dx), math.abs(dy)) == r and clear(px + dx, py + dy) then
      return (px + dx + 0.5 - p:getX()) .. "," .. (py + dy + 0.5 - p:getY())
    end
  end end
end
return "none"
"""


def shot(name, scale=0.6):
    path = OUT / name
    path.write_bytes(pz.screenshot(scale))
    return str(path)


def main():
    results = {}
    start = None
    pause = pz.lua("return getCore():getOptionPauseOnFocusloss()")["results"][0]
    try:
        pz.lua("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); "
               "_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); "
               "table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end")
        pz.cmd("heal")
        start = pz.state()["player"]
        pz.lua("getGameTime():setTimeOfDay(15.5)")  # a low afternoon sun gives long, visible shadows
        off = pz.lua(FIND_OUTSIDE)["results"][0]
        assert off != "none", "no clear outdoor spot within 25 tiles"
        dx, dy = (float(v) for v in off.split(","))
        results["moved"] = pz.cmd(f"tp {dx} {dy}")["result"]
        time.sleep(2.5)

        # Drop a pickaxe and a dirt block in front of Steve and look down at them.
        pz.send_input(look={"yaw": 180, "pitch": 35, "relative": False}, hotbar=2, wait=True)
        pz.send_input(["DROP"], 120, wait=True)
        time.sleep(0.4)
        pz.send_input(hotbar=3, wait=True)
        pz.send_input(["DROP"], 120, wait=True)
        time.sleep(2.0)
        st = pz.state()
        results["firstPerson"] = {"entities": st["nativeEntities"], "thirdPerson": False}
        results["shotFirstPerson"] = shot("native-items.png")

        # Third person: Steve's own model, lit by PZ, casting his shadow.
        results["third"] = pz.cmd("third on")["result"]
        time.sleep(1.5)
        pz.send_input(look={"yaw": 150, "pitch": 20, "relative": False}, wait=True)
        time.sleep(1.0)
        st = pz.state()
        results["thirdPerson"] = {"entities": st["nativeEntities"]}
        results["shotThirdPerson"] = shot("native-steve-third.png")
        pz.send_input(look={"yaw": 60, "pitch": 30, "relative": False}, wait=True)
        time.sleep(1.0)
        results["shotThirdPerson2"] = shot("native-steve-third2.png")
        assert st["nativeEntities"]["drawnBatches"] > 0, "nothing drawn in the g-buffer in third person"
    finally:
        if start is not None:
            # Put the character back where the check found it, so it never ends up stranded in a horde.
            now = pz.state()["player"]
            pz.cmd(f"tp {start['x'] - now['x']} {start['y'] - now['y']}")
        pz.cmd("third off")
        pz.lua("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        pz.lua("getCore():setOptionPauseOnFocusloss(" + str(pause).lower() + ")")
        (OUT / "entities-results.json").write_text(json.dumps(results, indent=2))
        print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
