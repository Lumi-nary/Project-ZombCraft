"""Offline preview of a GeckoLib (Bedrock geometry) gun model and its animation clips: no game needed.

    python geoview.py GEO.json [--anim ANIM.json --clip NAME] [--tex PNG] [--view left|right|top|front|fp] [--frames N | --times a,b,c]
                      [--hide bone,bone] [--base BASE.json] [--out sheet.png] [--size WxH]

The transforms are GeckoLib 5's own (studied from its loader): pivots and cube origins mirrored in X, rotations (-x, -y, z) applied
Z then Y then X about the pivot, animated position (-x, y, z) / 16, scale about the pivot. Keyframes are interpolated linearly
(catmullrom keys are shown linear), which is enough to read a clip's timing and to author new ones.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np

import pngio


def rot_zyx(rx, ry, rz):
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    X = np.array([[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]], float)
    Y = np.array([[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]], float)
    Z = np.array([[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], float)
    return Z @ Y @ X


def translate(x, y, z):
    m = np.eye(4)
    m[:3, 3] = (x, y, z)
    return m


def scale(x, y, z):
    return np.diag([x, y, z, 1.0])


class Model:
    def __init__(self, geo_path, texture_path=None):
        geo = (geo_path if isinstance(geo_path, dict) else json.loads(Path(geo_path).read_text()))['minecraft:geometry'][0]
        self.desc = geo['description']
        self.tw, self.th = self.desc.get('texture_width', 64), self.desc.get('texture_height', 64)
        self.bones = geo['bones']
        self.by_name = {b['name']: b for b in self.bones}
        self.children = {}
        for b in self.bones:
            self.children.setdefault(b.get('parent'), []).append(b['name'])
        self.texture = None
        if texture_path and Path(texture_path).exists():
            self.texture = pngio.read(texture_path)

    def bone_matrix(self, bone, pose):
        px, py, pz = bone.get('pivot', [0, 0, 0])
        pivot = (-px / 16, py / 16, pz / 16)
        rx, ry, rz = bone.get('rotation', [0, 0, 0])
        a = pose.get(bone['name'], {})
        ar, ap, asc = a.get('rotation', (0, 0, 0)), a.get('position', (0, 0, 0)), a.get('scale', (1, 1, 1))
        m = translate(-ap[0] / 16, ap[1] / 16, ap[2] / 16) @ translate(*pivot)
        m = m @ rot_zyx(math.radians(-rx - ar[0]), math.radians(-ry - ar[1]), math.radians(rz + ar[2]))
        m = m @ scale(*asc) @ translate(-pivot[0], -pivot[1], -pivot[2])
        return m

    def world_matrices(self, pose):
        out = {}

        def walk(name, parent):
            m = parent @ self.bone_matrix(self.by_name[name], pose)
            out[name] = m
            for c in self.children.get(name, []):
                walk(c, m)
        for root in self.children.get(None, []):
            walk(root, np.eye(4))
        return out

    def quads(self, pose, hide=()):
        """Yields (bone, 4x3 corners in model space [blocks], uv rect (u0, v0, u1, v1) in texels, face)."""
        mats = self.world_matrices(pose)
        hidden = set()

        def mark(name):
            hidden.add(name)
            for c in self.children.get(name, []):
                mark(c)
        for h in hide:
            if h in self.by_name:
                mark(h)
        for b in self.bones:
            if b['name'] in hidden:
                continue
            a = pose.get(b['name'], {})
            sc = a.get('scale', (1, 1, 1))
            if abs(sc[0]) < 1e-6 and abs(sc[1]) < 1e-6 and abs(sc[2]) < 1e-6:
                continue
            bm = mats[b['name']]
            for c in b.get('cubes', []):
                ox, oy, oz = c['origin']
                sx, sy, sz = c['size']
                inf = c.get('inflate', 0)
                x0, y0, z0 = -(ox + sx) / 16 - inf / 16, oy / 16 - inf / 16, oz / 16 - inf / 16
                x1, y1, z1 = -ox / 16 + inf / 16, (oy + sy) / 16 + inf / 16, (oz + sz) / 16 + inf / 16
                m = bm
                if 'rotation' in c:
                    cpx, cpy, cpz = c.get('pivot', [0, 0, 0])
                    cp = (-cpx / 16, cpy / 16, cpz / 16)
                    crx, cry, crz = c['rotation']
                    m = m @ translate(*cp) @ rot_zyx(math.radians(-crx), math.radians(-cry), math.radians(crz)) @ translate(-cp[0], -cp[1], -cp[2])
                uv = c.get('uv', [0, 0])
                if isinstance(uv, dict):
                    faces_uv = {k: (v['uv'][0], v['uv'][1], v['uv'][0] + v['uv_size'][0], v['uv'][1] + v['uv_size'][1]) for k, v in uv.items()}
                else:
                    u, v = uv
                    w, h, d = math.floor(sx), math.floor(sy), math.floor(sz)
                    faces_uv = {'up': (u + d, v, u + d + w, v + d), 'down': (u + d + w, v, u + d + w + w, v + d),
                                'east': (u, v + d, u + d, v + d + h), 'north': (u + d, v + d, u + d + w, v + d + h),
                                'west': (u + d + w, v + d, u + d + w + d, v + d + h),
                                'south': (u + d + w + d, v + d, u + d + w + d + w, v + d + h)}
                # corners ordered so that uv (u0,v0) (u1,v0) (u1,v1) (u0,v1) go round the face
                faces = {'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
                         'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
                         'east': [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
                         'west': [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
                         'up': [(x1, y1, z1), (x0, y1, z1), (x0, y1, z0), (x1, y1, z0)],
                         'down': [(x1, y0, z0), (x0, y0, z0), (x0, y0, z1), (x1, y0, z1)]}
                for face, corners in faces.items():
                    if face not in faces_uv:
                        continue
                    p = (m @ np.array([[*k, 1.0] for k in corners]).T).T[:, :3]
                    yield b['name'], p, faces_uv[face], face


class Clip:
    def __init__(self, anim_path, name):
        anims = json.loads(Path(anim_path).read_text())['animations']
        key = name if name in anims else 'animation.model.' + name
        self.data = anims[key]
        self.length = float(self.data.get('animation_length', 0) or 0)
        self.name = name

    @staticmethod
    def _num(x):
        if isinstance(x, (int, float)):
            return float(x)
        try:
            return float(x)
        except (TypeError, ValueError):
            return 0.0

    @classmethod
    def _vec(cls, v, post=True):
        if isinstance(v, dict):
            if 'post' in v or 'pre' in v:
                v = v.get('post' if post else 'pre', v.get('post', v.get('pre')))
            if isinstance(v, dict):
                v = v.get('vector', [0, 0, 0])
        if isinstance(v, (int, float, str)):
            n = cls._num(v)
            return (n, n, n)
        return tuple(cls._num(x) for x in v)

    def channel(self, ch, t, default):
        if ch is None:
            return default
        if not isinstance(ch, dict) or 'vector' in ch or 'post' in ch or 'pre' in ch:
            return self._vec(ch)
        keys = sorted(((float(k), v) for k, v in ch.items()), key=lambda kv: kv[0])
        if not keys:
            return default
        if t <= keys[0][0]:
            return self._vec(keys[0][1], post=False)
        if t >= keys[-1][0]:
            return self._vec(keys[-1][1])
        for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
            if t0 <= t <= t1:
                a, b = self._vec(v0, post=True), self._vec(v1, post=False)
                f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
                return tuple(a[i] + (b[i] - a[i]) * f for i in range(3))
        return default

    def pose(self, t):
        out = {}
        for bone, ch in self.data.get('bones', {}).items():
            out[bone] = {'rotation': self.channel(ch.get('rotation'), t, (0, 0, 0)),
                         'position': self.channel(ch.get('position'), t, (0, 0, 0)),
                         'scale': self.channel(ch.get('scale'), t, (1, 1, 1))}
        return out


PALETTE = {}


def bone_color(name):
    if name not in PALETTE:
        h = (hash(name) % 360) / 360.0
        import colorsys
        r, g, b = colorsys.hsv_to_rgb(h, .45, .85)
        PALETTE[name] = (int(r * 255), int(g * 255), int(b * 255))
    return PALETTE[name]


FOV = 70        # the hands are drawn with Minecraft's fixed field of view, whatever the world is shown with


def first_person_matrix(base_path, hand='right'):
    """Vanilla's first-person hand transform followed by the item's display transform and GeckoLib's item offset. The display
    transforms come from the item's base model file, or from that file's content given as an object."""
    display = {}
    if isinstance(base_path, dict):
        display = base_path.get('display', {}).get('firstperson_%shand' % hand, {})
    elif base_path and Path(base_path).exists():
        display = json.loads(Path(base_path).read_text()).get('display', {}).get('firstperson_%shand' % hand, {})
    r = display.get('rotation', [0, 0, 0])
    t = display.get('translation', [0, 0, 0])
    s = display.get('scale', [1, 1, 1])
    side = 1 if hand == 'right' else -1
    m = translate(side * .56, -.52, -.72)
    m = m @ translate(t[0] / 16, t[1] / 16, t[2] / 16)
    cx, sx = math.cos(math.radians(r[0])), math.sin(math.radians(r[0]))
    cy, sy = math.cos(math.radians(r[1])), math.sin(math.radians(r[1]))
    cz, sz = math.cos(math.radians(r[2])), math.sin(math.radians(r[2]))
    X = np.array([[1, 0, 0, 0], [0, cx, -sx, 0], [0, sx, cx, 0], [0, 0, 0, 1]], float)
    Y = np.array([[cy, 0, sy, 0], [0, 1, 0, 0], [-sy, 0, cy, 0], [0, 0, 0, 1]], float)
    Z = np.array([[cz, -sz, 0, 0], [sz, cz, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], float)
    m = m @ X @ Y @ Z @ scale(*s)
    return m @ translate(0, .01, 0)


BACKGROUND = (38, 40, 46)
NEAR = .05


def clip_near(q, uv):
    """A face (camera space, with its texture corners) cut off at the near plane; None when all of it is behind that."""
    out_q, out_uv = [], []
    for i in range(len(q)):
        a, b, ua, ub = q[i], q[(i + 1) % len(q)], uv[i], uv[(i + 1) % len(q)]
        da, db = -a[2] - NEAR, -b[2] - NEAR
        if da >= 0:
            out_q.append(a)
            out_uv.append(ua)
        if (da >= 0) != (db >= 0):
            k = da / (da - db)
            out_q.append(a + (b - a) * k)
            out_uv.append(ua + (ub - ua) * k)
    return (np.array(out_q), np.array(out_uv)) if len(out_q) >= 3 else None


def render(model, pose, view='left', size=(480, 300), hide=(), base=None, bounds=None, shade=True, label=None, grid=0, gain=1.0,
           background=BACKGROUND):
    w, h = size
    color = np.zeros((h, w, 3), np.uint8)
    color[:] = background
    depth = np.full((h, w), np.inf)
    quads = list(model.quads(pose, hide))
    if not quads:
        return color
    if view == 'fp':
        vm = first_person_matrix(base)
        f = 1 / math.tan(math.radians(FOV) / 2)
        aspect = w / h

        def project(q):         # camera-space points in front of the near plane
            z = -q[:, 2]
            sx = (q[:, 0] * f / aspect / z * .5 + .5) * w
            sy = (.5 - q[:, 1] * f / z * .5) * h
            return sx, sy, z
    else:
        if bounds is None:
            allp = np.concatenate([q[1] for q in quads])
            lo, hi = allp.min(0), allp.max(0)
        else:
            lo, hi = np.array(bounds[0], float), np.array(bounds[1], float)
        axes = {'left': (2, 1, 0, 1, 1), 'right': (2, 1, 0, -1, -1), 'top': (2, 0, 1, 1, -1), 'front': (0, 1, 2, 1, -1), 'back': (0, 1, 2, -1, 1)}[view]
        ax, ay, ad, flipx, dsign = axes
        span = max((hi[ax] - lo[ax]) / (w - 20), (hi[ay] - lo[ay]) / (h - 20), 1e-6)
        cx_, cy_ = (lo[ax] + hi[ax]) / 2, (lo[ay] + hi[ay]) / 2

        def project(p):
            sx = w / 2 + flipx * (p[:, ax] - cx_) / span
            sy = h / 2 - (p[:, ay] - cy_) / span
            return sx, sy, dsign * p[:, ad] + 1000
    light = np.array([.35, .8, .5])
    light = light / np.linalg.norm(light)
    tex = model.texture
    for bone, p, uv, face in quads:
        n = np.cross(p[1] - p[0], p[3] - p[0])
        ln = np.linalg.norm(n)
        lum = 1.0
        if shade and ln > 1e-12:
            lum = .55 + .45 * abs(float(np.dot(n / ln, light)))
        u0, v0, u1, v1 = uv
        uvs = np.array([(u0, v0), (u1, v0), (u1, v1), (u0, v1)], float)
        if view == 'fp':
            # A face that reaches towards the eye (an arm) spans a large range of depth: its depth and texture have to be
            # interpolated in 1/depth, and what is behind the eye cut away, or it is drawn behind things it really covers.
            clipped = clip_near((vm @ np.c_[p, np.ones(len(p))].T).T[:, :3], uvs)
            if clipped is None:
                continue
            sx, sy, sz = project(clipped[0])
            uvs, far = clipped[1], 1 / sz
        else:
            sx, sy, sz = project(p)
            far = None
        for tri in [(0, i, i + 1) for i in range(1, len(sx) - 1)]:
            xs, ys, zs = sx[list(tri)], sy[list(tri)], sz[list(tri)]
            minx, maxx = int(max(0, math.floor(xs.min()))), int(min(w - 1, math.ceil(xs.max())))
            miny, maxy = int(max(0, math.floor(ys.min()))), int(min(h - 1, math.ceil(ys.max())))
            if minx > maxx or miny > maxy:
                continue
            den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(den) < 1e-9:
                continue
            gx, gy = np.meshgrid(np.arange(minx, maxx + 1) + .5, np.arange(miny, maxy + 1) + .5)
            a = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
            b = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
            c = 1 - a - b
            inside = (a >= -1e-4) & (b >= -1e-4) & (c >= -1e-4)
            if not inside.any():
                continue
            if far is not None:
                a, b, c = a * far[tri[0]], b * far[tri[1]], c * far[tri[2]]
                total = np.where(inside, a + b + c, 1.0)
                a, b, c = a / total, b / total, c / total
            z = a * zs[0] + b * zs[1] + c * zs[2]
            region = depth[miny:maxy + 1, minx:maxx + 1]
            win = inside & (z < region)
            if not win.any():
                continue
            if tex is not None:
                tu = a * uvs[tri[0], 0] + b * uvs[tri[1], 0] + c * uvs[tri[2], 0]
                tv = a * uvs[tri[0], 1] + b * uvs[tri[1], 1] + c * uvs[tri[2], 1]
                ix = np.clip((tu * tex.shape[1] / model.tw).astype(int), 0, tex.shape[1] - 1)
                iy = np.clip((tv * tex.shape[0] / model.th).astype(int), 0, tex.shape[0] - 1)
                texel = tex[iy, ix]
                win = win & (texel[..., 3] > 20)
                rgb = (texel[..., :3] * lum).astype(np.uint8)
            else:
                rgb = np.empty(a.shape + (3,), np.uint8)
                rgb[:] = tuple(int(k * lum) for k in bone_color(bone))
            region[win] = z[win]
            color[miny:maxy + 1, minx:maxx + 1][win] = rgb[win]
    if gain != 1.0:
        lit = depth < np.inf
        color[lit] = np.clip(color[lit].astype(float) * gain + 18, 0, 255).astype(np.uint8)
    if grid and view != 'fp':
        # model units (1/16 block, Bedrock axes: the numbers of the .geo.json) every `grid` units
        for axis, horizontal in ((ax, True), (ay, False)):
            lo_u, hi_u = lo[axis] * 16, hi[axis] * 16
            k = math.ceil(min(lo_u, hi_u) / grid) * grid
            while k <= max(lo_u, hi_u):
                if horizontal:
                    x = int(round(w / 2 + flipx * (k / 16 - cx_) / span))
                    if 0 <= x < w:
                        color[:, x] = (color[:, x] * .6 + np.array([90, 110, 150]) * .4).astype(np.uint8)
                        pngio.text(color, x + 2, h - 12, str(int(-k if axis == 0 else k)), (160, 200, 255), 1)
                else:
                    y = int(round(h / 2 - (k / 16 - cy_) / span))
                    if 0 <= y < h:
                        color[y, :] = (color[y, :] * .6 + np.array([90, 110, 150]) * .4).astype(np.uint8)
                        pngio.text(color, 2, y + 2, str(int(k)), (160, 200, 255), 1)
                k += grid
    if label:
        pngio.text(color, 6, 5, label)
    return color


def sheet(images, columns=4):
    h, w = images[0].shape[:2]
    columns = min(columns, len(images))
    rows = (len(images) + columns - 1) // columns
    out = np.zeros((h * rows, w * columns, 3), np.uint8)
    for i, im in enumerate(images):
        out[(i // columns) * h:(i // columns + 1) * h, (i % columns) * w:(i % columns + 1) * w] = im
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('geo')
    ap.add_argument('--anim')
    ap.add_argument('--clip')
    ap.add_argument('--tex')
    ap.add_argument('--base')
    ap.add_argument('--view', default='left')
    ap.add_argument('--frames', type=int, default=8)
    ap.add_argument('--times')
    ap.add_argument('--hide', default='')
    ap.add_argument('--out', default='geoview.png')
    ap.add_argument('--size', default='480x300')
    ap.add_argument('--columns', type=int, default=4)
    ap.add_argument('--flat', action='store_true', help='one colour per bone instead of the texture')
    ap.add_argument('--grid', type=int, default=0, help='grid lines every N model units (1/16 block), labelled with .geo.json numbers')
    ap.add_argument('--bounds', help='x0,y0,z0,x1,y1,z1 in model units (.geo.json numbers) for the orthographic views')
    ap.add_argument('--gain', type=float, default=1.0, help='brighten dark textures')
    ap.add_argument('--bg', default=','.join(str(c) for c in BACKGROUND), help='background colour r,g,b (a light one shows near-black parts)')
    a = ap.parse_args()
    model = Model(a.geo, None if a.flat else a.tex)
    size = tuple(int(x) for x in a.size.split('x'))
    hide = [x for x in a.hide.split(',') if x]
    images = []
    background = tuple(int(c) for c in a.bg.split(','))
    user_bounds = None
    if a.bounds:
        b = [float(x) for x in a.bounds.split(',')]
        # .geo.json x is mirrored in model space
        user_bounds = (np.array([-max(b[0], b[3]), min(b[1], b[4]), min(b[2], b[5])]) / 16, np.array([-min(b[0], b[3]), max(b[1], b[4]), max(b[2], b[5])]) / 16)
    if a.anim and a.clip:
        clip = Clip(a.anim, a.clip)
        times = [float(x) for x in a.times.split(',')] if a.times else [clip.length * i / max(1, a.frames - 1) for i in range(a.frames)]
        bounds = user_bounds
        if a.view != 'fp' and bounds is None:
            pts = np.concatenate([q[1] for t in times for q in model.quads(clip.pose(t), hide)])
            bounds = (pts.min(0), pts.max(0))
        for view in a.view.split(','):
            for t in times:
                images.append(render(model, clip.pose(t), view, size, hide, a.base, bounds, label=f'{a.clip} {t:.3f}s {view}', grid=a.grid, gain=a.gain, background=background))
    else:
        for view in a.view.split(','):
            images.append(render(model, {}, view, size, hide, a.base, user_bounds, label=view, grid=a.grid, gain=a.gain, background=background))
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    pngio.write(out, sheet(images, a.columns))
    print('saved', out, len(images), 'frames')


if __name__ == '__main__':
    main()
