"""PZ zombies vs Minecraft blocks: a closed ring of blocks around Steve keeps freshly spawned zombies out; with one block
pair removed they come in through that gap and only through it. Zombies are sampled twice a second and must never stand
on a block tile. Everything the check creates (blocks, zombies, the resistance effect) is removed afterwards and the
zombies that were already around get their AI back."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
FIND_OUTSIDE = """
local p = getPlayer()
local cell = getCell()
local px, py = math.floor(p:getX()), math.floor(p:getY())
local R = 6
local function clear(x, y)
  for dx = -R, R do for dy = -R, R do
    local sq = cell:getGridSquare(x + dx, y + dy, 0)
    if not sq or not sq:isOutside() or not sq:isFree(false) or sq:getObjects():size() > 1 then return false end
  end end
  return true
end
for r = 0, 60 do
  for dx = -r, r do for dy = -r, r do
    if math.max(math.abs(dx), math.abs(dy)) == r and clear(px + dx, py + dy) then
      return (px + dx + 0.5 - p:getX()) .. "," .. (py + dy + 0.5 - p:getY())
    end
  end end
end
return "none"
"""
POSITIONS = """
local out = {}
for _, z in ipairs(_pzTestZeds or {}) do
  out[#out + 1] = string.format("%.2f,%.2f,%d", z:getX(), z:getY(), z:isDead() and 1 or 0)
end
return table.concat(out, ";")
"""


def lua1(code):
    r = pz.lua(code)
    assert r["ok"], r
    return r["results"][0] if r["results"] else None


def blocks(cells, block):
    for (x, y) in cells:
        for h in (0, 1):
            pz.cmd(f"block {x} {h} {y} {block}")


def watch(seconds, ring, cx, cy, gap):
    """Sample the test zombies; returns (samples on block tiles, samples inside the ring, entered through gap?)."""
    on_block, inside, via_gap, closest = 0, 0, False, 99.0
    t0 = time.time()
    last_order = -99.0
    while time.time() - t0 < seconds:
        if time.time() - last_order > 6:
            # A fresh path order restarts the zombie's pathing, so give it rarely (zombies also chase on their own).
            last_order = time.time()
            lua1("for _, z in ipairs(_pzTestZeds) do if not z:isDead() then z:pathToCharacter(getPlayer()) end end")
        raw = lua1(POSITIONS) or ""
        for item in filter(None, raw.split(";")):
            x, y, dead = item.split(",")
            if dead == "1":
                continue
            tx, ty = int(float(x) // 1), int(float(y) // 1)
            if (tx, ty) in ring:
                on_block += 1
            if abs(tx - cx) <= 1 and abs(ty - cy) <= 1:
                inside += 1
            if gap and (tx, ty) == gap:
                via_gap = True
            closest = min(closest, max(abs(float(x) - (cx + 0.5)), abs(float(y) - (cy + 0.5))))
        time.sleep(0.5)
    return on_block, inside, via_gap, round(closest, 2)


def main():
    results = {}
    start = None
    pause = lua1("return tostring(getCore():getOptionPauseOnFocusloss())")
    ring = set()
    try:
        lua1("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); "
             "_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); "
             "table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end; _pzTestZeds={}")
        pz.cmd("heal")
        start = pz.state()["player"]
        pz.cmd("mc effect give @s minecraft:resistance 300 4 true")  # the zombies may reach Steve through the gap
        lua1("getGameTime():setTimeOfDay(12)")
        off = lua1(FIND_OUTSIDE)
        assert off != "none", "no clear 13x13 outdoor area within 60 tiles"
        dx, dy = (float(v) for v in off.split(","))
        results["moved"] = pz.cmd(f"tp {dx} {dy}")["result"]
        time.sleep(2.5)
        p = pz.state()["player"]
        cx, cy = int(p["x"] // 1), int(p["y"] // 1)
        ring = {(cx + i, cy + j) for i in range(-2, 3) for j in range(-2, 3) if max(abs(i), abs(j)) == 2}
        blocks(ring, "minecraft:stone")
        time.sleep(2.0)
        solid = lua1("local n=0; for _,c in ipairs({" + ",".join(f"{{{x},{y}}}" for x, y in ring) + "}) do "
                     "local sq=getCell():getGridSquare(c[1],c[2],0); if sq and sq:isSolid() then n=n+1 end end; return n")
        results["ringTiles"] = len(ring)
        results["ringTilesSolidInPz"] = int(float(solid))
        before = pz.state()["health"]["zombieAttacksForwarded"]

        # Three zombies, six tiles out on three sides, all told to walk to the player.
        lua1(f"for _, d in ipairs({{{{6,0}},{{-6,1}},{{0,-6}}}}) do local l = addZombiesInOutfit({cx}+d[1], {cy}+d[2], 0, 1, nil, 50); "
             "for i=0,l:size()-1 do table.insert(_pzTestZeds, l:get(i)) end end")
        on_block, inside, _, closest = watch(20, ring, cx, cy, None)
        results["closedRing"] = {"samplesOnBlockTiles": on_block, "samplesInside": inside, "closestChebyshev": closest,
                                 "attacksLanded": pz.state()["health"]["zombieAttacksForwarded"] - before}

        gap = (cx + 2, cy)
        blocks([gap], "minecraft:air")
        ring.discard(gap)
        time.sleep(1.0)
        on_block2, inside2, via_gap, closest2 = watch(25, ring, cx, cy, gap)
        results["withGap"] = {"gap": gap, "samplesOnBlockTiles": on_block2, "samplesInside": inside2,
                              "enteredThroughGap": via_gap, "closestChebyshev": closest2}
        (OUT / "native-collision-ring.png").write_bytes(pz.screenshot(0.6))
        results["pass"] = results["ringTilesSolidInPz"] == results["ringTiles"] \
            and on_block == 0 and inside == 0 and on_block2 == 0 and via_gap
    finally:
        if start is not None:
            # Put the character back where the check found it, so it never ends up stranded in a horde.
            now = pz.state()["player"]
            pz.cmd(f"tp {start['x'] - now['x']} {start['y'] - now['y']}")
        lua1("for _, z in ipairs(_pzTestZeds or {}) do z:removeFromWorld(); z:removeFromSquare() end; _pzTestZeds=nil")
        blocks(ring, "minecraft:air")
        pz.cmd("mc effect clear @s minecraft:resistance")
        lua1("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        lua1("getCore():setOptionPauseOnFocusloss(" + pause + ")")
        (OUT / "collision-results.json").write_text(json.dumps(results, indent=2))
        print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
