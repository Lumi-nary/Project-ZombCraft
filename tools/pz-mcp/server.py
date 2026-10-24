"""MCP server (stdio, newline-delimited JSON-RPC) that gives an agent direct control of Project Zomboid + PzCraft.

Standard library only. Register it with (project scope):  claude mcp add pz -- python tools/pz-mcp/server.py
Tools talk to PzCraft's local control API (127.0.0.1:47600, token file), so reading state, injecting input and taking
screenshots do not touch the user's real mouse or keyboard and work while PZ is in the background.
"""
from __future__ import annotations

import base64
import json
import sys
import traceback

import pzcore as pz

PROTOCOL = "2025-06-18"
SUPPORTED = {"2024-11-05", "2025-03-26", "2025-06-18"}


def text(s: str) -> dict:
    return {"type": "text", "text": s}


def jtext(o) -> dict:
    return text(json.dumps(o, indent=2))


BUTTONS = ["FWD", "BACK", "LEFT", "RIGHT", "JUMP", "SNEAK", "SPRINT", "ATTACK", "USE", "DROP"]


def t_status(_a):
    out = {"processes": pz.processes()}
    try:
        out["state"] = pz.state()
    except pz.PzError as e:
        out["state"] = f"unreachable: {e}"
    return [jtext(out)]


def t_screenshot(a):
    png = pz.screenshot(float(a.get("scale", 0.5)))
    return [{"type": "image", "data": base64.b64encode(png).decode(), "mimeType": "image/png"}]


def t_input(a):
    look = None
    if "yaw" in a or "pitch" in a:
        look = {"relative": not a.get("absolute", False)}
        for k in ("yaw", "pitch"):
            if k in a:
                look[k] = float(a[k])
    r = pz.send_input(a.get("hold"), a.get("ms", 200), look, a.get("hotbar"), a.get("wait", False), a.get("release", False))
    return [jtext(r)]


def t_cmd(a):
    return [jtext(pz.cmd(a["line"]))]


def t_lua(a):
    return [jtext(pz.lua(a["code"]))]


def t_launch(a):
    return [jtext(pz.launch(a.get("restart", False), a.get("allow_all", False), a.get("enter_game", True), a.get("enable_3d", True),
                           save=a.get("save"), game_mode=a.get("game_mode", "Apocalypse"),
                           focus_fallback=a.get("focus_fallback", False)))]


def t_stop(a):
    return [jtext(pz.kill(a.get("which", "all")))]


def t_logs(a):
    return [text(pz.logs(a.get("source", "console"), a.get("pattern"), int(a.get("lines", 40))))]


def t_vision(a):
    return [text(pz.vision(a["question"], float(a.get("scale", 0.5))))]


def t_digest(a):
    return [text(pz.log_digest(a.get("source", "console"), int(a.get("lines", 200))))]


def t_decide(a):
    return [jtext(pz.decide(a["state"], a["instructions"], a["choices"]))]


def t_fast(a):
    return [text(pz.fast(a["prompt"]))]


def t_playtest(a):
    import playtest
    lines = []
    r = playtest.run(a.get("goal", "explore"), int(a.get("steps", 15)), int(a.get("seed", 1)), int(a.get("vision_every", 0)),
                     not a.get("unsafe", False), bool(a.get("force", False)), log=lines.append)
    return [jtext({"summary": r["summary"], "anomalies": r["anomalies"], "log": lines})]


def t_respawn(_a):
    return [jtext(pz.respawn())]


TOOLS = {
    "pz_status": (t_status, "Processes (PZ, Minecraft) plus PzCraft's full game state: fps, game state, paused, Viewpoint 3D, link, "
                  "player/Steve pose, health, hunger, zombies, blocks, overlay.", {}, []),
    "pz_screenshot": (t_screenshot, "Screenshot of exactly what PZ rendered (taken inside the game, works in the background).",
                      {"scale": {"type": "number", "description": "0.1-1.0, default 0.5"}}, []),
    "pz_input": (t_input, "Inject input without touching the real keyboard/mouse. hold: buttons for ms. yaw/pitch: look change in "
                 "Minecraft degrees (relative unless absolute=true; yaw 0=south 90=west, pitch + = down). hotbar 1-9. "
                 "wait=true blocks until the hold ends. release=true clears holds.",
                 {"hold": {"type": "array", "items": {"type": "string", "enum": BUTTONS}}, "ms": {"type": "integer"},
                  "yaw": {"type": "number"}, "pitch": {"type": "number"}, "absolute": {"type": "boolean"},
                  "hotbar": {"type": "integer"}, "wait": {"type": "boolean"}, "release": {"type": "boolean"}}, []),
    "pz_cmd": (t_cmd, "Dev command: spawn [n] [dist], pull [dist], tp dx dy, hurt n, hunger 0..1, where, heal, "
               "block x y z id (Minecraft coords), mc <minecraft command>, third on|off, native on|off, shadows on|off.",
               {"line": {"type": "string"}}, ["line"]),
    "pz_lua": (t_lua, "Run Lua inside PZ on the game thread; returns pcall results. e.g. return getPlayer():getX()",
               {"code": {"type": "string"}}, ["code"]),
    "pz_launch": (t_launch, "Start PZ + hidden Minecraft (scripts/start-all.bat), continue the latest save, switch on Viewpoint 3D "
                  "and wait until Steve is ready. allow_all=true makes ZombieBuddy RECORD allow decisions for loaded jars: use only "
                  "when the user agreed. Without it, a changed PzCraft jar waits for the user's approval dialog.",
                  {"restart": {"type": "boolean"}, "allow_all": {"type": "boolean"}, "enter_game": {"type": "boolean"},
                   "enable_3d": {"type": "boolean"}, "save": {"type": "string", "description": "Explicit save directory name; omit for PZ's latest save"},
                   "game_mode": {"type": "string", "description": "Save's mode directory; default Apocalypse"},
                   "focus_fallback": {"type": "boolean", "description": "allow the desktop click / O key if the control API cannot start the game or 3D view; brings PZ to the front, so warn the user first"}}, []),
    "pz_stop": (t_stop, "Kill PZ and/or Minecraft.", {"which": {"type": "string", "enum": ["all", "pz", "minecraft"]}}, []),
    "pz_logs": (t_logs, "Tail/grep a log: console (PZ), minecraft, launch.",
                {"source": {"type": "string", "enum": ["console", "minecraft", "launch"]}, "pattern": {"type": "string"},
                 "lines": {"type": "integer"}}, []),
    "pz_vision": (t_vision, "Ask a cheap multimodal model (OpenRouter, default unbiased/pareto-26.10-preview) a question about the "
                  "current screenshot, so the answer costs few tokens. Needs the user's OPENROUTER_API_KEY.",
                  {"question": {"type": "string"}, "scale": {"type": "number"}}, ["question"]),
    "pz_log_digest": (t_digest, "Summarize problems in a log with a fast model (default inception/mercury-2.5). Needs OPENROUTER_API_KEY.",
                      {"source": {"type": "string"}, "lines": {"type": "integer"}}, []),
    "pz_decide": (t_decide, "Mercury decisions model (OpenRouter /api/alpha/decisions): given a state description, pick one of the "
                  "named choices. Returns choice + probabilities + confidence in a few hundred ms; for fast routing like 'which "
                  "screen is this' or 'what next'.",
                  {"state": {"type": "string"}, "instructions": {"type": "string"},
                   "choices": {"type": "object", "description": "name -> meaning", "additionalProperties": {"type": "string"}}},
                  ["state", "instructions", "choices"]),
    "pz_fast": (t_fast, "Ask the fast text model (OpenRouter) anything. Needs OPENROUTER_API_KEY.",
                {"prompt": {"type": "string"}}, ["prompt"]),
    "pz_playtest": (t_playtest, "Automated playtest: the decision model picks short actions (walk, jump, mine, place, fight, "
                    "hotbar, third person...) toward a goal while hard checks catch bugs (Steve/PZ desync, stuck in solid, "
                    "fell through floor, renderer failures, new log errors, actions without effect); each anomaly is triaged by "
                    "the decision model, and optionally a vision model judges screenshots. Moves the character: refuses while PZ has the "
                    "mouse captured unless force=true (only on a session you launched). Report in tools/pz-mcp/playtest-runs.",
                    {"goal": {"type": "string", "enum": ["explore", "collision", "building", "combat", "items"]},
                     "steps": {"type": "integer"}, "seed": {"type": "integer"},
                     "vision_every": {"type": "integer", "description": "send a screenshot to the OpenRouter vision model every N steps; default 0 = never (only with the user's OK to share game screenshots)"},
                     "unsafe": {"type": "boolean", "description": "no damage resistance: Steve can die"},
                     "force": {"type": "boolean"}}, []),
    "pz_respawn": (t_respawn, "After a death: continue with a new character (default spawn, unemployed); Minecraft re-places "
                   "Steve by itself.", {}, []),
}


def tool_list():
    out = []
    for name, (_f, desc, props, req) in TOOLS.items():
        out.append({"name": name, "description": desc,
                    "inputSchema": {"type": "object", "properties": props, "required": req}})
    return out


def handle(msg: dict):
    method = msg.get("method")
    mid = msg.get("id")
    if method == "initialize":
        want = (msg.get("params") or {}).get("protocolVersion")
        return {"protocolVersion": want if want in SUPPORTED else PROTOCOL, "capabilities": {"tools": {}},
                "serverInfo": {"name": "pz", "version": "0.1.0"}}
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": tool_list()}
    if method == "tools/call":
        p = msg.get("params") or {}
        name = p.get("name")
        if name not in TOOLS:
            raise KeyError(f"unknown tool {name}")
        try:
            content = TOOLS[name][0](p.get("arguments") or {})
            return {"content": content, "isError": False}
        except pz.PzError as e:
            return {"content": [text(f"error: {e}")], "isError": True}
        except Exception as e:  # noqa: BLE001
            return {"content": [text(f"error: {e}\n{traceback.format_exc()[-800:]}")], "isError": True}
    if mid is None:  # notification we do not care about
        return None
    raise KeyError(f"method not found: {method}")


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        mid = msg.get("id")
        try:
            res = handle(msg)
            if mid is not None:
                sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": mid, "result": res}) + "\n")
                sys.stdout.flush()
        except Exception as e:  # noqa: BLE001
            if mid is not None:
                sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": str(e)}}) + "\n")
                sys.stdout.flush()


if __name__ == "__main__":
    main()
