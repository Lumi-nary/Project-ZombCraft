"""PZ's own gun meshes as a measuring reference (never bundled: they are TIS's). Reads a DirectX .x text mesh from a PZ install
and draws its outlines from the side, the top and the back on a grid of model units, so a model of our own can be drawn to
PZ's sizes and shapes.

    python pzmesh.py M9_Pistol --out DIR [--length 29.5] [--muzzle -20] [--top 25.6] [--pz DIR]
    python pzmesh.py M9_Pistol --out DIR --length 29.5 --muzzle -20 --top 25.5 --compare gun_m9.geo.json

The mesh is in metres, its longest extent along the gun, the middle one its height. It is turned into the model's numbers
(.geo.json: x to the gun's left, y up, the muzzle towards -z): --length scales it so the gun is that many units (1/16 block)
long, --muzzle and --top put the muzzle's end and the top of the gun at those z and y. Which way PZ's mesh faces is found from
it: the end with the barrel (the thinner end along the length) is the muzzle; the grip hangs down. Pictures: the side (the muzzle
to the left), the top and the back on a grid of one unit (darker every 5, a line through 0); a text profile of the side's top
and bottom every half unit.
"""
import argparse
import math
import re
from pathlib import Path

import numpy as np

PZ = Path.home() / 'pz-dedicated' / 'media' / 'models_X' / 'weapons' / 'firearm'


def read(name, root=PZ):
    """The mesh's vertices (n x 3, metres, the frame's matrix applied) and its faces (lists of vertex numbers)."""
    text = (Path(root) / f'{name}.x').read_text(errors='replace')
    matrix = np.eye(4)
    m = re.search(r'FrameTransformMatrix\s*\{\s*([^;]+);;', text)
    if m:
        matrix = np.array([float(v) for v in m.group(1).split(',')]).reshape(4, 4)
    body = text[re.search(r'\n\s*Mesh\s+\S*\s*\{', text).end():]
    numbers = iter(re.findall(r'-?\d+(?:\.\d+)?(?:e-?\d+)?', body.split('MeshNormals')[0]))
    n = int(next(numbers))
    verts = np.array([[float(next(numbers)) for _ in range(3)] for _ in range(n)])
    faces = []
    for _ in range(int(next(numbers))):
        k = int(next(numbers))
        faces.append([int(next(numbers)) for _ in range(k)])
    verts = (np.c_[verts, np.ones(n)] @ matrix)[:, :3]
    return verts, faces


def axes(verts):
    """Which of the mesh's axes is the gun's length, height and width (longest, middle, shortest extent)."""
    ext = verts.max(0) - verts.min(0)
    order = list(np.argsort(-ext))
    return order[0], order[1], order[2]


def to_model(verts, length=None, muzzle=None, top=None):
    """The mesh in the model's numbers (see the module's text): (n x 3) x, y, z, and the scale used (units a metre)."""
    L, H, W = axes(verts)
    ext = verts.max(0) - verts.min(0)
    scale = length / ext[L] if length else 16.0
    along, up, across = verts[:, L] * scale, verts[:, H] * scale, verts[:, W] * scale
    lo, hi = along.min(), along.max()
    # the muzzle's end is the narrower one (the barrel or slide's front against the grip's depth); the grip hangs down from the
    # back half, so along the height the far side from the bore is where the back half reaches further
    near_lo = up[along < lo + .15 * (hi - lo)]
    near_hi = up[along > hi - .15 * (hi - lo)]
    muzzle_at_hi = np.ptp(near_hi) < np.ptp(near_lo)
    z = -along if muzzle_at_hi else along                 # the muzzle towards -z
    back = up[z > np.median(z)]
    grip_down = abs(back.min() - np.median(up)) > abs(back.max() - np.median(up))
    y = up if grip_down else -up
    if muzzle is not None:
        z = z - z.min() + muzzle
    if top is not None:
        y = y - y.max() + top
    return np.c_[across, y, z], scale


def raster(points2d, faces, w, h):
    """Fill the faces' projections into a w x h mask (points already in pixel coordinates)."""
    mask = np.zeros((h, w), bool)
    ys, xs = np.mgrid[0:h, 0:w]
    for f in faces:
        for i in range(1, len(f) - 1):
            tri = points2d[[f[0], f[i], f[i + 1]]]
            x0, y0 = np.floor(tri.min(0)).astype(int)
            x1, y1 = np.ceil(tri.max(0)).astype(int)
            x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, w - 1), min(y1, h - 1)
            if x1 < x0 or y1 < y0:
                continue
            px, py = xs[y0:y1 + 1, x0:x1 + 1] + .5, ys[y0:y1 + 1, x0:x1 + 1] + .5
            (ax, ay), (bx, by), (cx, cy) = tri
            d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(d) < 1e-12:
                continue
            l1 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / d
            l2 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / d
            inside = (l1 >= -1e-6) & (l2 >= -1e-6) & (1 - l1 - l2 >= -1e-6)
            mask[y0:y1 + 1, x0:x1 + 1] |= inside
    return mask


def picture(verts, faces, horizontal, vertical, scale, px_per_unit, flip_h=False):
    """The mesh seen along the third axis: an RGB image with a grid every unit (darker every 5), the silhouette grey, and
    the origin of the picture's numbers (lowest corner) so a pixel can be read back as model units."""
    h_vals, v_vals = verts[:, horizontal] * scale, verts[:, vertical] * scale
    if flip_h:
        h_vals = -h_vals
    lo_h, hi_h, lo_v, hi_v = math.floor(h_vals.min()) - 1, math.ceil(h_vals.max()) + 1, math.floor(v_vals.min()) - 1, math.ceil(v_vals.max()) + 1
    w, h = int((hi_h - lo_h) * px_per_unit), int((hi_v - lo_v) * px_per_unit)
    pts = np.c_[(h_vals - lo_h) * px_per_unit, (hi_v - v_vals) * px_per_unit]
    mask = raster(pts, faces, w, h)
    img = np.full((h, w, 3), 255, np.uint8)
    for u in range(int(hi_h - lo_h) + 1):
        n = u + lo_h
        img[:, min(w - 1, u * px_per_unit)] = (255, 120, 120) if n == 0 else (150, 150, 150) if n % 5 == 0 else (220, 220, 220)
    for v in range(int(hi_v - lo_v) + 1):
        n = hi_v - v
        img[min(h - 1, v * px_per_unit), :] = (255, 120, 120) if n == 0 else (150, 150, 150) if n % 5 == 0 else (220, 220, 220)
    img[mask] = (90, 90, 96)
    # the outline darker
    edge = mask & ~(np.roll(mask, 1, 0) & np.roll(mask, -1, 0) & np.roll(mask, 1, 1) & np.roll(mask, -1, 1))
    img[edge] = (20, 20, 24)
    return img, (lo_h, hi_v), mask


def profile(mask, origin, px_per_unit, step=.5):
    """The silhouette's top and bottom (in model units) at every `step` along its horizontal axis."""
    lo_h, hi_v = origin
    out = []
    w = mask.shape[1]
    u = 0.0
    while u * px_per_unit < w:
        col = mask[:, int(u * px_per_unit + px_per_unit / 2) if int(u * px_per_unit + px_per_unit / 2) < w else w - 1]
        rows = np.nonzero(col)[0]
        if len(rows):
            out.append((round(lo_h + u + step / 2, 2), round(hi_v - (rows.max() + 1) / px_per_unit, 2), round(hi_v - rows.min() / px_per_unit, 2)))
        u += step
    return out


def compare(verts, faces, geo, out, px=20, hide=('rightarm', 'leftarm', 'scope', 'muzzleflash')):
    """PZ's side outline against a model of ours (a .geo.json, at rest): grey where both are, red PZ's only, blue ours only.
    Returns how much of each is uncovered (picture pixels / px^2 = square units)."""
    import geoview
    pts = np.concatenate([q[1] for q in geoview.Model(geo).quads({}, hide)]) * 16
    lo_h = math.floor(min(verts[:, 2].min(), pts[:, 2].min())) - 1
    hi_h = math.ceil(max(verts[:, 2].max(), pts[:, 2].max())) + 1
    lo_v = math.floor(min(verts[:, 1].min(), pts[:, 1].min())) - 1
    hi_v = math.ceil(max(verts[:, 1].max(), pts[:, 1].max())) + 1
    w, h = int((hi_h - lo_h) * px), int((hi_v - lo_v) * px)

    def to_px(z, y):
        return np.c_[(z - lo_h) * px, (hi_v - y) * px]
    theirs = raster(to_px(verts[:, 2], verts[:, 1]), faces, w, h)
    ours = np.zeros((h, w), bool)
    for _, corners, _, _ in geoview.Model(geo).quads({}, hide):
        c = corners * 16
        ours |= raster(to_px(c[:, 2], c[:, 1]), [[0, 1, 2, 3]], w, h)
    img = np.full((h, w, 3), 255, np.uint8)
    for u in range(int(hi_h - lo_h) + 1):
        img[:, min(w - 1, u * px)] = (150, 150, 150) if (u + lo_h) % 5 == 0 else (225, 225, 225)
    for v in range(int(hi_v - lo_v) + 1):
        img[min(h - 1, v * px), :] = (150, 150, 150) if (hi_v - v) % 5 == 0 else (225, 225, 225)
    img[theirs & ours] = (120, 120, 126)
    img[theirs & ~ours] = (220, 60, 50)
    img[ours & ~theirs] = (60, 110, 230)
    import pngio
    Path(out).write_bytes(pngio.to_bytes(np.dstack([img, np.full((h, w), 255, np.uint8)])))
    return (theirs & ~ours).sum() / px ** 2, (ours & ~theirs).sum() / px ** 2


if __name__ == '__main__':
    import pngio
    ap = argparse.ArgumentParser()
    ap.add_argument('name')
    ap.add_argument('--out', default='.')
    ap.add_argument('--length', type=float, default=None, help='model units the gun is long (default 16 a metre)')
    ap.add_argument('--muzzle', type=float, default=None, help='z of the muzzle end')
    ap.add_argument('--top', type=float, default=None, help='y of the top of the gun')
    ap.add_argument('--px', type=int, default=24, help='picture pixels a model unit')
    ap.add_argument('--pz', default=str(PZ))
    ap.add_argument('--compare', help='a .geo.json of ours: write NAME-compare.png (its side outline over the mesh) and stop')
    a = ap.parse_args()
    raw, faces = read(a.name, a.pz)
    verts, scale = to_model(raw, a.length, a.muzzle, a.top)
    if a.compare:
        import json
        out = Path(a.out) / f'{a.name}-compare.png'
        missing, extra = compare(verts, faces, json.loads(Path(a.compare).read_text()), out, a.px)
        print(f'{out}: PZ only {missing:.1f}, ours only {extra:.1f} square units (grey both, red PZ only, blue ours only)')
        raise SystemExit
    ext = verts.max(0) - verts.min(0)
    print(f'{a.name}: {len(verts)} vertices, {len(faces)} faces; {scale:.2f} units a metre')
    print(f'  {ext[2]:.2f} long, {ext[1]:.2f} high, {ext[0]:.2f} wide; min {np.round(verts.min(0), 2)} max {np.round(verts.max(0), 2)}')
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for view, (hh, vv, flip) in (('side', (2, 1, False)), ('top', (2, 0, False)), ('back', (0, 1, True))):
        img, origin, mask = picture(verts, faces, hh, vv, 1.0, a.px, flip)
        (out / f'{a.name}-{view}.png').write_bytes(pngio.to_bytes(np.dstack([img, np.full(img.shape[:2], 255, np.uint8)])))
        print(f"  {view}: top left of the picture at ({origin[0]}, {origin[1]}), {img.shape[1]}x{img.shape[0]} px")
        if view == 'side':
            (out / f'{a.name}-side-profile.txt').write_text('z bottom top\n' + '\n'.join(f'{z} {lo} {hi}' for z, lo, hi in profile(mask, origin, a.px)))
    np.save(out / f'{a.name}-verts.npy', verts)
