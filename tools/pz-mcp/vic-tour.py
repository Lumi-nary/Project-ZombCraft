"""Every gun in the hand, one after another: python vic-tour.py NAME TYPE [TYPE ...] [--clips fire,rack] [--tp]

For each PZ firearm type: opens a tune session with a temporary gun of that type (tune-open.py: borrowed slot 8, zombies frozen
every tick), takes a picture at the hip and one aimed (and one per clip in --clips, about a third of the way in; --tp adds a
front third-person view), closes the session (tune-close.py: fixture removed, slot/tuning/zombies restored). One row per gun in
m9-views/NAME.png. Stops at the first gun whose session does not open or close cleanly."""
import json
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

import pzcore as p

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'vic-guns'))
import pngio  # noqa: E402

HERE = Path(__file__).parent
args = sys.argv[1:]
name = args.pop(0)
clips, tp = [], False
types = []
while args:
    a = args.pop(0)
    if a == '--clips':
        clips = args.pop(0).split(',')
    elif a == '--tp':
        tp = True
    else:
        types.append(a)


def grab(scale=.5):
    return pngio.read_bytes(p.screenshot(scale))[..., :3]


def run_world(on):
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(%d)' % (1 if on else 0))


rows = []
hp0 = p.state()['health']['steveHealth']
for t in types:
    r = subprocess.run([sys.executable, str(HERE / 'tune-open.py'), t], capture_output=True, text=True, cwd=HERE)
    if r.returncode:
        print(t, 'did not open:', r.stdout[-400:], r.stderr[-800:])
        break
    frames = []
    try:
        run_world(True)
        time.sleep(1.4)                       # the draw clip
        frames.append(grab())
        p.send_input(['USE'], 6000, wait=False)
        time.sleep(.8)
        frames.append(grab())
        p.send_input(release=True)
        time.sleep(.5)
        for c in clips:
            a = p.state()['minecraftGui']['gunFeel'].get('animation', {})
            p.cmd('mc pzgunanim ' + c)
            time.sleep(.3)
            frames.append(grab())
            time.sleep(1.2)
        if tp:
            p.cmd('camera 2')
            time.sleep(.6)
            frames.append(grab())
            p.cmd('camera 0')
        kind = p.state()['minecraftGui']['gunFeel'].get('animation', {}).get('kind')
    finally:
        p.send_input(release=True)
        run_world(False)
    r = subprocess.run([sys.executable, str(HERE / 'tune-close.py')], capture_output=True, text=True, cwd=HERE)
    print(t, 'kind', kind, '|', r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.stderr[-300:])
    row = np.concatenate(frames, 1)
    pngio.text(row, 12, 12, t.replace('Base.', '') + ' ' + str(kind), scale=4)
    rows.append(row)
    if r.returncode or 'errors []' not in r.stdout:
        print('session did not close cleanly; stopping')
        break
if rows:
    width = max(r.shape[1] for r in rows)
    rows = [np.pad(r, ((0, 0), (0, width - r.shape[1]), (0, 0))) for r in rows]
    out = HERE / 'm9-views' / f'{name}.png'
    pngio.write(out, np.concatenate(rows, 0))
    print('saved', out)
hp1 = p.state()['health']['steveHealth']
print('hp', hp0, '->', hp1, '| paused', p.state()['paused'])
assert hp1 >= hp0 - 0.01
