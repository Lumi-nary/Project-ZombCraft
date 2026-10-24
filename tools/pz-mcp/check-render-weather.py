"""Checks for the 0.10 fixes, one stage at a time (python check-render-weather.py STAGE ...):

  moving     falling sand and primed TNT are drawn natively; a piston's head and the block it pushes stay visible while
             moving (game slowed with /tick rate so the motion can be photographed)
  boat       a boat can be put down on PZ ground (aiming at a PZ floor)
  particles  particles are hidden behind a Minecraft wall and show in front of it
  eye        PZ's first-person camera sits at Steve's eye height (standing / sneaking)
  weather    /weather variations reach PZ (precipitation, snow, thunder, fog) and PZ's weather comes back to Minecraft

Fixtures are put in a clear outdoor spot near the character and removed afterwards; nearby zombie AI is suspended,
Steve is protected and the character is put back where it was. Results: render-weather-STAGE.json and *.png here.
"""
import json
import re
import sys
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
FIND_OUTSIDE = """
local p = getPlayer()
local cell = getCell()
local px, py = math.floor(p:getX()), math.floor(p:getY())
local function clear(x, y)
  for dx = -3, 7 do for dy = -4, 4 do
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


def lua1(code):
    r = pz.lua(code)
    assert r["ok"], r
    return r["results"][0] if r["results"] else None


def mc(command, wait=0.3):
    pz.cmd("mc " + command)
    time.sleep(wait)


def shot(name, scale=0.6):
    path = OUT / name
    path.write_bytes(pz.screenshot(scale))
    return str(path)


def entities():
    return pz.state().get("nativeEntities") or {}


def said(marker, since_lines):
    """True if Minecraft's log has '<marker>' (a /say from an execute-if test) after line since_lines."""
    text = Path(pz.LOGS["minecraft"]).read_text(encoding="utf-8", errors="replace").splitlines()[since_lines:]
    return any(marker in line for line in text)


def log_lines():
    return len(Path(pz.LOGS["minecraft"]).read_text(encoding="utf-8", errors="replace").splitlines())


class Fixture:
    """Moves to a clear spot facing east, protects Steve, and undoes everything on exit."""

    def __enter__(self):
        self.pause = lua1("return tostring(getCore():getOptionPauseOnFocusloss())")
        lua1("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); "
             "_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); "
             "table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end")
        self.start = pz.state()["player"]
        pz.cmd("heal")
        mc("effect give @s minecraft:resistance 600 4 true")
        off = lua1(FIND_OUTSIDE)
        assert off != "none", "no clear 11x9 outdoor area within 60 tiles"
        dx, dy = (float(v) for v in off.split(","))
        pz.cmd(f"tp {dx} {dy}")
        time.sleep(2.5)
        p = pz.state()["player"]
        self.x, self.y = int(p["x"] // 1), int(p["y"] // 1)   # PZ tile = Minecraft x / z
        self.blocks = []
        pz.send_input(look={"yaw": -90, "pitch": 15, "relative": False}, wait=True)  # Minecraft yaw -90 = east (+x)
        return self

    def block(self, x, y, z, block):
        """A Minecraft block (states allowed, e.g. minecraft:piston[facing=east]) at Minecraft x, y, z."""
        self.blocks.append((x, y, z))
        mc(f"setblock {x} {y} {z} {block}", 0.1)

    def __exit__(self, *exc):
        mc("tick rate 20")
        mc(f"kill @e[type=minecraft:tnt,distance=..30]")
        mc(f"kill @e[type=minecraft:falling_block,distance=..30]")
        mc(f"kill @e[type=minecraft:oak_boat,distance=..30]")
        for x, y, z in reversed(self.blocks):
            mc(f"setblock {x} {y} {z} minecraft:air", 0.1)
        time.sleep(0.5)
        now = pz.state()["player"]
        pz.cmd(f"tp {self.start['x'] - now['x']} {self.start['y'] - now['y']}")
        mc("effect clear @s minecraft:resistance")
        pz.cmd("heal")
        lua1("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        lua1("getCore():setOptionPauseOnFocusloss(" + self.pause + ")")
        return False


def stage_moving(r):
    def until_more(base, extra, seconds):
        """Poll the native triangle count until it exceeds base + extra (or time runs out); returns the delta."""
        t0, best = time.time(), 0
        while time.time() - t0 < seconds:
            best = entities().get("triangles", 0) - base
            if best >= extra:
                break
            time.sleep(0.1)
        return best

    with Fixture() as f:
        x, z = f.x, f.y
        pz.send_input(look={"yaw": -90, "pitch": 5, "relative": False}, wait=True)
        time.sleep(0.5)
        base = entities().get("triangles", 0)
        mc("tick rate 2")  # ten times slower: motion lasts long enough to photograph
        # falling sand, three blocks up, four blocks ahead (east)
        mc(f"summon minecraft:falling_block {x + 4.5} 3 {z + 0.5} {{BlockState:{{Name:\"minecraft:sand\"}},Time:1}}", 0.2)
        r["fallingTriangles"] = until_more(base, 12, 3)
        r["shotFalling"] = shot("render-falling.png")
        time.sleep(1.0)
        mc("kill @e[type=minecraft:falling_block,distance=..30]")
        mc(f"setblock {x + 4} 0 {z} minecraft:air")  # in case it already landed
        time.sleep(1.0)
        base = entities().get("triangles", 0)
        # primed TNT with a long fuse, killed long before it could go off
        mc(f"summon minecraft:tnt {x + 3.5} 0 {z - 1.5} {{fuse:400}}", 0.2)
        r["tntTriangles"] = until_more(base, 12, 3)
        r["shotTnt"] = shot("render-tnt.png")
        mc("kill @e[type=minecraft:tnt,distance=..30]")
        # piston pushing a plank east: piston at x+3, plank at x+4, powered by a redstone block at x+2
        f.block(x + 3, 0, z + 2, "minecraft:piston[facing=east]")
        f.block(x + 4, 0, z + 2, "minecraft:oak_planks")
        pz.send_input(look={"yaw": -60, "pitch": 15, "relative": False}, wait=True)
        mc("tick rate 1", 1.5)
        time.sleep(1.5)
        base = entities().get("triangles", 0)
        f.block(x + 2, 0, z + 2, "minecraft:redstone_block")
        r["pistonExtendingTriangles"] = until_more(base, 12, 4)
        r["shotPistonExtending"] = shot("render-piston-extending.png")
        time.sleep(3.0)
        r["shotPistonExtended"] = shot("render-piston-extended.png")
        base = entities().get("triangles", 0)
        mc(f"setblock {x + 2} 0 {z + 2} minecraft:air", 0.0)
        r["pistonRetractingTriangles"] = until_more(base, 12, 4)
        r["shotPistonRetracting"] = shot("render-piston-retracting.png")
        time.sleep(3.0)
        mc("tick rate 20")
        f.blocks.append((x + 5, 0, z + 2))   # the pushed plank, if it stayed pushed
        r["pass"] = r["fallingTriangles"] >= 12 and r["tntTriangles"] >= 12 and r["pistonExtendingTriangles"] >= 12


def stage_boat(r):
    with Fixture() as f:
        mc("give @s minecraft:oak_boat 1")
        time.sleep(3.2)  # the held-item / hotbar log line comes every 3 s
        bar = pz.logs("minecraft", r"crosshair:", 1)
        slots = re.search(r"hotbar \[([^\]]*)\]", bar).group(1).split(",")
        slot = next(i for i, s in enumerate(slots, start=1) if "boat" in s)
        pz.send_input(hotbar=slot, wait=True)
        pz.send_input(look={"yaw": -90, "pitch": 50, "relative": False}, wait=True)  # at the ground two blocks ahead
        mark = log_lines()
        pz.send_input(["USE"], 150, wait=True)
        time.sleep(1.0)
        mc("execute if entity @e[type=minecraft:oak_boat,distance=..6] run say pzcraft-boat-placed", 1.0)
        r["boatPlaced"] = said("pzcraft-boat-placed", mark)
        r["shotBoat"] = shot("render-boat.png")
        r["pass"] = r["boatPlaced"]


def stage_particles(r):
    with Fixture() as f:
        x, z = f.x, f.y
        # a 3-high, 5-wide stone wall three tiles east of Steve
        for dz in range(-2, 3):
            for y in range(0, 3):
                f.block(x + 3, y, z + dz, "minecraft:stone")
        pz.send_input(look={"yaw": -90, "pitch": 0, "relative": False}, wait=True)
        time.sleep(1.0)
        r["shotWallOnly"] = shot("render-particles-none.png")
        # flames and smoke behind the wall: must not show
        for _ in range(6):
            mc(f"particle minecraft:flame {x + 5.5} 1.2 {z + 0.5} 0.3 0.4 0.3 0.01 40 force", 0.15)
            mc(f"particle minecraft:campfire_cosy_smoke {x + 5.5} 1.5 {z + 0.5} 0.2 0.2 0.2 0.01 4 force", 0.15)
        r["particlesBehind"] = entities()
        r["shotBehind"] = shot("render-particles-behind.png")
        time.sleep(2.5)
        # in front of the wall: must show
        for _ in range(6):
            mc(f"particle minecraft:flame {x + 1.8} 1.2 {z + 0.5} 0.2 0.3 0.2 0.01 40 force", 0.15)
            mc(f"particle minecraft:campfire_cosy_smoke {x + 1.8} 1.2 {z + 0.5} 0.1 0.1 0.1 0.01 3 force", 0.15)
        r["particlesFront"] = entities()
        r["shotFront"] = shot("render-particles-front.png")
        r["pass"] = (r["particlesFront"].get("triangles", 0) > 0 and not r["particlesFront"].get("failed"))


def stage_eye(r):
    with Fixture():
        s = pz.state()
        r["standingEye"] = s["viewpoint"].get("eye")
        pz.send_input(["SNEAK"], 2500)
        time.sleep(1.5)
        r["sneakingEye"] = pz.state()["viewpoint"].get("eye")
        time.sleep(1.5)
        r["afterEye"] = pz.state()["viewpoint"].get("eye")
        stand, sneak = r["standingEye"][1], r["sneakingEye"][1]
        r["pass"] = abs(stand - 1.62) < 0.03 and 1.2 < sneak < 1.35


def stage_weather(r):
    def wait_weather(pred, seconds=8):
        t0 = time.time()
        while time.time() - t0 < seconds:
            w = pz.state().get("weather") or {}
            if pred(w):
                return w
            time.sleep(0.5)
        return pz.state().get("weather") or {}

    mark = log_lines()
    mc("weather rain 6000", 0.5)
    r["rain"] = wait_weather(lambda w: w.get("precipitation", 0) > 0.3)
    r["shotRain"] = shot("render-weather-rain.png")
    mc("weather blizzard 6000", 0.5)
    r["blizzard"] = wait_weather(lambda w: w.get("snow") and w.get("precipitation", 0) > 0.5)
    r["shotBlizzard"] = shot("render-weather-blizzard.png")
    mc("weather fog 0.8", 0.5)
    r["fog"] = wait_weather(lambda w: w.get("fog", 0) > 0.5)
    r["shotFog"] = shot("render-weather-fog.png")
    mc("weather thunder 6000", 0.5)
    r["thunder"] = wait_weather(lambda w: w.get("thunder") and w.get("precipitation", 0) > 0.3 and not w.get("snow"), 10)
    r["shotThunder"] = shot("render-weather-thunder.png")
    mc("weather clear", 0.5)
    r["clear"] = wait_weather(lambda w: w.get("precipitation", 0) < 0.05 and w.get("fog", 1) < 0.3 and not w.get("thunder"), 15)
    r["shotClear"] = shot("render-weather-clear.png")
    # PZ's own weather (here started from PZ's side) is mirrored into Minecraft's world once commands settle
    time.sleep(4)
    mirror_mark = log_lines()
    pz.cmd("weather rain 0.5 2")
    time.sleep(5)
    pz.cmd("weather clear")
    time.sleep(5)
    text = Path(pz.LOGS["minecraft"]).read_text(encoding="utf-8", errors="replace").splitlines()[mirror_mark:]
    r["minecraftMirror"] = [l[l.find("PZ weather"):][:150] for l in text if "PZ weather:" in l][-6:]
    r["pass"] = (r["rain"].get("precipitation", 0) > 0.3 and r["blizzard"].get("snow") and r["fog"].get("fog", 0) > 0.5
                 and r["thunder"].get("thunder") and r["clear"].get("precipitation", 1) < 0.05
                 and any("rain=true" in l for l in r["minecraftMirror"]) and "rain=false" in (r["minecraftMirror"] or [""])[-1])


STAGES = {"moving": stage_moving, "boat": stage_boat, "particles": stage_particles, "eye": stage_eye, "weather": stage_weather}


def main(names):
    for name in names:
        r = {"stage": name}
        try:
            STAGES[name](r)
        except Exception as e:  # noqa: BLE001 - record and continue with the next stage
            r["error"] = repr(e)
        (OUT / f"render-weather-{name}.json").write_text(json.dumps(r, indent=2))
        print(json.dumps(r, indent=2))


if __name__ == "__main__":
    main(sys.argv[1:] or list(STAGES))
