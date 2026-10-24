"""Our own gun effect images and shell casing models, drawn here from scratch (no other mod's files are read).

    python tools/effects/make_effects.py [--preview out.png]

Writes into fabric-mod/src/main/resources/assets/pzcraft/ the files the Fabric side draws guns' effects with, at the sizes and
layouts its code expects:
  textures/gui/crosshair.png                 39x39: centre dot in the middle 7x7, one arm in each outer 16 px strip
  textures/effect/flashes.png                1800x200: nine muzzle flash frames side by side, on black (drawn additive)
  textures/effect/tracers2.png               320x64: the streak across u 0..0.75 (v runs along it, 0 = the head end), the
                                             glowing head in u 0.75..1 (additive)
  textures/effect/blood.png                  256x256: four blood splashes, 2x2
  textures/effect/blood2.png                 256x256: one blood mist cloud
  textures/effect/dirt3x3.png                128x128: nine bits of debris, 3x3
  textures/entity/shell_casing/ar_shell.png  16x16: brass casing (sides, primer end, mouth end)
  geckolib/models/entity/shell_casing/pistol_shell.geo.json, ar_shell.geo.json: one box each, faces mapped onto that texture
Fixed random seeds: the same files every run. Needs numpy (tools/vic-guns/pngio.py writes the PNGs).
"""
import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools' / 'vic-guns'))
import pngio  # noqa: E402

OUT = ROOT / 'fabric-mod' / 'src' / 'main' / 'resources' / 'assets' / 'pzcraft'


def grid(h, w):
    y, x = np.mgrid[0:h, 0:w].astype(float)
    return x + .5, y + .5


def rgba(rgb, alpha):
    out = np.zeros(rgb.shape[:2] + (4,))
    out[..., :3], out[..., 3] = rgb, alpha
    return (np.clip(out, 0, 1) * 255 + .5).astype(np.uint8)


def ramp(t, stops):
    """Colour along t in 0..1 through (t, (r, g, b)) stops."""
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,))
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        k = np.clip((t - t0) / max(t1 - t0, 1e-6), 0, 1)[..., None]
        inside = ((t >= t0) & (t <= t1))[..., None]
        out = np.where(inside, np.array(c0) * (1 - k) + np.array(c1) * k, out)
    return out


FIRE = [(0, (0, 0, 0)), (.18, (.55, .16, .02)), (.45, (1, .55, .12)), (.75, (1, .86, .45)), (1, (1, 1, .92))]


def crosshair():
    """White lines with a dark rim: a dot in the middle, and a line in each of the four outer strips (the code draws three)."""
    n, mid = 39, 19
    white, rim = np.zeros((n, n)), np.zeros((n, n))
    white[mid - 1:mid + 2, mid - 1:mid + 2] = 1                       # 3x3 dot
    for a, b in ((2, 13), (26, 37)):
        white[mid, a:b] = 1                                           # left, right
        white[a:b, mid] = 1                                           # top, bottom
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            rim = np.maximum(rim, np.roll(np.roll(white, dy, 0), dx, 1))
    rgb = np.repeat(white[..., None], 3, axis=2)
    alpha = np.maximum(white * .95, rim * .55)
    return rgba(rgb, alpha)


def flashes(rnd):
    """Nine star-shaped muzzle flashes: a hot core, five to seven tapering prongs, a soft orange halo."""
    size, frames = 200, 9
    out = np.zeros((size, size * frames, 4), np.uint8)
    x, y = grid(size, size)
    dx, dy = x - size / 2, y - size / 2
    r = np.hypot(dx, dy)
    for f in range(frames):
        heat = np.exp(-(r / rnd.uniform(14, 20)) ** 2) * 1.1 + np.exp(-(r / rnd.uniform(38, 50)) ** 2) * .32
        start = rnd.uniform(0, 2 * math.pi)
        prongs = rnd.integers(5, 8)
        for i in range(prongs):
            angle = start + 2 * math.pi * i / prongs + rnd.uniform(-.25, .25)
            length, width = rnd.uniform(48, 96), rnd.uniform(5, 11)
            along = dx * math.cos(angle) + dy * math.sin(angle)
            across = -dx * math.sin(angle) + dy * math.cos(angle)
            taper = np.clip(1 - along / length, 0, 1)
            heat += np.where(along > 0, taper ** 1.4 * np.exp(-(across / (width * (.25 + .75 * taper))) ** 2), 0) * rnd.uniform(.7, 1)
        heat = np.clip(heat, 0, 1)
        rgb = ramp(heat, FIRE)
        out[:, f * size:(f + 1) * size] = rgba(rgb, rgb.max(axis=2))
    return out


def tracer():
    """The streak (bright thin core in a wider glow, fading from the head end to the tail) and its round glowing head."""
    h, w, split = 64, 320, 240
    x, y = grid(h, w)
    across = (x - split / 2) / (split / 2)
    head_end = 1 - y / h                                              # v 0 (row 0) is the end the bullet is at
    streak = (np.exp(-(across / .1) ** 2) + .25 * np.exp(-(across / .3) ** 2)) * (.25 + .75 * head_end ** 1.5)
    hx, hy = (x - (split + (w - split) / 2)) / ((w - split) / 2), (y - h / 2) / (h / 2)
    hr = np.hypot(hx, hy)
    head = np.exp(-(hr / .28) ** 2) * 1.1 + .35 * np.exp(-(hr / .7) ** 2)
    heat = np.clip(np.where(x < split, streak, head), 0, 1)
    rgb = ramp(heat, [(0, (0, 0, 0)), (.3, (.8, .4, .08)), (.7, (1, .82, .45)), (1, (1, .98, .88))])
    return rgba(rgb, rgb.max(axis=2))


def splash(rnd, size):
    """Blood drops for one frame: a clump of round drops near the middle and a few flecks thrown out."""
    x, y = grid(size, size)
    alpha, shade = np.zeros((size, size)), np.zeros((size, size))
    c = size / 2
    drops = [(c + rnd.normal(0, size * .08), c + rnd.normal(0, size * .08), rnd.uniform(size * .07, size * .16)) for _ in range(rnd.integers(4, 7))]
    for _ in range(rnd.integers(6, 11)):
        a, d = rnd.uniform(0, 2 * math.pi), rnd.uniform(size * .2, size * .42)
        drops.append((c + d * math.cos(a), c + d * math.sin(a), rnd.uniform(size * .015, size * .045)))
    for cx, cy, rad in drops:
        dist = np.hypot(x - cx, y - cy) / rad
        alpha = np.maximum(alpha, np.clip((1 - dist) * 6, 0, 1))
        shade = np.maximum(shade, np.clip(1 - dist, 0, 1))
    rgb = np.stack([.36 + .26 * shade, .015 + .03 * shade, .02 + .03 * shade], axis=2)
    return rgba(rgb, alpha)


def blood(rnd):
    out = np.zeros((256, 256, 4), np.uint8)
    for i in range(4):
        r, c = divmod(i, 2)
        out[r * 128:(r + 1) * 128, c * 128:(c + 1) * 128] = splash(rnd, 128)
    return out


def mist(rnd):
    """A soft red cloud made of overlapping round puffs, thinner towards its edge."""
    size = 256
    x, y = grid(size, size)
    density = np.zeros((size, size))
    for _ in range(22):
        cx, cy = size / 2 + rnd.normal(0, 34), size / 2 + rnd.normal(0, 34)
        rad = rnd.uniform(12, 34)
        density += np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * rad ** 2)) * rnd.uniform(.15, .35)
    density *= np.clip(1 - np.hypot(x - size / 2, y - size / 2) / (size * .48), 0, 1) * rnd.uniform(.85, 1.15, (size, size))
    alpha = np.clip(density, 0, .6)
    rgb = np.stack([.5 + .15 * alpha, .04 + 0 * alpha, .05 + 0 * alpha], axis=2)
    return rgba(rgb, alpha)


def debris(rnd):
    """Nine chips of dirt and stone: irregular shapes, mottled, darker at their rim."""
    size = 128
    out = np.zeros((size, size, 4), np.uint8)
    bounds = [round(i * size / 3) for i in range(4)]
    for i in range(9):
        r, c = divmod(i, 3)
        y0, y1, x0, x1 = bounds[r], bounds[r + 1], bounds[c], bounds[c + 1]
        h, w = y1 - y0, x1 - x0
        x, y = grid(h, w)
        dx, dy = x - w / 2, y - h / 2
        angle, dist = np.arctan2(dy, dx), np.hypot(dx, dy)
        corners = rnd.integers(5, 9)
        radii = rnd.uniform(.32, .46, corners) * min(w, h)
        phase = rnd.uniform(0, 2 * math.pi)
        k = ((angle - phase) % (2 * math.pi)) / (2 * math.pi) * corners
        lo = np.floor(k).astype(int) % corners
        edge = radii[lo] * (1 - (k - np.floor(k))) + radii[(lo + 1) % corners] * (k - np.floor(k))
        inside = dist < edge
        rim = np.clip((edge - dist) / 2.5, 0, 1)
        base = np.array(rnd.choice([(.42, .33, .24), (.36, .3, .25), (.48, .46, .43), (.32, .25, .18)]))
        noise = rnd.uniform(.82, 1.12, (h, w))
        rgb = base[None, None] * (noise * (.6 + .4 * rim))[..., None]
        out[y0:y1, x0:x1] = rgba(rgb, inside.astype(float))
    return out


def shell_texture():
    """16x16: brass sides in rows 0..7 (a bright band along the middle), the primer end at (0, 8), the open mouth at (8, 8)."""
    img = np.zeros((16, 16, 3))
    rows = np.linspace(0, 1, 8)
    band = .78 + .3 * np.exp(-((rows - .4) / .18) ** 2)
    img[:8] = np.array([.8, .6, .24])[None, None] * band[:, None, None]
    img[:8, ::4] *= .93                                               # faint lengthwise lines
    x, y = grid(8, 8)
    r = np.hypot(x - 4, y - 4)
    primer = np.where(r < 1.6, .55, np.where(r < 3.6, .78, .62))
    img[8:16, 0:8] = np.array([.8, .6, .24]) * primer[..., None]
    mouth = np.where(r < 2.6, .12, .72)
    img[8:16, 8:16] = np.array([.8, .6, .24]) * mouth[..., None]
    return rgba(img, np.ones((16, 16)))


def shell_geo(length):
    """One box along z (the case lies along the ejection's z), its sides on the brass rows, its ends on the primer and mouth."""
    side = {'uv': [0, 0], 'uv_size': [16, 8]}
    cube = {'origin': [-.45, -.45, -length / 2], 'size': [.9, .9, length],
            'uv': {'north': {'uv': [8, 8], 'uv_size': [8, 8]}, 'south': {'uv': [0, 8], 'uv_size': [8, 8]},
                   'east': side, 'west': side, 'up': side, 'down': side}}
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.pzcraft_shell', 'texture_width': 16, 'texture_height': 16,
                        'visible_bounds_width': 2, 'visible_bounds_height': 2, 'visible_bounds_offset': [0, 0, 0]},
        'bones': [{'name': 'main', 'pivot': [0, 0, 0], 'cubes': [cube]}]}]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--preview', help='also write a contact sheet of the textures here')
    a = ap.parse_args()
    rnd = np.random.default_rng(20261014)
    images = {
        'textures/gui/crosshair.png': crosshair(),
        'textures/effect/flashes.png': flashes(rnd),
        'textures/effect/tracers2.png': tracer(),
        'textures/effect/blood.png': blood(rnd),
        'textures/effect/blood2.png': mist(rnd),
        'textures/effect/dirt3x3.png': debris(rnd),
        'textures/entity/shell_casing/ar_shell.png': shell_texture(),
    }
    for rel, img in images.items():
        target = OUT / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        pngio.write(target, img)
        print(f'{img.shape[1]:>5}x{img.shape[0]:<4} {rel}')
    for name, length in (('pistol_shell', 2.1), ('ar_shell', 3.5)):
        target = OUT / 'geckolib' / 'models' / 'entity' / 'shell_casing' / f'{name}.geo.json'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(shell_geo(length), indent=1) + '\n')
        print(f'{"":10} {target.relative_to(OUT)}')
    if a.preview:
        # every texture on a grey board, scaled to 200 px high, with the additive ones also shown on black
        tiles = []
        for rel, img in images.items():
            scale = max(1, 200 // img.shape[0])
            big = np.repeat(np.repeat(img, scale, 0), scale, 1).astype(float) / 255
            if big.shape[0] > 200:
                step = big.shape[0] / 200
                idx = (np.arange(200) * step).astype(int)
                big = big[idx][:, (np.arange(int(big.shape[1] / step)) * step).astype(int)]
            board = np.full(big.shape[:2] + (3,), .45)
            if 'flashes' in rel or 'tracers' in rel:
                board[:] = 0
                comp = board + big[..., :3]
            else:
                comp = board * (1 - big[..., 3:]) + big[..., :3] * big[..., 3:]
            tiles.append(np.clip(comp, 0, 1))
        width = max(t.shape[1] for t in tiles)
        sheet = np.ones((sum(t.shape[0] + 8 for t in tiles), width, 3))
        y = 0
        for t in tiles:
            sheet[y:y + t.shape[0], :t.shape[1]] = t
            y += t.shape[0] + 8
        pngio.write(a.preview, (sheet * 255).astype(np.uint8))


if __name__ == '__main__':
    main()
