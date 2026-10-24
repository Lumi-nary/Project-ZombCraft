"""python tshot.py NAME [fp|tp|front] key=value ... : merge values into ~/.pzcraft/tuning.json (keys with a dot as they are, bare keys get
pzgun.), run the world for ~1.3 s (zombies are frozen every tick by the open tune session), screenshot into m9-views/NAME.png, pause again.
reset=1 clears the file first. Needs tune-open.py."""
import json, sys, time
from pathlib import Path
import pzcore as p
f = Path.home() / '.pzcraft' / 'tuning.json'
d = json.loads(f.read_text() or '{}') if f.exists() else {}
args = sys.argv[1:]
name = args.pop(0)
cam = 0
if args and args[0] in ('fp', 'tp', 'front'):
    cam = {'fp': 0, 'tp': 1, 'front': 2}[args.pop(0)]
paused = '--paused' in args
args = [a for a in args if a != '--paused']
if 'reset=1' in args: d = {}
for kv in [a for a in args if a != 'reset=1']:
    k, v = kv.split('='); d[k if '.' in k else 'pzgun.' + k] = float(v)
f.write_text(json.dumps(d, indent=1))
p.cmd(f'camera {cam}')
if not paused: p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
try:
    time.sleep(1.6)
    out = Path(__file__).parent / 'm9-views' / f'{name}.png'
    out.write_bytes(p.screenshot(0.7)); print('saved', out)
finally:
    if not paused: p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    p.cmd('camera 0')
