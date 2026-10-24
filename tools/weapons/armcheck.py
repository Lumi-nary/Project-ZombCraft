"""Checks where the arms are in the first-person view of a gun set's clips: no arm may show an end to the eye.

    python armcheck.py [--set DIR] [--before DIR] KIND [KIND ...] [--sheet KIND:CLIP:t,t,.. --out sheet.png]

The game draws Steve's own arms on a gun's arm bones, and an arm's ends are faces no first-person view should show: the
shoulder end is the open top of the arm, the hand end the flat of the fist. A clip that aims an arm back at the eye fills the
view with that end (release/BUGS.md B2: the rifles' left arm reached over the receiver from in front of the handle). For every
clip of each kind this samples the view every 0.05 s with geoview's camera (the arm bones are the placeholder boxes, which
match the game's arms) and prints the most end pixels any frame shows (192x108, both arms) and when. --before compares
with another build (build_own.py --out DIR). --sheet draws frames with the shoulder ends red, the hand ends yellow, the left
arm grey, the right arm blue and the action (bolt, handle) green.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np

import geoview
import pngio

HERE = Path(__file__).resolve().parent
SET = HERE.parents[1] / 'fabric-mod/src/main/resources/pzcraft/guns'
W, H = 192, 108
ARMS = ('leftarm', 'rightarm')
ACTION = ('action', 'action2', 'bolt', 'charging', 'slide')


def faces(model, pose, base):
    """Which (bone, face) wins each pixel (-1: none), seen from the first-person camera with back faces culled as in game."""
    vm = geoview.first_person_matrix(base)
    f = 1 / math.tan(math.radians(geoview.FOV) / 2)
    ids, depth, names = np.full((H, W), -1, int), np.full((H, W), np.inf), []
    for bone, p, _, face in model.quads(pose):
        cam = (vm @ np.c_[p, np.ones(len(p))].T).T[:, :3]
        if float(np.dot(np.cross(cam[1] - cam[0], cam[3] - cam[0]), cam[0])) <= 0:     # geoview's corners turn n inwards
            continue
        clipped = geoview.clip_near(cam, np.zeros((4, 2)))
        if clipped is None:
            continue
        q = clipped[0]
        z = -q[:, 2]
        sx, sy, inv = (q[:, 0] * f / (W / H) / z * .5 + .5) * W, (.5 - q[:, 1] * f / z * .5) * H, 1 / z
        k = len(names)
        names.append((bone, face))
        for tri in [(0, i, i + 1) for i in range(1, len(sx) - 1)]:
            xs, ys = sx[list(tri)], sy[list(tri)]
            x0, x1 = int(max(0, math.floor(xs.min()))), int(min(W - 1, math.ceil(xs.max())))
            y0, y1 = int(max(0, math.floor(ys.min()))), int(min(H - 1, math.ceil(ys.max())))
            den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if x0 > x1 or y0 > y1 or abs(den) < 1e-12:
                continue
            gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
            a = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
            b = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
            inside = (a >= -1e-4) & (b >= -1e-4) & (1 - a - b >= -1e-4)
            zz = np.where(inside, 1 / np.maximum(a * inv[tri[0]] + b * inv[tri[1]] + (1 - a - b) * inv[tri[2]], 1e-9), np.inf)
            region = depth[y0:y1 + 1, x0:x1 + 1]
            win = inside & (zz < region)
            region[win] = zz[win]
            ids[y0:y1 + 1, x0:x1 + 1][win] = k
    return ids, names


def ends_seen(model, pose, base):
    ids, names = faces(model, pose, base)
    return int(np.array([b in ARMS and f in ('up', 'down') for b, f in names] + [False])[ids].sum())


def load(set_dir, kind):
    root = Path(set_dir) / 'assets' / 'pzcraft'
    name = 'gun_' + kind
    model = geoview.Model(str(root / f'geckolib/models/item/{name}.geo.json'))
    base = json.loads((root / f'models/item/{name}_base.json').read_text())
    return model, base, str(root / f'geckolib/animations/item/{name}.animation.json')


def worst(set_dir, kind, step=.05):
    """{clip: (most end pixels in a frame, when)} for every clip of a kind."""
    model, base, anims = load(set_dir, kind)
    out = {}
    for full in sorted(json.loads(Path(anims).read_text())['animations']):
        clip = geoview.Clip(anims, full)
        best = (0, None)
        for i in range(int(clip.length / step + 1e-6) + 1):
            seen = ends_seen(model, clip.pose(i * step), base)
            if seen > best[0]:
                best = (seen, round(i * step, 2))
        out[full.rsplit('.', 1)[-1]] = best
    return out


def sheet(set_dir, kind, clip_name, times, out):
    model, base, anims = load(set_dir, kind)
    clip = geoview.Clip(anims, clip_name)
    images = []
    for t in times:
        ids, names = faces(model, clip.pose(t), base)
        img = np.zeros((H, W, 3), np.uint8)
        img[:] = (38, 40, 46)
        for k, (bone, face) in enumerate(names):
            if bone in ARMS:
                c = {'up': (230, 40, 40), 'down': (250, 220, 40)}.get(face, (150, 150, 160) if bone == 'leftarm' else (120, 170, 220))
            else:
                c = (60, 200, 90) if bone in ACTION else (90, 95, 110)
            img[ids == k] = c
        pngio.text(img, 3, 3, f'{clip_name} {t:.2f}')
        images.append(img)
    pngio.write(out, geoview.sheet(images, 4))
    print('saved', out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('kinds', nargs='*')
    ap.add_argument('--set', default=str(SET))
    ap.add_argument('--before', help='another build of the set to compare with')
    ap.add_argument('--sheet', help='KIND:CLIP:t,t,...')
    ap.add_argument('--out', default='armcheck.png')
    a = ap.parse_args()
    if a.sheet:
        kind, clip, times = a.sheet.split(':')
        sheet(a.set, kind, clip, [float(t) for t in times.split(',')], a.out)
    for kind in a.kinds:
        now, before = worst(a.set, kind), worst(a.before, kind) if a.before else {}
        print(f'== {kind}: most arm-end pixels in a frame of each clip (of {W}x{H}), and when')
        for name in sorted(set(now) | set(before)):
            if now.get(name, (0,))[0] or before.get(name, (0,))[0]:
                was = f'   before {before.get(name)}' if a.before else ''
                print(f'   {name:20s} {now.get(name)}{was}')


if __name__ == '__main__':
    main()
