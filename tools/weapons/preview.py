"""One picture of a gun kind for a quick look (a phone is enough): its four sides, then moments of its clips in first person.
The arm placeholders are drawn as boxes (the game draws Steve's arms there).

    python preview.py m1911a1 --set DIR --out m1911a1-preview.png
"""
import argparse
import json
from pathlib import Path

import numpy as np

import geoview
import pngio

MOMENTS = [('idle', 0, 'at rest'), ('fire', .035, 'shot'), ('reload', .6, 'magazine out'), ('rack', .44, 'racked by hand'),
           ('inspect', 2.4, 'inspect'), ('idleempty', 0, 'empty')]


def preview(kind, root, out):
    entry = json.loads((root / 'manifest.json').read_text())['kinds'][kind]
    a = root / 'assets/pzcraft'
    model = geoview.Model(str(a / f'geckolib/models/item/gun_{kind}.geo.json'), str(a / f'textures/item/gun_{kind}.png'))
    anim, base = a / f'geckolib/animations/item/gun_{kind}.animation.json', a / f'models/item/gun_{kind}_base.json'
    placeholders = [b for b in entry['hide'] if b not in ('rightarm', 'leftarm')]
    sides = [geoview.render(model, {}, view, (600, 380), entry['hide'], None, None, label=view, gain=1.4, background=(150, 160, 175))
             for view in ('left', 'right', 'top', 'front')]
    shots = []
    for clip, t, label in MOMENTS:
        if clip in entry['clips']:
            pose = geoview.Clip(str(anim), clip).pose(min(t, entry['clips'][clip]))
            shots.append(geoview.render(model, pose, 'fp', (400, 250), placeholders, str(base), None, label=f'{clip} {t:g}s: {label}',
                                        gain=1.3, background=(112, 128, 148)))
    top = geoview.sheet(sides, 2)
    bottom = geoview.sheet(shots, 3)
    width = max(top.shape[1], bottom.shape[1])

    def pad(img):
        return np.pad(img, ((0, 0), (0, width - img.shape[1]), (0, 0)), constant_values=30)
    pngio.write(out, np.concatenate([pad(top), pad(bottom)]))
    print('saved', out)


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('kind')
    ap.add_argument('--set', required=True)
    ap.add_argument('--out')
    a = ap.parse_args()
    preview(a.kind, Path(a.set), Path(a.out or f'{a.kind}-preview.png'))
