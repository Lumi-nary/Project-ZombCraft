"""python tune_shot.py NAME key=value ... (bare keys get the pzmesh. prefix; reset=1 clears the file) : merge values into ~/.pzcraft/tuning.json, wait for the game to re-read it, screenshot into m9-views/NAME.png"""
import json, sys, time
from pathlib import Path
import pzcore as p
f = Path.home() / '.pzcraft' / 'tuning.json'
d = json.loads(f.read_text() or '{}') if f.exists() else {}
name = sys.argv[1]
if 'reset=1' in sys.argv: d = {}
for kv in [a for a in sys.argv[2:] if a != 'reset=1']:
    k, v = kv.split('='); d[k if '.' in k else 'pzmesh.' + k] = float(v)
f.write_text(json.dumps(d, indent=1)); time.sleep(1.2)
out = Path(__file__).parent / 'm9-views' / f'{name}.png'
out.write_bytes(p.screenshot(0.7)); print('saved', out)
