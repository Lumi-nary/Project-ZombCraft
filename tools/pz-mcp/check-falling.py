"""PZ must not run its own falls while Steve drives: jumping onto a Minecraft block, standing on it, stepping off and
dropping from a three-block pillar must never put the PZ player into a falling / landing / knocked-down animation
(those move the head that Viewpoint's camera and Steve's eye follow). Samples PZ's action state and fall timer ten times a
second. Zombie AI is suspended and Steve protected during the check; fixtures are removed and the character is put back."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
STEADY = {"idle", "movement", "strafe", "run", "sprint", "aim", "maskingleft", "maskingright", "turning", "turning180",
          "turningAim180", "turningIdle180", "turningMovement180"}
FIND_OUTSIDE = """
local p = getPlayer()
local cell = getCell()
local px, py = math.floor(p:getX()), math.floor(p:getY())
local function clear(x, y)
  for dx = -3, 4 do for dy = -2, 2 do
    local sq = cell:getGridSquare(x + dx, y + dy, 0)
    if not sq or not sq:isFree(false) or sq:getObjects():size() > 1 then return false end
  end end
  return true
end
for r = 0, 30 do
  for dx = -r, r do for dy = -r, r do
    if math.max(math.abs(dx), math.abs(dy)) == r and clear(px + dx, py + dy) then
      return (px + dx + 0.5 - p:getX()) .. "," .. (py + dy + 0.5 - p:getY())
    end
  end end
end
return "none"
"""
SAMPLE = ("local p=getPlayer(); return p:getCurrentActionContextStateName()..','..p:getFallTime()..','.."
          "tostring(p:isbFalling())..','..p:getZ()")


def lua1(code):
    r = pz.lua(code)
    assert r["ok"], r
    return r["results"][0] if r["results"] else None


def sample_for(seconds, log):
    t0 = time.time()
    while time.time() - t0 < seconds:
        name, fall, falling, z = lua1(SAMPLE).split(",")
        steve = pz.state().get("steve") or {}
        log.append({"t": round(time.time() - t0, 2), "state": name, "fallTime": round(float(fall), 3),
                    "falling": falling == "true", "pzZ": round(float(z), 3), "steveY": round(float(steve.get("y", 0)), 3)})
        time.sleep(0.1)


def main():
    results = {}
    start = None
    blocks = []
    pause = lua1("return tostring(getCore():getOptionPauseOnFocusloss())")
    try:
        lua1("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); "
             "_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); "
             "table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end")
        pz.cmd("heal")
        pz.cmd("mc effect give @s minecraft:resistance 120 4 true")
        start = pz.state()["player"]
        off = lua1(FIND_OUTSIDE)
        assert off != "none", "no clear 8x5 area within 30 tiles"
        dx, dy = (float(v) for v in off.split(","))
        results["moved"] = pz.cmd(f"tp {dx} {dy}")["result"]
        time.sleep(2.5)
        p = pz.state()["player"]
        cx, cy = int(p["x"] // 1), int(p["y"] // 1)
        # a step two tiles east, and a three-high pillar four tiles east
        blocks = [(cx + 2, 0, cy), (cx + 4, 0, cy), (cx + 4, 1, cy), (cx + 4, 2, cy)]
        for x, y, z in blocks:
            pz.cmd(f"block {x} {y} {z} minecraft:stone")
        time.sleep(1.5)
        pz.send_input(look={"yaw": -90, "pitch": 10, "relative": False}, wait=True)  # Minecraft yaw -90 = east
        log = []
        pz.send_input(["FWD", "JUMP"], 900)  # onto the step
        sample_for(1.2, log)
        sample_for(2.5, log)                  # standing on it
        results["afterStep"] = pz.state()["steve"]
        pz.send_input(["BACK"], 700)          # step back off it
        sample_for(1.5, log)
        pz.cmd(f"mc tp @s {cx + 4.5} 3 {cy + 0.5}")  # on top of the pillar
        sample_for(2.0, log)
        results["onPillar"] = pz.state()["steve"]
        pz.send_input(["BACK"], 700)          # off the pillar: a three-block drop
        sample_for(2.5, log)
        bad = [s for s in log if s["state"] not in STEADY or s["falling"] or s["fallTime"] > 0]
        results["samples"] = len(log)
        results["statesSeen"] = sorted({s["state"] for s in log})
        results["maxSteveY"] = max(s["steveY"] for s in log)
        results["maxPzZ"] = max(s["pzZ"] for s in log)
        results["badSamples"] = bad[:10]
        results["pass"] = not bad and results["maxSteveY"] >= 2.9
        results["log"] = log
    finally:
        for x, y, z in blocks:
            pz.cmd(f"block {x} {y} {z} minecraft:air")
        if start is not None:
            now = pz.state()["player"]
            pz.cmd(f"tp {start['x'] - now['x']} {start['y'] - now['y']}")
        pz.cmd("mc effect clear @s minecraft:resistance")
        lua1("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        lua1("getCore():setOptionPauseOnFocusloss(" + pause + ")")
        (OUT / "falling-results.json").write_text(json.dumps(results, indent=2))
        print(json.dumps({k: v for k, v in results.items() if k != "log"}, indent=2))


if __name__ == "__main__":
    main()
