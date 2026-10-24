"""A clip of the held gun as it plays, in the open tune session (tune-open.py TYPE):

    python vic-burst.py NAME CLIP [frames=6] [fp|tp|front] [hold=USE,...]

Starts CLIP (/pzgunanim, visual only; "none" for the gun at rest) and takes pictures as fast as the control API allows; they are
joined into m9-views/NAME.png with the time of each printed. Minecraft only animates while PZ runs, so the world runs for the
burst (the open tune session freezes every zombie on every tick) and is paused again. hold= keeps input buttons down meanwhile
(USE = aim down the sights)."""
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
name, clip = args[0], args[1]
count, cam, hold = 6, 0, None
for a in args[2:]:
    if a in ('fp', 'tp', 'front'):
        cam = {'fp': 0, 'tp': 1, 'front': 2}[a]
    elif a.startswith('hold='):
        hold = a[5:].split(',')
    else:
        count = int(a)
frames, times = [], []
assert (HERE / 'tune-session.json').exists(), 'open a tune session first (zombies frozen every tick)'
hp0 = p.state()['health']['steveHealth']
try:
    p.cmd(f'camera {cam}')
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    if hold:
        p.send_input(hold, 8000, wait=False)
        time.sleep(.6)
    else:
        time.sleep(.3)
    if clip != 'none':
        p.cmd('mc pzgunanim ' + clip)
    t0 = time.time()
    for _ in range(count):
        data = p.screenshot(.5)
        times.append(time.time() - t0)
        frames.append(pngio.read_bytes(data)[..., :3])
finally:
    if hold:
        p.send_input(release=True)
    time.sleep(float(__import__('os').environ.get('VIC_TAIL', '0.3')))
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    p.cmd('camera 0')
hp1 = p.state()['health']['steveHealth']
assert hp1 >= hp0 - 0.01, f'Steve lost health during the burst: {hp0} -> {hp1}'
out = HERE / 'm9-views' / f'{name}.png'
rows = [np.concatenate(frames[i:i + 3], 1) for i in range(0, len(frames) - len(frames) % 3 or len(frames), 3)] if len(frames) >= 3 else [np.concatenate(frames, 1)]
pngio.write(out, np.concatenate(rows, 0))
print('saved', out, 'times', [round(t, 2) for t in times], 'clip', p.state()['minecraftGui']['gunFeel'].get('animation', {}).get('lastClip'))
