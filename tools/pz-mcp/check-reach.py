"""Zombies cannot hurt Steve on top of a two-block pillar but do on the ground next to it. PZ refuses some attacks by
height itself; forwarded ones must also pass Minecraft's melee reach and line of sight (ActorProxies). One zombie is spawned and
removed again, the pillar is removed, other zombies are held still, Steve is healed and the character put back."""
import json
import re
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
FIND_OUTSIDE = """
local p = getPlayer()
local cell = getCell()
local px, py = math.floor(p:getX()), math.floor(p:getY())
local function clear(x, y)
  for dx = -1, 6 do for dy = -2, 2 do
    local sq = cell:getGridSquare(x + dx, y + dy, 0)
    if not sq or not sq:isFree(false) or sq:getObjects():size() > 1 then return false end
  end end
  return true
end
for r = 0, 40 do
  for dx = -r, r do for dy = -r, r do
    if math.max(math.abs(dx), math.abs(dy)) == r and clear(px + dx, py + dy) then
      return (px + dx + 0.5 - p:getX()) .. "," .. (py + dy + 0.5 - p:getY())
    end
  end end
end
return "none"
"""


def lua1(code):
    r = pz.lua(code)
    assert r["ok"], r
    return r["results"][0] if r["results"] else None


def out_of_reach_count():
    line = pz.logs("minecraft", r"attacks out of reach=", 1)
    m = re.search(r"attacks out of reach=(\d+)", line)
    return int(m.group(1)) if m else None


def watch(seconds, until_damage=False):
    h0 = pz.state()["health"]
    t0, lowest = time.time(), h0["steveHealth"]
    while time.time() - t0 < seconds:
        lua1("for _, z in ipairs(_pzTestZeds) do if not z:isDead() and z:getActionStateName()~='attack' then z:pathToCharacter(getPlayer()) end end")
        time.sleep(1.0)
        lowest = min(lowest, pz.state()["health"]["steveHealth"])
        if until_damage and lowest < h0['steveHealth']:break
    h1 = pz.state()["health"]
    return {"attacksForwarded": h1["zombieAttacksForwarded"] - h0["zombieAttacksForwarded"],
            "healthBefore": h0["steveHealth"], "lowestHealth": lowest}


def main():
    results = {}
    start = None
    blocks = []
    pause = lua1("return tostring(getCore():getOptionPauseOnFocusloss())")
    try:
        lua1("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); "
             "_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); "
             "table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end; _pzTestZeds={}")
        pz.cmd("heal")
        start = pz.state()["player"]
        off = lua1(FIND_OUTSIDE)
        assert off != "none", "no clear 8x5 area within 40 tiles"
        dx, dy = (float(v) for v in off.split(","))
        results["moved"] = pz.cmd(f"tp {dx} {dy}")["result"]
        time.sleep(2.5)
        p = pz.state()["player"]
        cx, cy = int(p["x"] // 1), int(p["y"] // 1)
        blocks = [(cx + 3, 0, cy), (cx + 3, 1, cy)]
        for x, y, z in blocks:
            pz.cmd(f"block {x} {y} {z} minecraft:stone")
        time.sleep(1.0)
        pz.cmd(f"mc tp @s {cx + 3.5} 2 {cy + 0.5}")
        time.sleep(1.5)
        results["onPillar"] = pz.state()["steve"]
        before = out_of_reach_count()
        lua1(f"local l = addZombiesInOutfit({cx + 4}, {cy}, 0, 1, nil, 50); for i=0,l:size()-1 do table.insert(_pzTestZeds, l:get(i)) end")
        results["pillar"] = watch(15)
        time.sleep(10.5)  # the Minecraft stats line with the out-of-reach count is logged every 10 s
        after = out_of_reach_count()
        results["pillar"]["outOfReach"] = None if before is None or after is None else after - before

        pz.cmd("heal")
        for x,y,z in blocks:pz.cmd(f"block {x} {y} {z} minecraft:air")
        time.sleep(1)
        pz.cmd(f"mc tp @s {cx + 5.5} 0.02 {cy + 0.5}")  # same side as the zombie: the pillar must not block this check
        lua1(f"for _,z in ipairs(_pzTestZeds) do z:setForceX({cx+4.5});z:setForceY({cy+.5});z:setZ(0);z:setTarget(getPlayer()) end")
        time.sleep(1.0)
        results["ground"] = watch(25, until_damage=True)
        results['zombieAfter']=lua1("local z=_pzTestZeds[1];return z:getX()..','..z:getY()..','..z:getZ()..','..tostring(z:isUseless())..','..z:getActionStateName()..','..tostring(z:isNoTeeth())")
        pz.cmd("heal")
        r = results
        # What a player feels: no damage on the pillar, damage on the ground. PZ itself may already refuse the attack
        # (attacksForwarded 0, its height check); otherwise Minecraft's reach turns it into a miss (outOfReach > 0).
        r["pass"] = (r["pillar"]["lowestHealth"] >= r["pillar"]["healthBefore"]
                     and r["ground"]["lowestHealth"] < r["ground"]["healthBefore"])
    finally:
        lua1("for _, z in ipairs(_pzTestZeds or {}) do z:removeFromWorld(); z:removeFromSquare() end; _pzTestZeds=nil")
        for x, y, z in blocks:
            pz.cmd(f"block {x} {y} {z} minecraft:air")
        if start is not None:
            now = pz.state()["player"]
            pz.cmd(f"tp {start['x'] - now['x']} {start['y'] - now['y']}")
        pz.cmd("heal")
        lua1("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        time.sleep(1)
        lua1("UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(" + pause + ")")
        (OUT / "reach-results.json").write_text(json.dumps(results, indent=2))
        print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
