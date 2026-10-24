"""0.11 survival ownership: saved PZ stats, hidden moodles, vanilla hunger and regeneration, restored stats."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
result = {"passed": False}
expected = {"Hunger": 1, "Thirst": 1, "Panic": 100, "Fatigue": 1, "Temperature": 30}
baseline = None
baseline_cold_stage = 0


def lua(code):
    r = pz.lua(code)
    assert r["ok"], r
    return r["results"][0] if r["results"] else None


def mc(command):
    pz.cmd("mc " + command)
    time.sleep(.15)


def until_active(active):
    return pz.wait_for(lambda: pz.state() if pz.state()["steveVitals"]["active"] == active else None,
                       10, .15, "Steve survival ownership")


def run():
    global baseline, baseline_cold_stage
    pz.wait_for(lambda: pz.state().get("gameState") == "IngameState" and pz.state()["link"]["ready"],
                30, .5, "loaded feature save and Minecraft")
    pause_option = lua("return tostring(getCore():getOptionPauseOnFocusloss())")
    try:
        lua("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(0)")
        pz.cmd("view off"); until_active(False)
        baseline = pz.state()["steveVitals"]["stats"]
        baseline_cold_stage = pz.state()["steveVitals"]["coldDamageStage"]
        lua("getPlayer():getBodyDamage():RestoreToFullHealth(); return 'cleared prior test injuries'")
        assignments = "; ".join(f"p:getStats():set(CharacterStat.{name.upper()}, {value})" for name, value in expected.items())
        lua("local p=getPlayer(); " + assignments + "; p:getBodyDamage():setHasACold(true); "
            "p:getBodyDamage():setColdStrength(70); p:getBodyDamage():setColdDamageStage(.7); "
            "p:getMoodles():Update(); return 'set survival fixture'")
        pz.cmd("view on"); s = until_active(True)
        assert s["steveVitals"]["saved"] and not s["steveVitals"]["hasCold"]
        lua("getPlayer():getMoodles():Update(); return 'updated moodles'")
        levels = lua("local max,nChecked=0,0; local p=getPlayer(); for _,v in pairs(MoodleType) do "
                     "if type(v)=='userdata' then local ok,n=pcall(function() return p:getMoodles():getMoodleLevel(v) end); "
                     "if ok then max=math.max(max,n); nChecked=nChecked+1 end end end; return max..','..nChecked")
        result["maxMoodleLevel"] = levels
        max_level, checked = (int(float(v)) for v in levels.split(','))
        assert max_level == 0 and checked >= 20, levels
        lua("_pzMoodleZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); "
            "table.insert(_pzMoodleZeds,{z,z:isUseless()}); z:setUseless(true) end; UIManager.getSpeedControls():SetCurrentGameSpeed(1)")
        lua("_pzMoodleDamage={}; _pzMoodleHandler=function(p,kind,amount) table.insert(_pzMoodleDamage,tostring(kind)..':'..tostring(amount)) end; "
            "Events.OnPlayerGetDamage.Add(_pzMoodleHandler)")
        mc("gamemode survival")
        pz.wait_for(lambda: pz.state()["link"]["ready"] and pz.state()["minecraftGui"]["server"].get("minecraftOwnsFood"),
                    15, .2, "Minecraft survival after placement")
        pz.cmd("heal")
        time.sleep(2)
        mc("effect give @s minecraft:saturation 2 19 true")
        time.sleep(3)
        mc("effect clear @s minecraft:saturation")
        s = pz.state(); events = s["health"]["pzDamageEvents"]
        result["initialFood"] = s["minecraftGui"]["server"]["food"]
        mc("effect give @s minecraft:hunger 65 14 true")
        samples = []
        result["samples"] = samples
        t0 = time.monotonic()
        while time.monotonic() - t0 < 60:
            s = pz.state(); v = s["steveVitals"]
            assert v["active"] and not v["reflectionFailed"]
            assert v["stats"]["Hunger"] == 0 and v["stats"]["Thirst"] == 0
            assert v["stats"]["Panic"] == 0 and v["stats"]["Fatigue"] == 0 and v["stats"]["Temperature"] == 37
            assert v["coldDamageStage"] == 0
            samples.append({"seconds": round(time.monotonic()-t0,1), "food": s["minecraftGui"]["server"]["food"],
                            "saturation": s["minecraftGui"]["server"]["saturation"], "pzDamageEvents": s["health"]["pzDamageEvents"]})
            assert s["health"]["pzDamageEvents"] == events, "PZ damage reached Minecraft; inspect damageCauses and samples"
            time.sleep(1)
        mc("effect clear @s minecraft:hunger")
        result["samples"] = samples
        assert samples[-1]["food"] < result["initialFood"], "PZ kept overwriting vanilla hunger"
        assert s["steveVitals"]["hiddenFrames"] > 0, "PZ moodle UI was not suppressed"
        (OUT / "moodles-steve.png").write_bytes(pz.screenshot(.65))
        # A real PZ injury still forwards; vanilla well-fed regeneration heals it.
        pz.cmd("hurt 10"); time.sleep(1)
        damaged = pz.state()["health"]["steveHealth"]
        mc("effect give @s minecraft:saturation 2 19 true")
        time.sleep(12)
        healed = pz.state()["health"]["steveHealth"]
        result["regeneration"] = {"damaged": damaged, "healed": healed}
        assert healed > damaged, "vanilla regeneration failed"
        lua("UIManager.getSpeedControls():SetCurrentGameSpeed(0)")
        pz.cmd("view off"); s = until_active(False)
        result["restored"] = s["steveVitals"]
        for name, value in expected.items():
            assert abs(s["steveVitals"]["stats"][name] - value) < .001, (name, s)
        assert s["steveVitals"]["hasCold"] and not s["steveVitals"]["saved"]
        assert abs(s["steveVitals"]["coldDamageStage"] - .7) < .001
        result["passed"] = True
    except Exception as e:
        result["error"] = str(e)
        result["damageCauses"] = lua("return _pzMoodleDamage and table.concat(_pzMoodleDamage,';') or 'none'")
        result["failureState"] = pz.state()
        raise
    finally:
        mc("effect clear @s minecraft:hunger"); mc("effect clear @s minecraft:saturation")
        lua("UIManager.getSpeedControls():SetCurrentGameSpeed(0)")
        pz.cmd("view off"); until_active(False)
        if baseline:
            statements = "; ".join(f"p:getStats():set(CharacterStat.getById('{key}'), {value})" for key, value in baseline.items())
            lua("local p=getPlayer(); " + statements + "; p:getBodyDamage():setHasACold(false); p:getBodyDamage():setColdStrength(0); "
                f"p:getBodyDamage():setColdDamageStage({baseline_cold_stage})")
        lua("if _pzMoodleZeds then for _,v in ipairs(_pzMoodleZeds) do v[1]:setUseless(v[2]) end; _pzMoodleZeds=nil end; "
            "if _pzMoodleHandler then Events.OnPlayerGetDamage.Remove(_pzMoodleHandler); _pzMoodleHandler=nil end; "
            "getCore():setOptionPauseOnFocusloss(" + pause_option + ")")
        pz.cmd("view on")
        (OUT / "moodles-results.json").write_text(json.dumps(result, indent=2))
        print(json.dumps({k: v for k, v in result.items() if k not in ("samples", "failureState")}, indent=2), flush=True)


if __name__ == "__main__":
    run()
