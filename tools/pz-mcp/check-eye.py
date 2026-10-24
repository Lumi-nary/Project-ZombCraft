"""PZ animations must not move the camera (and Steve's eye) while Steve drives: the PZ character sits down on the ground
(an animation far outside Viewpoint's steady states) while the eye is sampled ten times a second. With the default
"eye steady" the eye stays at Steve's eye height; "eye follow" (Viewpoint's own behaviour, for comparison) drops with
the sitting head. The character stands up again afterwards."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
STEADY = {"idle", "movement", "strafe", "run", "sprint", "aim", "maskingleft", "maskingright", "turning", "turning180",
          "turningAim180", "turningIdle180", "turningMovement180"}


def sample(seconds):
    samples = []
    t0 = time.time()
    while time.time() - t0 < seconds:
        s = pz.state()
        eye = (s.get("viewpoint") or {}).get("eye")
        samples.append({"state": (s.get("player") or {}).get("actionState"), "eye": [round(float(v), 3) for v in eye] if eye else None})
        time.sleep(0.1)
    return samples


def main():
    results = {}
    pause = pz.lua("return tostring(getCore():getOptionPauseOnFocusloss())")["results"][0]
    try:
        pz.lua("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)")
        pz.send_input(look={"pitch": 0, "relative": False}, wait=True)
        pz.cmd("eye steady")
        standing = pz.state()["viewpoint"]["eye"][1]
        pz.lua('getPlayer():reportEvent("EventSitOnGround")')
        held = sample(3.0)
        seated = [x for x in held if x["state"] not in STEADY and x["eye"]]
        results["steady"] = {"standingEye": standing, "statesSeen": sorted({str(x["state"]) for x in held}),
                             "seatedSamples": len(seated),
                             "maxEyeChange": round(max((abs(x["eye"][1] - standing) for x in seated), default=0.0), 3)}
        pz.cmd("eye follow")
        followed = sample(1.5)
        results["follow"] = {"lowestEye": min((x["eye"][1] for x in followed if x["eye"]), default=None)}
        r = results
        r["pass"] = r["steady"]["seatedSamples"] > 0 and r["steady"]["maxEyeChange"] <= 0.02 \
            and r["follow"]["lowestEye"] is not None and r["follow"]["lowestEye"] < standing - 0.3
    finally:
        pz.cmd("eye steady")
        pz.lua('getPlayer():setVariable("forceGetUp", true)')
        time.sleep(2.0)
        pz.lua('getPlayer():clearVariable("forceGetUp")')
        results["afterState"] = pz.state()["player"].get("actionState")
        pz.lua("getCore():setOptionPauseOnFocusloss(" + pause + ")")
        (OUT / "eye-results.json").write_text(json.dumps(results, indent=2))
        print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
