"""0.11 camera checks through the game control API. Run only on the backed-up feature save."""
import json
import math
import time
from pathlib import Path
import pzcore as pz
from importlib.machinery import SourceFileLoader

helper = SourceFileLoader("render_checks", str(Path(__file__).with_name("check-render-weather.py"))).load_module()
OUT = Path(__file__).parent


def state():
    return pz.state()


def wait_mode(mode):
    return pz.wait_for(lambda: state() if state()["viewpoint"]["minecraftCamera"].get("mode") == mode else None,
                       10, .15, f"Minecraft camera mode {mode}")


def cycle():
    key = state()["minecraftGui"].get("perspectiveKey", 294)
    for action in (1, 0):
        assert pz.api("POST", "/gui", {"key": key, "action": action})["ok"]
    time.sleep(.5)


def mode(target):
    for _ in range(3):
        if state()["viewpoint"]["minecraftCamera"].get("mode") == target:
            return wait_mode(target)
        cycle()
    return wait_mode(target)


def distance(s):
    cam = s["viewpoint"]["minecraftCamera"]["position"]
    steve = s["steve"]
    eye = (steve["x"], steve["y"] + 1.62, steve["z"])
    return math.dist(cam, eye)


def shot(name):
    (OUT / name).write_bytes(pz.screenshot(.65))


def run():
    result = {"passed": False}
    pz.wait_for(lambda: state().get("gameState") == "IngameState" and state()["link"]["ready"],
                30, .5, "loaded feature save and Minecraft")
    native_wall = None
    try:
        with helper.Fixture() as f:
            pz.send_input(look={"yaw": -90, "pitch": 12, "relative": False}, wait=True)
            time.sleep(1)
            s = mode(0)
            result["first"] = s["viewpoint"]["minecraftCamera"]
            shot("camera-first.png")
            cycle(); s = wait_mode(1)
            result["back"] = s["viewpoint"]["minecraftCamera"]
            result["backDistance"] = distance(s)
            assert abs(result["backDistance"] - 4) < .05, result
            assert not s["viewpoint"]["thirdPerson"] and not s["minecraftGui"]["firstPersonHand"]
            shot("camera-back.png")
            cycle(); s = wait_mode(2)
            result["front"] = s["viewpoint"]["minecraftCamera"]
            assert abs(((result["front"]["yaw"] - result["back"]["yaw"]) % 360) - 180) < .5
            assert abs(result["front"]["pitch"] + result["back"]["pitch"]) < .5
            assert abs(distance(s) - 4) < .05
            assert not s["minecraftGui"]["firstPersonHand"]
            shot("camera-front.png")
            cycle(); s = wait_mode(0)
            assert s["minecraftGui"]["firstPersonHand"]
            assert s["viewpoint"]["minecraftCamera"]["draws"] > 0 and not s["viewpoint"]["minecraftCamera"]["failed"]
            pz.cmd("third on"); wait_mode(1)
            pz.cmd("camera 2"); wait_mode(2)
            pz.cmd("third off"); wait_mode(0)
            result["explicitCameraCommands"] = True
            # Level pitch makes the obstacle fixture predictable.
            pz.send_input(look={"yaw": -90, "pitch": 0, "relative": False}, wait=True)
            for yy in range(3):
                for zz in range(f.y - 1, f.y + 2):
                    f.block(f.x - 2, yy, zz, "minecraft:stone")
            s = mode(1)
            result["minecraftWallDistance"] = distance(s)
            assert .2 < result["minecraftWallDistance"] < 2, result
            shot("camera-minecraft-wall.png")
            mode(0)
            for x, y, z in f.blocks:
                helper.mc(f"setblock {x} {y} {z} minecraft:air", .04)
            f.blocks.clear()
            # Spawn a real native wall; do not damage a map building.
            native_wall = f"_pzCameraWall"
            helper.lua1(f"local sq=getCell():getGridSquare({f.x-1},{f.y},0); "
                        f"{native_wall}=IsoObject.new(sq,'walls_exterior_house_01_0','PzCraftCameraFixture'); "
                        f"sq:AddTileObject({native_wall}); sq:RecalcAllWithNeighbours(true); return 'wall spawned'")
            time.sleep(3)
            s = mode(1)
            result["pzWallDistance"] = distance(s)
            assert .2 < result["pzWallDistance"] < 2.5, result
            shot("camera-pz-wall.png")
            helper.lua1(f"if {native_wall} then local sq={native_wall}:getSquare(); sq:RemoveTileObject({native_wall}); "
                        f"sq:RecalcAllWithNeighbours(true); {native_wall}=nil end; return 'wall removed'")
            native_wall = None
            time.sleep(2)
            assert abs(distance(state()) - 4) < .05, "removed native wall left camera collision"
            result["passed"] = True
    except Exception as e:
        result["error"] = str(e)
        raise
    finally:
        if native_wall:
            helper.lua1(f"if {native_wall} then local sq={native_wall}:getSquare(); sq:RemoveTileObject({native_wall}); "
                        f"sq:RecalcAllWithNeighbours(true); {native_wall}=nil end")
        mode(0)
        pz.lua("UIManager.getSpeedControls():SetCurrentGameSpeed(0)")
        (OUT / "camera-results.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result, indent=2), flush=True)


if __name__ == "__main__":
    run()
