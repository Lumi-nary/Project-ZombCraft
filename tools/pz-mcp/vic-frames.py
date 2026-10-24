"""Frames of a gun clip, held still, in the open tune session (tune-open.py TYPE):

    python vic-frames.py NAME CLIP t1,t2,... [fp|tp|front] [key=value ...]

Plays CLIP of the held gun (/pzgunanim, visual only), freezes it at each time with the debug.clipTime tuning key and takes a
picture; the pictures are joined into m9-views/NAME.png (one row). CLIP "none" just takes pictures of the gun at rest. The world
stays paused; debug.clipTime is removed again. Extra key=value pairs go into the tuning file like tshot.py's."""
import json
import sys
import time
from pathlib import Path

import numpy as np

import pzcore as p

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vic-guns'))
import pngio  # noqa: E402

HERE = Path(__file__).parent
args = sys.argv[1:]
name, clip, times = args[0], args[1], [float(x) for x in args[2].split(',')]
rest = args[3:]
cam = 0
if rest and rest[0] in ('fp', 'tp', 'front'):
    cam = {'fp': 0, 'tp': 1, 'front': 2}[rest.pop(0)]
tuning = Path.home() / '.pzcraft' / 'tuning.json'
d = json.loads(tuning.read_text() or '{}') if tuning.exists() else {}
for kv in rest:
    k, v = kv.split('=')
    d[k if '.' in k else 'pzgun.' + k] = float(v)
frames = []
try:
    p.cmd(f'camera {cam}')
    for t in times:
        d['debug.clipTime'] = t
        tuning.write_text(json.dumps(d, indent=1))
        time.sleep(.7)                      # the tuning file is re-read about twice a second
        if clip != 'none':
            p.cmd('mc pzgunanim ' + clip)
        time.sleep(.45)
        frames.append(pngio.read_bytes(p.screenshot(.5))[..., :3] if hasattr(pngio, 'read_bytes') else None)
finally:
    d.pop('debug.clipTime', None)
    tuning.write_text(json.dumps(d, indent=1))
    p.cmd('camera 0')
out = HERE / 'm9-views' / f'{name}.png'
pngio.write(out, np.concatenate(frames, 1))
print('saved', out, 'anim', json.dumps(p.state()['minecraftGui']['gunFeel'].get('animation')))
