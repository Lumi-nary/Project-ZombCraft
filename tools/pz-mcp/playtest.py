"""Automated playtester: a fast decision model plays short, bounded actions while hard checks look for bugs.

Each step:
  1. observe   - compact game state (Steve, PZ player, health, zombies, what is ahead, crosshair, time, indoors).
  2. decide    - OpenRouter's decisions model picks one action from a fixed menu for the session's goal (a few hundred
                 ms; falls back to a seeded random pick without a key).
  3. act       - the action runs through PzCraft's control API (no real mouse or keyboard).
  4. check     - invariants that must always hold (Steve and the PZ player agree, not inside a solid tile, not fallen out
                 of the world, renderers alive, no new errors) and what the action should have done (walking moves you
                 unless something solid is ahead, mining removes a block, placing adds one...).
  5. judge     - every anomaly is triaged by the decision model (bug / expected / test artifact). Optionally
                 (--vision-every N) a vision model also looks at a screenshot every N steps; that sends the game picture
                 to OpenRouter, so it is off unless asked for. The decision model only ever receives the text summary.

Run only on a test session: it moves the character. By default it refuses while PZ has the mouse captured (someone may
be playing); pass force=True for a session you launched yourself. Results go to tools/pz-mcp/playtest-runs/<time>/.

  python playtest.py --goal collision --steps 25
  python pzctl.py playtest --goal building --steps 15 --force
"""
from __future__ import annotations

import argparse
import base64
import json
import math
import random
import re
import time
from pathlib import Path

import pzcore as pz

RUNS = Path(__file__).parent / "playtest-runs"

GOALS = {
    "explore": "Explore the neighbourhood: walk, sprint and jump around buildings, fences, furniture and cars, go in and "
               "out of houses, and look for places where Steve gets stuck, walks through something solid, or the picture "
               "looks wrong.",
    "collision": "Find collision bugs between Steve and Project Zomboid geometry and Minecraft blocks: walk into walls, "
                 "doors, fences, furniture, cars and placed blocks from different angles, jump onto low objects, and "
                 "check that solid things stop Steve and open space never does.",
    "building": "Test Minecraft building inside Project Zomboid: place blocks against the ground, walls and other blocks, "
                "jump on them, mine them again, and look for blocks floating, sinking into walls or being invisible.",
    "combat": "Test fighting Project Zomboid zombies with Minecraft weapons: face zombies, attack them, back off, use "
              "blocks as cover, and check damage, knock-back and that Steve is never frozen in place.",
    "items": "Test the hotbar and items: switch slots with every number, drop and pick up items, eat food, toggle third "
             "person to look at Steve and the dropped items, and check that they are lit and cast shadows.",
}

# name -> (meaning for the decision model, action function name)
ACTIONS = {
    "walk_forward": "walk forward for about a second and a half",
    "sprint_forward": "sprint forward for two seconds",
    "jump_forward": "jump forward once (onto or over something low)",
    "walk_back": "walk backwards for a second",
    "strafe_left": "step sideways to the left for a second",
    "strafe_right": "step sideways to the right for a second",
    "turn_left": "turn 90 degrees to the left",
    "turn_right": "turn 90 degrees to the right",
    "turn_around": "turn 180 degrees",
    "face_zombie": "turn to face the nearest zombie",
    "look_down": "look down at the ground in front",
    "look_level": "look straight ahead",
    "attack": "swing the selected item once (hit a zombie or a block)",
    "mine": "hold attack for three seconds to break the block under the crosshair",
    "place_block": "select oak planks and place one block where the crosshair points",
    "next_slot": "select the next hotbar slot with its number key",
    "drop_item": "drop one of the selected item",
    "eat": "select food and eat it",
    "third_person": "toggle the third-person camera",
    "vision_check": "take a screenshot and have it checked for visual problems",
}

FOOD = ("beef", "porkchop", "bread", "apple", "chicken", "mutton", "carrot", "potato", "cod", "salmon")

# PZ action states in which Viewpoint keeps the camera steady (its Controls.STEADY_STATES); any other state makes the
# view follow the animated head, which is also Steve's eye in Minecraft.
STEADY_STATES = {"idle", "movement", "strafe", "run", "sprint", "aim", "maskingleft", "maskingright", "turning",
                 "turning180", "turningAim180", "turningIdle180", "turningMovement180"}

OBSERVE_LUA = """
local p = getPlayer()
if not p then return "none" end
local cell = getCell()
local px, py, pz = p:getX(), p:getY(), math.floor(p:getZ())
local zl = cell:getZombieList()
local near, best, bx, by = 0, 999, 0, 0
for i = 0, zl:size() - 1 do
  local z = zl:get(i)
  if not z:isDead() then
    local dx, dy = z:getX() - px, z:getY() - py
    local d = math.sqrt(dx * dx + dy * dy)
    if d < 12 then near = near + 1 end
    if d < best then best, bx, by = d, dx, dy end
  end
end
local fx, fy = %f, %f
local here = cell:getGridSquare(math.floor(px), math.floor(py), pz)
local function describe(dist)
  local sq = cell:getGridSquare(math.floor(px + fx * dist), math.floor(py + fy * dist), pz)
  if not sq then return "void" end
  local prev = cell:getGridSquare(math.floor(px + fx * (dist - 1)), math.floor(py + fy * (dist - 1)), pz)
  if sq:isSolid() then return "solid" end
  if sq:isSolidTrans() then return "furniture" end
  if prev and prev ~= sq and prev:isBlockedTo(sq) then return "wall" end
  return "open"
end
local outside = here and here:isOutside() and 1 or 0
local solidHere = here and (here:isSolid() and 1 or 0) or -1
return string.format("%%d|%%.2f|%%.2f|%%.2f|%%s|%%s|%%d|%%d|%%.2f|%%s", near, best, bx, by, describe(1), describe(2), outside, solidHere,
  getGameTime():getTimeOfDay(), tostring(p:getVehicle() ~= nil))
"""


class Playtest:
    def __init__(self, goal: str, steps: int, seed: int, vision_every: int, safe: bool, out: Path, log=print):
        self.goal = goal
        self.steps = steps
        self.rng = random.Random(seed)
        self.vision_every = vision_every
        self.safe = safe
        self.out = out
        self.log = log
        self.history: list[dict] = []
        self.anomalies: list[dict] = []
        self.third_person = False
        self.console_mark = self._log_size(pz.LOGS["console"])
        self.mc_mark = self._log_size(pz.LOGS["minecraft"])
        self.decider = "decision model" if pz.openrouter_key() else "random (no OpenRouter key)"

    # ---- observation ----------------------------------------------------------------------------------------------

    @staticmethod
    def _log_size(path: Path) -> int:
        try:
            return path.stat().st_size
        except OSError:
            return 0

    def _new_errors(self) -> list[str]:
        out = []
        for key, attr in (("console", "console_mark"), ("minecraft", "mc_mark")):
            path = pz.LOGS[key]
            size = self._log_size(path)
            start = getattr(self, attr)
            if size < start:
                start = 0  # the log was restarted
            if size > start:
                with open(path, "rb") as f:
                    f.seek(start)
                    chunk = f.read(size - start).decode("utf-8", "replace")
                for line in chunk.splitlines():
                    if re.search(r"Exception|\bERROR\b|\[PzCraft\].*fail", line) and not re.search(
                            r"FluidContainerScript|AngelCodeFont|CraftRecipeComponentScript|SoundManager", line):
                        out.append(f"{key}: {line.strip()[:240]}")
            setattr(self, attr, size)
        return out[:8]

    def _crosshair(self) -> dict:
        line = pz.logs("minecraft", r"crosshair:", 1)
        m = re.search(r"crosshair: (\w+)(?: at \(([-\d.E]+), ([-\d.E]+), ([-\d.E]+)\))?.*?slot (\d+) = (\d+) (\S+)\)", line)
        if not m:
            return {}
        hit = {"type": m.group(1), "slot": int(m.group(5)) + 1, "count": int(m.group(6)), "item": m.group(7)}
        if m.group(2):
            hit["at"] = [round(float(m.group(i)), 2) for i in (2, 3, 4)]
        bar = re.search(r"hotbar \[([^\]]*)\]", line)
        if bar:
            hit["hotbar"] = bar.group(1).split(",")  # 9 entries, "" for an empty slot
        target = re.search(r"target (\S+)", line)
        if target:
            hit["target"] = target.group(1)  # a Minecraft block id; minecraft:air when aiming at PZ geometry
        return hit

    def observe(self) -> dict:
        s = pz.state()
        steve = s.get("steve") or {}
        yaw = math.radians(float(steve.get("yaw", 0.0)))
        fx, fy = -math.sin(yaw), math.cos(yaw)  # Minecraft forward in PZ's x/y (MC z = PZ y)
        raw = pz.lua(OBSERVE_LUA % (fx, fy))["results"]
        o = {"state": s, "forward": (round(fx, 3), round(fy, 3)), "crosshair": self._crosshair()}
        if raw and raw[0] != "none":
            near, best, bx, by, ahead1, ahead2, outside, solid_here, tod, vehicle = raw[0].split("|")
            bearing = None
            if float(best) < 900:
                # PZ's y grows south, so a positive angle here is clockwise seen from above: to Steve's right
                bearing = round(math.degrees(math.atan2(fx * float(by) - fy * float(bx), fx * float(bx) + fy * float(by))))
            o.update({"zombiesNear": int(near), "nearestZombie": round(float(best), 1) if float(best) < 900 else None,
                      "zombieBearing": bearing, "ahead": [ahead1, ahead2], "outside": outside == "1",
                      "insideSolidTile": solid_here == "1", "hour": round(float(tod), 1), "inVehicle": vehicle == "true"})
        return o

    def describe(self, o: dict) -> str:
        s = o["state"]
        st = s.get("steve") or {}
        h = s.get("health") or {}
        cross = o.get("crosshair") or {}
        z = o.get("nearestZombie")
        zdesc = "no zombies around" if z is None else (
            f"{o['zombiesNear']} zombies within 12 tiles, nearest {z} tiles away "
            f"{'ahead' if abs(o['zombieBearing']) < 30 else ('to the right' if o['zombieBearing'] > 0 else 'to the left')}"
            f" ({o['zombieBearing']} deg)")
        last = ", ".join(f"{e['action']}{'' if e['ok'] else ' (anomaly)'}" for e in self.history[-5:]) or "none yet"
        return (f"Steve {'on the ground' if st.get('onGround') else 'in the air'}, health {h.get('steveHealth')}/{h.get('steveMax')}, "
                f"{'outdoors' if o.get('outside') else 'indoors'} at {o.get('hour')}h, looking {round(float(st.get('pitch', 0)))} deg "
                f"{'down' if float(st.get('pitch', 0)) > 0 else 'up'}. Ahead: 1 tile {o.get('ahead', ['?'])[0]}, 2 tiles "
                f"{o.get('ahead', ['?', '?'])[1]}. Crosshair: {cross.get('type', '?')}. Holding slot {cross.get('slot', '?')}: "
                f"{cross.get('count', '?')} {cross.get('item', '?')}. {zdesc}. Third person: {self.third_person}. "
                f"Last actions: {last}. Anomalies so far: {len(self.anomalies)}.")

    # ---- decisions ------------------------------------------------------------------------------------------------

    def choose(self, o: dict) -> tuple[str, dict]:
        choices = dict(ACTIONS)
        if o.get("nearestZombie") is None:
            choices.pop("face_zombie")
        if self.vision_every <= 0 or not pz.openrouter_key():
            choices.pop("vision_check")
        recent = [e["action"] for e in self.history[-2:]]
        if len(recent) == 2 and recent[0] == recent[1]:
            choices.pop(recent[0], None)  # never the same action three times in a row
        if pz.openrouter_key():
            try:
                tried = {e["action"] for e in self.history}
                untried = [a for a in choices if a not in tried]
                instructions = ("You are a game tester for a Project Zomboid x Minecraft mashup (Minecraft's Steve plays "
                                "inside Project Zomboid). Goal: " + GOALS[self.goal] + " Pick the most useful next action "
                                "for that goal. Prefer actions not tried yet (" + (", ".join(untried) or "none") + ") and "
                                "do not repeat one that just failed.")
                d = pz.decide(self.describe(o), instructions, choices)
                probs = {k: float(v) for k, v in (d.get("probabilities") or {}).items() if k in choices}
                if probs:
                    # Sample from the model's own preferences (softened) rather than always taking the top one, which
                    # collapses into the model's favourite action; the seed keeps runs repeatable.
                    names = list(probs)
                    pick = self.rng.choices(names, [max(probs[n], 1e-6) ** 0.5 for n in names])[0]
                    return pick, {"model": d["choice"], "p": round(probs[pick], 3), "ms": d["ms"]}
                if d["choice"] in choices:
                    return d["choice"], {"confidence": round(d["confidence"], 3), "ms": d["ms"]}
            except pz.PzError as e:
                self.log(f"  decision model unavailable ({e}); random pick")
        return self.rng.choice(list(choices)), {"random": True}

    def triage(self, anomaly: dict) -> dict:
        if not pz.openrouter_key():
            return {}
        try:
            d = pz.decide(json.dumps(anomaly)[:1500],
                          "A playtest check fired in a Project Zomboid x Minecraft mashup. Classify it. Facts: Steve (Minecraft) "
                          "drives the PZ player; PZ walls, floors, furniture and cars are solid for Steve but cannot be "
                          "mined; zombies and closed doors legitimately stop him; one PZ storey is 2.45 Minecraft blocks; "
                          "the test moves the character with teleports and samples state a few times a second.",
                          {"bug": "the game or the mod misbehaves and a player would notice",
                           "expected": "correct behaviour that the check misread (e.g. blocked by a real wall or zombie)",
                           "test_artifact": "caused by the automated test itself (timing, teleports, sampling)"})
            return {"triage": d["choice"], "triageConfidence": round(d["confidence"], 3)}
        except pz.PzError:
            return {}

    # ---- actions --------------------------------------------------------------------------------------------------

    def _hold(self, buttons, ms, sample=False):
        """Hold buttons; with sample=True also poll Steve while they are held (for jumps)."""
        if not sample:
            pz.send_input(buttons, ms, wait=True)
            return []
        pz.send_input(buttons, ms)
        seen, t0 = [], time.time()
        while time.time() - t0 < ms / 1000 + 0.6:
            st = pz.state().get("steve") or {}
            seen.append((st.get("y"), st.get("onGround")))
            time.sleep(0.08)
        return seen

    def _select(self, predicate) -> bool:
        """Select the first hotbar slot whose item matches (pressing its number key, which also exercises them)."""
        bar = (self._crosshair() or {}).get("hotbar")
        if bar:
            for slot, item in enumerate(bar, start=1):
                if item and predicate(item):
                    pz.send_input(hotbar=slot, wait=True)
                    return True
            return False
        for slot in range(1, 10):  # an older Minecraft side without the hotbar list: cycle and read the held item
            pz.send_input(hotbar=slot, wait=True)
            time.sleep(3.2)  # Minecraft logs the held item every 3 s
            item = (self._crosshair() or {}).get("item", "")
            if predicate(item):
                return True
        return False

    def act(self, name: str, o: dict) -> dict:
        info: dict = {}
        if name == "walk_forward":
            self._hold(["FWD"], 1500)
        elif name == "sprint_forward":
            self._hold(["FWD", "SPRINT"], 2000)
        elif name == "jump_forward":
            info["samples"] = self._hold(["FWD", "JUMP"], 700, sample=True)
        elif name == "walk_back":
            self._hold(["BACK"], 1000)
        elif name == "strafe_left":
            self._hold(["LEFT"], 1000)
        elif name == "strafe_right":
            self._hold(["RIGHT"], 1000)
        elif name in ("turn_left", "turn_right", "turn_around"):
            pz.send_input(look={"yaw": {"turn_left": -90, "turn_right": 90, "turn_around": 180}[name], "relative": True}, wait=True)
        elif name == "face_zombie":
            pz.send_input(look={"yaw": float(o.get("zombieBearing") or 0), "pitch": 10, "relative": True}, wait=True)
            pz.send_input(look={"pitch": 10, "relative": False}, wait=True)
        elif name == "look_down":
            pz.send_input(look={"pitch": 45, "relative": False}, wait=True)
        elif name == "look_level":
            pz.send_input(look={"pitch": 0, "relative": False}, wait=True)
        elif name == "attack":
            self._hold(["ATTACK"], 150)
        elif name == "mine":
            self._hold(["ATTACK"], 3000)
        elif name == "place_block":
            if "planks" not in (o.get("crosshair") or {}).get("item", ""):
                info["selected"] = self._select(lambda it: "planks" in it or "stone" in it)
            self._hold(["USE"], 150)
        elif name == "next_slot":
            slot = ((o.get("crosshair") or {}).get("slot", 0)) % 9 + 1
            pz.send_input(hotbar=slot, wait=True)
            info["slot"] = slot
        elif name == "drop_item":
            self._hold(["DROP"], 120)
        elif name == "eat":
            if not any(f in (o.get("crosshair") or {}).get("item", "") for f in FOOD):
                info["selected"] = self._select(lambda it: any(f in it for f in FOOD))
            self._hold(["USE"], 1900)
        elif name == "third_person":
            self.third_person = not self.third_person
            info["result"] = pz.cmd("third " + ("on" if self.third_person else "off"))["result"]
        elif name == "vision_check":
            info["vision"] = self.vision()
        time.sleep(0.6)  # let Minecraft's 3 s crosshair log and PZ settle a little
        return info

    def vision(self) -> dict:
        png = pz.screenshot(0.5)
        path = self.out / f"vision-{len(self.history):03d}.png"
        path.write_bytes(png)
        question = ("This is Project Zomboid rendered in 3D with Minecraft blocks, items and a Minecraft player mixed in. "
                    "Look for rendering problems only: blocks or items floating or sunk into walls/floors, missing or "
                    "black textures, things that should cast a shadow but do not, flickering/z-fighting, parts of the "
                    "Minecraft player clipping through the camera, UI covering the view. Reply with JSON only: "
                    '{"broken": true|false, "issue": "short description or empty"}')
        try:
            b64 = base64.b64encode(png).decode()
            answer = pz.openrouter(pz.VISION_MODEL, [{"type": "text", "text": question},
                                                     {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}}],
                                   max_tokens=200)
            m = re.search(r"\{.*\}", answer, re.S)
            verdict = json.loads(m.group(0)) if m else {"broken": False, "issue": answer[:200]}
        except (pz.PzError, ValueError) as e:
            verdict = {"error": str(e)}
        verdict["screenshot"] = str(path)
        return verdict

    # ---- checks ---------------------------------------------------------------------------------------------------

    def check(self, name: str, before: dict, after: dict, info: dict) -> list[str]:
        problems = []
        s = after["state"]
        st, pl = s.get("steve") or {}, s.get("player") or {}
        link = s.get("link") or {}
        if not link.get("connected") or not link.get("ready"):
            problems.append(f"link not ready: {link}")
        elif st and pl:
            gap = math.hypot(float(st["x"]) - float(pl["x"]), float(st["z"]) - float(pl["y"]))
            if gap > 0.75:
                problems.append(f"Steve and the PZ player disagree by {gap:.2f} tiles")
            level_y = math.floor(float(pl.get("z", 0))) * 2.449
            if float(st["y"]) < level_y - 2.0:
                problems.append(f"Steve fell below the floor (y={st['y']:.2f}, level floor {level_y:.2f})")
        state_name = pl.get("actionState") if pl else None
        if state_name and pl.get("steveDrives") and state_name not in STEADY_STATES:
            problems.append(f"PZ animation state '{state_name}' while Steve drives (moves the camera and Steve's eye)")
        square = pl.get("square") if pl else None
        if square is not None:
            # A tile is also solid while a Minecraft block stands in it, and Steve may stand on top of that block.
            if square.get("solid") and not square.get("minecraftBlock"):
                problems.append("the PZ player stands inside a tile PZ itself treats as solid (wall or furniture)")
        elif after.get("insideSolidTile") and st and pl:
            floor_y = math.floor(float(pl.get("z", 0))) * 2.449
            if float(st["y"]) - floor_y < 0.5:  # older build without the square details: only flag it at floor height
                problems.append("the PZ player stands inside a solid tile at floor height")
        for key in ("nativeBlocks", "nativeEntities"):
            if (s.get(key) or {}).get("failed"):
                problems.append(f"{key} renderer failed: {s.get(key)}")
        if (s.get("overlay") or {}).get("disabled"):
            problems.append("Minecraft overlay disabled")
        if float(s.get("fps") or 0) < 20:
            problems.append(f"low fps {s.get('fps')}")
        h = s.get("health") or {}
        if link.get("ready") and h and not h.get("minecraftOwnsHealth"):
            problems.append("Minecraft does not own health while Steve is in play")

        moved = 0.0
        b, a = (before["state"].get("steve") or {}), st
        if b and a:
            moved = math.hypot(float(a["x"]) - float(b["x"]), float(a["z"]) - float(b["z"]))
        ahead = before.get("ahead") or ["?", "?"]
        zombie_close = (before.get("nearestZombie") or 99) < 1.5
        if name in ("walk_forward", "sprint_forward") and moved < 0.4 and ahead[0] == "open" and not zombie_close:
            problems.append(f"{name} moved Steve only {moved:.2f} although the tile ahead is open "
                            f"(crosshair {before.get('crosshair', {}).get('type')})")
        if name == "jump_forward":
            ys = [y for y, _g in info.get("samples", []) if y is not None]
            if ys and max(ys) - float(b.get("y", 0)) < 0.5:
                problems.append(f"jump did not lift Steve (max rise {max(ys) - float(b.get('y', 0)):.2f})")
        cross = before.get("crosshair") or {}
        target = cross.get("target")
        # Steve's crosshair also reports PZ walls and floors as block hits (target air): those cannot be mined. Without
        # the target (an older Minecraft side) the check cannot tell them apart, so it stays quiet.
        if name == "mine" and cross.get("type") == "BLOCK" and target and target not in ("minecraft:air", "-"):
            # Steve did not move, so if the same block is still under the crosshair at the same point it did not break.
            # (The block count would not do: it counts PZ tiles, and a tile stays covered while any block is left.)
            after_cross = after.get("crosshair") or {}
            if after_cross.get("target") == target and after_cross.get("at") == cross.get("at"):
                problems.append(f"mining {target} at {cross.get('at')} did not break it in 3 s")
        if name == "next_slot":
            want = info.get("slot")
            got = (after.get("crosshair") or {}).get("slot")
            if want and got and want != got:
                problems.append(f"hotbar key {want} selected slot {got}")
        if name == "vision_check" and (info.get("vision") or {}).get("broken"):
            problems.append(f"vision: {info['vision'].get('issue')} ({info['vision'].get('screenshot')})")
        problems += [f"new log error: {e}" for e in self._new_errors()]
        return problems

    # ---- run ------------------------------------------------------------------------------------------------------

    def run(self) -> dict:
        self.out.mkdir(parents=True, exist_ok=True)
        start = pz.state()
        pause = pz.lua("return tostring(getCore():getOptionPauseOnFocusloss())")["results"][0]
        pz.lua("getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)")
        if self.safe:
            pz.cmd("heal")
            pz.cmd("mc effect give @s minecraft:resistance 1000000 4 true")
        t_start = time.time()
        try:
            o = self.observe()
            for step in range(1, self.steps + 1):
                if self.vision_every > 0 and pz.openrouter_key() and step % self.vision_every == 0:
                    action, why = "vision_check", {"scheduled": True}
                else:
                    action, why = self.choose(o)
                info = self.act(action, o)
                time.sleep(3.0 if action in ("next_slot",) else 0.0)  # the held-item log needs a beat
                after = self.observe()
                problems = self.check(action, o, after, info)
                entry = {"step": step, "action": action, "why": why, "ok": not problems,
                         "steve": after["state"].get("steve"), "ahead": o.get("ahead"), "problems": problems}
                if info.get("vision"):
                    entry["vision"] = info["vision"]
                self.history.append(entry)
                self.log(f"[{step:02d}] {action:15s} {'ok' if not problems else 'ANOMALY: ' + '; '.join(problems)}")
                for p in problems:
                    shot = self.out / f"anomaly-{step:03d}.png"
                    try:
                        shot.write_bytes(pz.screenshot(0.5))
                    except pz.PzError:
                        shot = None
                    a = {"step": step, "action": action, "problem": p, "context": self.describe(o),
                         "screenshot": str(shot) if shot else None}
                    a.update(self.triage(a))
                    self.anomalies.append(a)
                if not (after["state"].get("link") or {}).get("ready"):
                    self.log("  Steve is not in play (dead or respawning); stopping")
                    break
                o = after
        finally:
            if self.third_person:
                pz.cmd("third off")
            pz.send_input(release=True)
            if self.safe:
                pz.cmd("mc effect clear @s minecraft:resistance")
            try:
                now = pz.state().get("player")
                sp = start.get("player")
                if now and sp and (pz.state().get("link") or {}).get("ready"):
                    pz.cmd(f"tp {sp['x'] - now['x']} {sp['y'] - now['y']}")  # hand the character back where it was
            except pz.PzError:
                pass
            pz.lua("getCore():setOptionPauseOnFocusloss(" + pause + ")")
        summary = {"goal": self.goal, "steps": len(self.history), "decider": self.decider,
                   "seconds": round(time.time() - t_start), "anomalies": len(self.anomalies),
                   "bugs": sum(1 for a in self.anomalies if a.get("triage") == "bug"),
                   "out": str(self.out)}
        (self.out / "history.json").write_text(json.dumps(self.history, indent=2))
        (self.out / "anomalies.json").write_text(json.dumps(self.anomalies, indent=2))
        (self.out / "summary.json").write_text(json.dumps(summary, indent=2))
        (self.out / "report.md").write_text(self.report(summary), encoding="utf-8")
        return {"summary": summary, "anomalies": self.anomalies}

    def report(self, summary: dict) -> str:
        lines = [f"# Playtest: {self.goal}", "",
                 f"{summary['steps']} steps in {summary['seconds']} s, actions chosen by {self.decider}. "
                 f"{summary['anomalies']} anomalies ({summary['bugs']} triaged as bugs).", "", "## Anomalies", ""]
        if not self.anomalies:
            lines.append("None.")
        for a in self.anomalies:
            tri = f" [{a['triage']} {a.get('triageConfidence')}]" if a.get("triage") else ""
            lines.append(f"- step {a['step']} `{a['action']}`{tri}: {a['problem']}")
            if a.get("screenshot"):
                lines.append(f"  - screenshot: {a['screenshot']}")
        lines += ["", "## Steps", "", "| # | action | result |", "|---|---|---|"]
        for e in self.history:
            lines.append(f"| {e['step']} | {e['action']} | {'ok' if e['ok'] else '; '.join(e['problems'])[:160]} |")
        return "\n".join(lines) + "\n"


def run(goal: str = "explore", steps: int = 20, seed: int = 1, vision_every: int = 0, safe: bool = True,
        force: bool = False, log=print) -> dict:
    if goal not in GOALS:
        raise pz.PzError(f"unknown goal '{goal}' (have: {', '.join(GOALS)})")
    s = pz.state()
    if not s.get("inGame") or not (s.get("link") or {}).get("ready"):
        raise pz.PzError("not in game with Steve placed: launch first (pzctl launch)")
    if (s.get("input") or {}).get("captured") and not force:
        raise pz.PzError("PZ has the mouse captured, so someone may be playing: the playtest would move their character. "
                         "Pass force=True on a test session you launched yourself.")
    out = RUNS / time.strftime("%Y%m%d-%H%M%S")
    return Playtest(goal, steps, seed, vision_every, safe, out, log).run()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="playtest")
    ap.add_argument("--goal", default="explore", choices=list(GOALS))
    ap.add_argument("--steps", type=int, default=20)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--vision-every", type=int, default=0,
                    help="send a screenshot to the OpenRouter vision model every N steps (default 0 = never)")
    ap.add_argument("--unsafe", action="store_true", help="no resistance effect: Steve can die (tests damage too)")
    ap.add_argument("--force", action="store_true", help="run even though PZ has the mouse captured")
    a = ap.parse_args(argv)
    try:
        r = run(a.goal, a.steps, a.seed, a.vision_every, not a.unsafe, a.force)
    except pz.PzError as e:
        print(f"error: {e}")
        return 1
    print(json.dumps(r["summary"], indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
