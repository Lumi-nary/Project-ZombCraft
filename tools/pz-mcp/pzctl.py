"""Command-line front end for the PzCraft control API (same functions the MCP server exposes).

  python pzctl.py state
  python pzctl.py shot --scale 0.5 --out shot.png
  python pzctl.py input --hold FWD,SPRINT --ms 1500 --wait
  python pzctl.py input --look-yaw 90 --look-pitch 20          (relative degrees; add --abs for absolute)
  python pzctl.py input --hotbar 3 --hold USE --ms 150
  python pzctl.py cmd "tp 9 9"
  python pzctl.py lua "return getPlayer():getX()"
  python pzctl.py logs console "PzCraft" 20
  python pzctl.py respawn                     (after a death: new character with defaults; Steve follows)
  python pzctl.py playtest --goal collision --steps 25 --force   (decision-model playtester; see playtest.py)
  python pzctl.py launch --restart            (add --allow-all only with the user's OK; see pzcore.launch)
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pzcore as pz


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="pzctl")
    sub = ap.add_subparsers(dest="c", required=True)
    sub.add_parser("state")
    s = sub.add_parser("shot"); s.add_argument("--scale", type=float, default=0.5); s.add_argument("--out", default="pz-shot.png")
    i = sub.add_parser("input")
    i.add_argument("--hold", default=""); i.add_argument("--ms", type=int, default=200); i.add_argument("--wait", action="store_true")
    i.add_argument("--look-yaw", type=float); i.add_argument("--look-pitch", type=float); i.add_argument("--abs", action="store_true")
    i.add_argument("--hotbar", type=int); i.add_argument("--release", action="store_true")
    c = sub.add_parser("cmd"); c.add_argument("line")
    l = sub.add_parser("lua"); l.add_argument("code")
    m = sub.add_parser("menu"); m.add_argument("action", nargs="?", default="continue")
    g = sub.add_parser("logs"); g.add_argument("source", nargs="?", default="console"); g.add_argument("pattern", nargs="?"); g.add_argument("n", nargs="?", type=int, default=40)
    sub.add_parser("procs")
    sub.add_parser("respawn")
    pt = sub.add_parser("playtest"); pt.add_argument("--goal", default="explore"); pt.add_argument("--steps", type=int, default=20)
    pt.add_argument("--seed", type=int, default=1); pt.add_argument("--vision-every", type=int, default=0)
    pt.add_argument("--unsafe", action="store_true"); pt.add_argument("--force", action="store_true")
    k = sub.add_parser("kill"); k.add_argument("which", nargs="?", default="all"); k.add_argument("--force", action="store_true", help="stop even if a save was not confirmed")
    la = sub.add_parser("launch"); la.add_argument("--restart", action="store_true"); la.add_argument("--allow-all", action="store_true"); la.add_argument("--no-game", action="store_true")
    la.add_argument("--save"); la.add_argument("--game-mode", default="Apocalypse")
    la.add_argument("--focus-fallback", action="store_true", help="allow the desktop click / O key (brings PZ to the front)")
    sub.add_parser("mc-start", help="start only the hidden Minecraft half and wait until it is linked (PZ must be running)")
    v = sub.add_parser("vision"); v.add_argument("question"); v.add_argument("--scale", type=float, default=0.5)
    dc = sub.add_parser("decide"); dc.add_argument("state"); dc.add_argument("instructions"); dc.add_argument("choices", help='JSON {"name":"meaning"}')
    d = sub.add_parser("digest"); d.add_argument("source", nargs="?", default="console")
    a = ap.parse_args(argv)

    try:
        if a.c == "state":
            print(json.dumps(pz.state(), indent=2))
        elif a.c == "shot":
            Path(a.out).write_bytes(pz.screenshot(a.scale)); print(Path(a.out).resolve())
        elif a.c == "input":
            look = None
            if a.look_yaw is not None or a.look_pitch is not None:
                look = {"relative": not a.abs}
                if a.look_yaw is not None: look["yaw"] = a.look_yaw
                if a.look_pitch is not None: look["pitch"] = a.look_pitch
            hold = [h for h in a.hold.split(",") if h]
            print(json.dumps(pz.send_input(hold, a.ms, look, a.hotbar, a.wait, a.release)))
        elif a.c == "cmd":
            print(json.dumps(pz.cmd(a.line)))
        elif a.c == "lua":
            print(json.dumps(pz.lua(a.code)))
        elif a.c == "menu":
            print(json.dumps(pz.menu(a.action)))
        elif a.c == "logs":
            print(pz.logs(a.source, a.pattern, a.n))
        elif a.c == "procs":
            print(json.dumps(pz.processes()))
        elif a.c == "respawn":
            print(json.dumps(pz.respawn(), indent=2))
        elif a.c == "playtest":
            import playtest
            r = playtest.run(a.goal, a.steps, a.seed, a.vision_every, not a.unsafe, a.force)
            print(json.dumps(r["summary"], indent=2))
        elif a.c == "mc-start":
            r = pz.start_minecraft()
            r.pop("state", None)
            print(json.dumps(r))
        elif a.c == "kill":
            print(json.dumps(pz.kill(a.which, force=a.force)))
        elif a.c == "launch":
            print(json.dumps(pz.launch(a.restart, a.allow_all, not a.no_game, save=a.save, game_mode=a.game_mode,
                                    focus_fallback=a.focus_fallback), indent=2))
        elif a.c == "vision":
            print(pz.vision(a.question, a.scale))
        elif a.c == "decide":
            print(json.dumps(pz.decide(a.state, a.instructions, json.loads(a.choices)), indent=2))
        elif a.c == "digest":
            print(pz.log_digest(a.source))
    except pz.PzError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
