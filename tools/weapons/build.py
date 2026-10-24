"""Shared geometry and animation helpers for Project-ZombCraft's original weapons.

This module processes the authored models and clips passed by build_own.py.
It has no asset importer and reads no other mod's files.
"""
import argparse
import copy
import hashlib
import json
import math
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[1]
EPS = 1e-4
PREFIX = 'animation.model.'
PARTICLE = 'minecraft:item/iron_sword'   # the base models' particle texture (as PzWeaponModels' pzmodel_base)


# ---------------------------------------------------------------- clips

def _vec(v, post=True):
    """A keyframe value as three floats (pre/post keys give the side asked for)."""
    if isinstance(v, dict):
        if 'post' in v or 'pre' in v:
            v = v.get('post' if post else 'pre', v.get('post', v.get('pre')))
        if isinstance(v, dict):
            v = v.get('vector', [0, 0, 0])
    if isinstance(v, (int, float, str)):
        try:
            n = float(v)
        except ValueError:
            n = 0.0
        return [n, n, n]
    out = []
    for x in v:
        try:
            out.append(float(x))
        except (TypeError, ValueError):
            out.append(0.0)
    return out


def _keyed(channel):
    return isinstance(channel, dict) and 'vector' not in channel and 'post' not in channel and 'pre' not in channel


def sample(channel, t, rest):
    if channel is None:
        return list(rest)
    if not _keyed(channel):
        return _vec(channel)
    keys = sorted(((float(k), v) for k, v in channel.items()), key=lambda kv: kv[0])
    if t <= keys[0][0]:
        return _vec(keys[0][1], post=False)
    if t >= keys[-1][0]:
        return _vec(keys[-1][1])
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            a, b = _vec(v0), _vec(v1, post=False)
            f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return [a[i] + (b[i] - a[i]) * f for i in range(3)]
    return list(rest)


REST = {'rotation': [0, 0, 0], 'position': [0, 0, 0], 'scale': [1, 1, 1]}


def fmt(t):
    return ('%.4f' % t).rstrip('0').rstrip('.') if t else '0.0'


def r4(v):
    return [round(x, 4) for x in v]


def window(clip, t0, t1, drop=(), only=None, loop=False):
    """The part of a clip between two times as a clip of its own (first and last keys sampled at the cut)."""
    out = {'animation_length': round(t1 - t0, 4), 'bones': {}}
    if loop:
        out['loop'] = True
    for bone, channels in clip.get('bones', {}).items():
        if bone in drop or (only is not None and bone not in only):
            continue
        nb = {}
        for name, channel in channels.items():
            if not _keyed(channel):
                nb[name] = copy.deepcopy(channel)
                continue
            keys = {fmt(0): r4(sample(channel, t0, REST[name]))}
            for k, v in channel.items():
                kt = float(k)
                if t0 + EPS < kt < t1 - EPS:
                    keys[fmt(kt - t0)] = copy.deepcopy(v)
            keys[fmt(t1 - t0)] = r4(sample(channel, t1, REST[name]))
            nb[name] = keys
        if nb:
            out['bones'][bone] = nb
    return out


def concat(*clips):
    """Clips one after another; a bone a part does not animate is at rest during that part, as it is when the part plays alone."""
    total, offsets = 0.0, []
    for c in clips:
        offsets.append(total)
        total += float(c['animation_length'])
    out = {'animation_length': round(total, 4), 'bones': {}}
    bones = []
    for c in clips:
        for b in c.get('bones', {}):
            if b not in bones:
                bones.append(b)
    for bone in bones:
        nb = {}
        names = []
        for c in clips:
            for n in c.get('bones', {}).get(bone, {}):
                if n not in names:
                    names.append(n)
        for name in names:
            keys = {}
            for c, off in zip(clips, offsets):
                length = float(c['animation_length'])
                channel = c.get('bones', {}).get(bone, {}).get(name)
                start, end = off + (EPS if off > 0 else 0), off + length
                if channel is None or not _keyed(channel):
                    v = r4(sample(channel, 0, REST[name]))
                    keys[fmt(start)] = v
                    keys[fmt(end)] = v
                    continue
                keys[fmt(start)] = r4(sample(channel, 0, REST[name]))
                for k, v in channel.items():
                    kt = float(k)
                    if EPS < kt < length - EPS:
                        keys[fmt(off + kt)] = copy.deepcopy(v)
                keys[fmt(end)] = r4(sample(channel, length, REST[name]))
            nb[name] = keys
        out['bones'][bone] = nb
    return out


def remap(clip, points, bones=None):
    """Part of a clip on a new time line: points are (time in the clip, new time) pairs in order. The pose at each point is keyed
    and the keys between two points keep their place between them."""
    def moved(t):
        for (s0, d0), (s1, d1) in zip(points, points[1:]):
            if s0 - EPS <= t <= s1 + EPS:
                return d0 + (d1 - d0) * (0 if s1 == s0 else (t - s0) / (s1 - s0))
        return points[-1][1]
    out = {'animation_length': round(points[-1][1], 4), 'bones': {}}
    for bone, channels in clip.get('bones', {}).items():
        if bones is not None and bone not in bones:
            continue
        nb = {}
        for name, channel in channels.items():
            if not _keyed(channel):
                nb[name] = copy.deepcopy(channel)
                continue
            keys = {fmt(d): r4(sample(channel, s, REST[name])) for s, d in points}
            for k, v in channel.items():
                if points[0][0] + EPS < float(k) < points[-1][0] - EPS:
                    keys[fmt(moved(float(k)))] = copy.deepcopy(v)
            nb[name] = keys
        if nb:
            out['bones'][bone] = nb
    return out


def join(first, second, gap):
    """One clip, then another whose first pose differs from the end of the first: `gap` seconds to get from one to the other."""
    length = float(first['animation_length'])
    off = length + gap
    out = copy.deepcopy(first)
    out['animation_length'] = round(off + float(second['animation_length']), 4)
    for bone, channels in second.get('bones', {}).items():
        nb = out['bones'].setdefault(bone, {})
        for name, channel in channels.items():
            if not _keyed(channel):
                nb.setdefault(name, copy.deepcopy(channel))
                continue
            if not _keyed(nb.get(name)):
                nb[name] = {fmt(0): list(REST[name]), fmt(length): list(REST[name])}
            for k, v in channel.items():
                nb[name][fmt(off + float(k))] = copy.deepcopy(v)
    return out


def in_order(clip):
    """Every channel's keys in time order, whatever put them there (GeckoLib reads them in the order of the file)."""
    for channels in clip.get('bones', {}).values():
        for name, channel in channels.items():
            if _keyed(channel):
                channels[name] = dict(sorted(channel.items(), key=lambda kv: float(kv[0])))
    return clip


def retime(clip, factor):
    """The same clip played over factor times its length."""
    out = {'animation_length': round(float(clip['animation_length']) * factor, 4), 'bones': {}}
    if clip.get('loop'):
        out['loop'] = True
    for bone, channels in clip.get('bones', {}).items():
        out['bones'][bone] = {n: ({fmt(float(k) * factor): copy.deepcopy(v) for k, v in ch.items()} if _keyed(ch) else copy.deepcopy(ch))
                              for n, ch in channels.items()}
    return out


def without(clip, *bones):
    out = copy.deepcopy(clip)
    for b in bones:
        out.get('bones', {}).pop(b, None)
    return out


def hold(clip, t, seconds=0.1, loop=True):
    """A still pose: the clip frozen at one time."""
    out = {'animation_length': seconds, 'bones': {}}
    if loop:
        out['loop'] = True
    for bone, channels in clip.get('bones', {}).items():
        out['bones'][bone] = {n: r4(sample(ch, t, REST[n])) for n, ch in channels.items()}
    return out


def plain(clip):
    """A source clip without what PZ owns (sounds) or what is not used here (particles, timelines)."""
    out = {k: copy.deepcopy(v) for k, v in clip.items() if k in ('animation_length', 'loop', 'bones')}
    return out


def last_key(clip, *bones):
    t = 0.0
    for b in bones:
        for ch in clip.get('bones', {}).get(b, {}).values():
            if _keyed(ch):
                t = max(t, max(float(k) for k in ch))
    return t


# ---------------------------------------------------------------- geometry

def bones_of(geo):
    return geo['minecraft:geometry'][0]['bones']


def bone_of(geo, name):
    return next(b for b in bones_of(geo) if b['name'] == name)


def explicit_uv(cube):
    """Box UV as the same six face rectangles (GeckoLib's own layout), so the cube can change size and keep its texture."""
    uv = cube.get('uv')
    if isinstance(uv, dict) or cube.get('mirror'):
        return cube
    u, v = uv
    dx, dy, dz = (math.floor(x) for x in cube['size'])
    cube['uv'] = {'west': {'uv': [u + dz + dx, v + dz], 'uv_size': [dz, dy]}, 'east': {'uv': [u, v + dz], 'uv_size': [dz, dy]},
                  'north': {'uv': [u + dz, v + dz], 'uv_size': [dx, dy]}, 'south': {'uv': [u + dz + dx + dz, v + dz], 'uv_size': [dx, dy]},
                  'up': {'uv': [u + dz, v], 'uv_size': [dx, dz]}, 'down': {'uv': [u + dz + dx, v + dz], 'uv_size': [dx, -dz]}}
    return cube


def flat_cube(origin, size, rect, rotation=None, pivot=None, inflate=0):
    """A new cube whose faces all show one rectangle of the texture (a patch of plain steel or wood)."""
    u0, v0, u1, v1 = rect
    face = {'uv': [u0, v0], 'uv_size': [u1 - u0, v1 - v0]}
    cube = {'origin': list(origin), 'size': list(size), 'uv': {k: dict(face) for k in ('north', 'south', 'east', 'west', 'up', 'down')}}
    if rotation is not None:
        cube['rotation'] = list(rotation)
        cube['pivot'] = list(pivot)
    if inflate:
        cube['inflate'] = inflate
    return cube


def bar(p, q, width, thick, rect, inflate=0):
    """A straight bar in the side plane of the gun from p to q, each (y, z) in model units; `width` across the gun."""
    (py, pz), (qy, qz) = p, q
    length = math.hypot(qy - py, qz - pz)
    angle = math.degrees(math.atan2(qy - py, qz - pz))       # GeckoLib turns the +z of a cube to (sin a, cos a) in (y, z)
    return flat_cube([-width / 2, py - thick / 2, pz], [width, thick, round(length, 3)], rect, [round(angle, 3), 0, 0], [0, py, pz], inflate)


def squeeze_z(geo, names, z0, factor):
    """Shortens (or lengthens) everything forward of z0 in the named bones: z -> z0 + (z - z0) * factor for z < z0."""
    def f(z):
        return round(z0 + (z - z0) * factor, 4) if z < z0 else z
    for b in bones_of(geo):
        if b['name'] not in names:
            continue
        if 'pivot' in b:
            b['pivot'][2] = f(b['pivot'][2])
        for c in b.get('cubes', []):
            z_min, z_max = c['origin'][2], c['origin'][2] + c['size'][2]
            if z_min >= z0:
                continue
            explicit_uv(c)
            n_min, n_max = f(z_min), f(z_max)
            c['origin'][2], c['size'][2] = n_min, round(n_max - n_min, 4)
            if 'pivot' in c:
                c['pivot'][2] = f(c['pivot'][2])


def cut_z(geo, names, z_cut):
    """Saws off everything forward of z_cut in the named bones."""
    for b in bones_of(geo):
        if b['name'] not in names:
            continue
        kept = []
        for c in b.get('cubes', []):
            z_min, z_max = c['origin'][2], c['origin'][2] + c['size'][2]
            if z_max <= z_cut:
                continue
            if z_min < z_cut:
                explicit_uv(c)
                c['origin'][2], c['size'][2] = z_cut, round(z_max - z_cut, 4)
            kept.append(c)
        b['cubes'] = kept


def hand_round(geo, source, arm='leftarm', name='handround'):
    """A round for the hand that loads the gun: a copy of the model's own cartridge (or shell) at the hand end of an arm, on a
    bone of its own beside the arm bone (same pivot and rest rotation, so the arm's animation moves it exactly like the arm; the
    arm bones themselves are never drawn, Steve's arms are, so it cannot be their child)."""
    cubes = copy.deepcopy(bone_of(geo, source)['cubes'])
    a = bone_of(geo, arm)
    px, py, pz = a['pivot']
    lo = [min(c['origin'][i] for c in cubes) for i in range(3)]
    hi = [max(c['origin'][i] + c['size'][i] for c in cubes) for i in range(3)]
    # the arm placeholder is an 8 x 24 x 8 box hanging from the pivot: its far end is the hand
    target = [px, py - 25.0, pz]
    d = [target[i] - (lo[i] + hi[i]) / 2 for i in range(3)]
    for c in cubes:
        c['origin'] = [round(c['origin'][i] + d[i], 4) for i in range(3)]
        if 'pivot' in c:
            c['pivot'] = [round(c['pivot'][i] + d[i], 4) for i in range(3)]
    bone = {'name': name, 'parent': a.get('parent'), 'pivot': list(a['pivot']), 'cubes': cubes}
    if 'rotation' in a:
        bone['rotation'] = list(a['rotation'])
    bones_of(geo).append(bone)
    return geo


def round_in_hand(clip, shown_from, arm='leftarm', name='handround'):
    """The hand's round follows the arm through a loading clip and is seen from the moment the hand comes back with it."""
    channels = clip.get('bones', {}).get(arm, {})
    clip.setdefault('bones', {})[name] = {k: copy.deepcopy(v) for k, v in channels.items() if k in ('rotation', 'position')}
    for t, v in ((0, 0), (max(.001, shown_from - .001), 0), (shown_from, 1)):
        set_key(clip, name, 'scale', t, [v, v, v])
    return clip


def set_key(clip, bone, channel, t, value):
    clip.setdefault('bones', {}).setdefault(bone, {}).setdefault(channel, {})
    ch = clip['bones'][bone][channel]
    if not _keyed(ch):
        ch = clip['bones'][bone][channel] = {}
    ch[fmt(t)] = list(value)


# ---------------------------------------------------------------- recipes

COMMON_HIDE = ['rightarm', 'leftarm', 'scope', 'muzzleflash', 'muzzleflash2', 'muzzleflash3', 'muzzleflashalt']
MOTION = ['running', 'runningstart', 'runningend']


def default_hidden(geo, data):
    """Bones the reference set shows only with an attachment (connector bones, kits, rails) and the placeholders."""
    names = [b['name'] for b in geo['minecraft:geometry'][0]['bones']]
    hide = [n for n in COMMON_HIDE if n in names] + [n for n in names if n.startswith('_cb_')]
    for f in data.get('features', []):
        if f.get('type') == 'PARTVISIBILITY':
            for p in f.get('parts', []):
                if p.get('visible') and p['name'] in names and p['name'] not in hide:
                    hide.append(p['name'])
    return hide


def base_clips(src, gun, pistol):
    """The gun's own clips under their logical names, with the reference set shared running clips where the gun has none."""
    own = src.clips(gun)
    shared = {k[len(PREFIX):]: v for k, v in src.json('geckolib/animations/item/%s.animation.json' % ('pistol' if pistol else 'common'))['animations'].items()}
    out = {}
    for name in ('idle', 'idleempty', 'draw', 'drawempty', 'fire', 'reload', 'reloadempty', 'inspect', 'inspectempty'):
        if name in own:
            out[name] = plain(own[name])
    for name in MOTION:
        clip = own.get(name) or shared.get(name)
        if clip is not None:
            out[name] = plain(clip)
    return own, out


def lead_in(clip, seconds=.12):
    """A clip cut out of the middle of another starts in a pose that is not the rest pose: give it a moment to get there."""
    out = {'animation_length': round(float(clip['animation_length']) + seconds, 4), 'bones': {}}
    for bone, channels in clip.get('bones', {}).items():
        nb = {}
        for name, channel in channels.items():
            if not _keyed(channel):
                nb[name] = copy.deepcopy(channel)
                continue
            keys = {fmt(0): list(REST[name])}
            for k, v in channel.items():
                keys[fmt(float(k) + seconds)] = copy.deepcopy(v)
            nb[name] = keys
        out['bones'][bone] = nb
    return out


def magazine_rack(own):
    """Working the slide/charging handle alone: the end of the empty reload, from a moment after the new magazine went in (the
    clip's own sound mark for it) to the end, with the magazines left where they are."""
    if 'reloadempty' not in own:
        return None
    clip = own['reloadempty']
    marks = sorted((float(k), cue_kind(v.get('effect', ''))) for k, v in clip.get('sound_effects', {}).items())
    inserted = [t for t, kind in marks if kind == 1]
    worked = [t for t, kind in marks if kind == 2 and inserted and t > inserted[-1]]
    length = float(clip['animation_length'])
    if not inserted or not worked:
        return None
    start = min(inserted[-1] + .25, worked[0] - .15)
    return lead_in(without(window(clip, start, length), 'magazine', 'magazine2', 'bullet', 'bullet2', 'bullet3'))


def press_check(own):
    """Where the pistol's inspection has the hand on the slide (pulled part of the way to look into the chamber, then let go):
    the times the hand takes hold, the slide is back, the hand lets go and the slide is shut, and how far the slide went."""
    channel = own.get('inspect', {}).get('bones', {}).get('slide', {}).get('position')
    if not _keyed(channel) or 'leftarm' not in own['inspect']['bones']:
        return None
    keys = sorted((float(k), _vec(v)[2]) for k, v in channel.items())
    back = next((i for i in range(1, len(keys)) if keys[i][1] > EPS and abs(keys[i - 1][1]) < EPS), None)
    if back is None:
        return None
    held = back
    while held + 1 < len(keys) and keys[held + 1][1] > EPS:
        held += 1
    if held + 1 >= len(keys):
        return None
    return keys[back - 1][0], keys[back][0], keys[held][0], keys[held + 1][0], keys[back][1]


# Pistols racked by hand here, and where the hand on the slide comes from. 'press': the gun's own inspection (its press check);
# its empty reload, which lets the slide go without a pull (the M1911 brings the hand up to the slide stop for it), ends with the
# same stroke. 'slingshot': the gun's own empty reload already has the hand on the locked slide, pulling away as it lets go (the
# B-F has no press check): that clip stays as it is and only the stroke from a shut slide is made.
HAND_RACKED = {}
# Guns whose inspection takes the right hand off the grip to show the gun's side (the B-F: 4 to the right, turned 10 degrees):
# the whole grip then stands in front of the hand that should be holding it. Here the hand stays where it is.
HAND_STAYS = ()
# the hand's way to the slide, the pull, the moment at full travel, the slide going home, and the pace of the way back to rest
RACK_REACH, RACK_PULL, RACK_HOLD, RACK_SNAP, RACK_SETTLE = .2, .1, .06, .04, .75
RACK_AROUND, RACK_LIFT = 5.0, (0, 5.0, 0)
RACK_SLIP = .75         # how much less than the slide the hand on it travels


def slide_stroke(own, locked=False):
    """The slide worked by hand. the reference set pistol clips never do it (an empty reload lets the locked slide drop by itself);
    PZ racks by hand. The hand on the slide is the inspection's press check; NEW here: the slide is pulled all the way instead of
    part of it and the pause to look is left out. `locked`: the slide already stands back after the last round, the hand takes
    hold of it there, tugs and lets go. The clip starts with the hand on the slide (not the rest pose)."""
    found = press_check(own)
    if found is None:
        return None
    grab, back, held, shut, travel = found
    inspect = own['inspect']
    full = _vec(sample(own.get('reloadempty', {}).get('bones', {}).get('slide', {}).get('position'), 0, [0, 0, 6.0]))[2] or 6.0
    end = last_key(inspect, 'main', '_camera_', 'leftarm')
    points = [(back, 0), (held, RACK_HOLD), (shut, RACK_HOLD + RACK_SNAP)] if locked else \
        [(grab, 0), (back, RACK_PULL), (held, RACK_PULL + RACK_HOLD), (shut, RACK_PULL + RACK_HOLD + RACK_SNAP)]
    points.append((end, points[-1][1] + (end - shut) * RACK_SETTLE))
    clip = remap(inspect, points, ('main', '_camera_', 'leftarm', 'slide'))
    t_back, t_held = points[-4][1], points[-3][1]
    tug = .5 if locked else 0          # a locked slide gives a little more before it is let go
    slide = clip['bones']['slide']['position']
    for k in list(slide):
        z = _vec(slide[k])[2] * full / travel
        slide[k] = [0, 0, round(z + (tug if abs(float(k) - t_held) < EPS else 0), 4)]
    gun = clip['bones']['main']['position']
    for k in list(gun):                 # the inspection holds the gun low; here the hand on the slide is to be seen
        if float(k) <= points[-2][1] + .2:
            gun[k] = r4([a + b for a, b in zip(_vec(gun[k]), RACK_LIFT)])
    hand = clip['bones']['leftarm']['position']
    taken = sample(inspect['bones']['leftarm']['position'], grab, REST['position'])[2]       # where the hand takes hold
    for k in list(hand):                # the hand goes (nearly) as far as the slide does
        if t_back - EPS <= float(k) <= t_held + EPS:
            v = _vec(hand[k])
            hand[k] = r4([v[0], v[1], taken + full - RACK_SLIP + (tug if abs(float(k) - t_held) < EPS else 0)])
    return clip


def slingshot_stroke(own):
    """The same stroke for a pistol whose own empty reload has the hand on the locked slide and pulling away as it lets it go:
    that hold and release, with the slide shut under the hand first and drawn back by it (NEW: the pull). The clip starts with
    the hand on the shut slide."""
    clip = own.get('reloadempty')
    slide = clip.get('bones', {}).get('slide', {}).get('position') if clip else None
    hand = clip.get('bones', {}).get('leftarm', {}).get('position') if clip else None
    if not _keyed(slide) or not _keyed(hand):
        return None
    drops = max(float(k) for k, v in slide.items() if _vec(v)[2] > EPS)                 # the last moment the slide stands back
    after = [float(k) for k, v in slide.items() if float(k) > drops and abs(_vec(v)[2]) < EPS]
    if not after:
        return None
    full, shut, end = _vec(sample(slide, 0, REST['position']))[2], min(after), last_key(clip, 'main', '_camera_', 'leftarm')
    back, let_go = RACK_PULL, RACK_PULL + RACK_HOLD
    stroke = remap(clip, [(drops, 0), (drops, let_go), (shut, let_go + RACK_SNAP), (end, let_go + RACK_SNAP + (end - shut) * RACK_SETTLE)],
                   ('main', '_camera_', 'leftarm', 'rightarm', 'slide'))
    stroke['bones']['slide']['position'] = {fmt(t): [0, 0, z] for t, z in ((0, 0), (back, full), (let_go, full), (let_go + RACK_SNAP, 0),
                                                                         (stroke['animation_length'], 0))}
    held = r4(sample(hand, drops, REST['position']))                                     # the hand with the slide back
    arm = stroke['bones']['leftarm']['position']
    arm[fmt(0)] = r4([held[0], held[1], held[2] - (full - RACK_SLIP)])
    arm[fmt(back)] = held
    return stroke


def eased(clip, t, easing, bones):
    """The way to the pose keyed at t starts and/or ends gently instead of at a constant pace."""
    for bone in bones:
        for name in ('rotation', 'position'):
            channel = clip['bones'].get(bone, {}).get(name)
            if _keyed(channel) and fmt(t) in channel:
                channel[fmt(t)] = {'vector': r4(_vec(channel[fmt(t)])), 'easing': easing}
    return clip


def around(clip, t0, t1, out=RACK_AROUND, bone='leftarm'):
    """Getting to the pose keyed at t1 (the hand on the slide): the gun and the view turn gently, and the hand, whose straight
    way there goes through the gun, is `out` further to its own side half way."""
    mid = (t0 + t1) / 2
    for name in ('rotation', 'position'):
        channel = clip['bones'][bone][name]
        v = sample(channel, mid, REST[name])
        if name == 'position':
            v[0] += out
        channel[fmt(mid)] = r4(v)
    eased(clip, mid, 'easeInSine', (bone,))
    eased(clip, t1, 'easeOutSine', (bone,))
    return eased(clip, t1, 'easeInOutSine', ('main', '_camera_'))


def hand_rack(own):
    """Working the slide of a pistol by hand from the rest pose (R with rounds in the magazine and none in the chamber)."""
    stroke = slide_stroke(own) or slingshot_stroke(own)
    return None if stroke is None else around(lead_in(stroke, RACK_REACH), 0, RACK_REACH)


def racked_reload(own):
    """The empty reload ending with the slide racked by hand instead of dropping by itself: the gun's own clip until the new
    magazine is in, then the hand goes to the locked slide. Returns the clip, when the gun counts as loaded (it is back at rest)
    and the sound marks (the slide's mark moves to the hand taking hold)."""
    stroke = slide_stroke(own, locked=True)
    clip = own.get('reloadempty')
    if stroke is None or clip is None:
        return None
    slide = clip['bones'].get('slide', {}).get('position')
    marks = sorted((float(k), cue_kind(v.get('effect', ''))) for k, v in clip.get('sound_effects', {}).items())
    if not _keyed(slide) or not any(kind == 2 for _, kind in marks):
        return None
    drops = max(float(k) for k, v in slide.items() if _vec(v)[2] > EPS)            # the last moment the slide stands back
    seated = max(float(k) for k in clip['bones']['magazine']['position'])            # the new magazine is in
    cut = min(seated + .07, drops - .02)
    if cut <= seated:
        return None
    turn = .28                                                                     # magazine hand to the slide, gun turned over
    out = around(join(window(plain(clip), 0, cut), stroke, turn), cut, cut + turn, RACK_AROUND + 3)    # it comes from under the grip
    takes_hold, shut = cut + turn, cut + turn + RACK_HOLD + RACK_SNAP
    rest = cut + turn + float(stroke['animation_length'])
    out['animation_length'] = round(rest + .2, 4)
    cues = [[round(t, 4), kind] for t, kind in marks if kind is not None and kind < 2 and t < cut]
    cues += [[round(takes_hold - .04, 4), 2], [round(shut + .15, 4), 3]]
    return out, round(rest, 4), cues


def at_rest(own, bone='leftarm'):
    """A bone's pose in the gun's idle clip (the reference set holds its rifles with the left arm moved and scaled there, not at zero)."""
    idle = own.get('idle', {}).get('bones', {}).get(bone, {})
    return {name: r4(sample(idle.get(name), 0, REST[name])) for name in REST}


# Rifles whose charging handle is pulled by hand here, and the the reference set gun whose own clip the pull is taken from. Point
# Blank's M16 never touches its handle: its empty reload sends the bolt home with the bolt release, and no clip works the action
# from a shut bolt, which is what PZ's rack is. Its M4 has the same receiver, the same handle bone and the same arms, and ends
# its empty reload with the left hand pulling the handle.
CHARGED = {}
# the hand's way from the handguard to the handle, and how far out to its own side it is half way (clear of the gun)
CHARGE_REACH, CHARGE_AROUND = .3, 6.0
# the reference set pulls the handle low, at the edge of the view, and turns its camera down to it; here the view stays where it is
# and the gun comes up and forward into sight for as long as the hand is on it
CHARGE_LIFT = (0, 5.0, -5.0)


def bolt_travel(own):
    channel = own.get('fire', {}).get('bones', {}).get('bolt', {}).get('position')
    return max(_vec(v)[2] for v in channel.values()) if _keyed(channel) else None


def charging_stroke(src, gun, own):
    """The donor's pull of the charging handle, on this gun's arm: from the hand taking hold of the handle to the end of the
    donor's empty reload (the hand back on the handguard, the gun at rest). The donor's bolt stands back until the handle is let
    go (its magazine was empty); here it is shut, and goes with the handle. The clip starts with the hand on the handle."""
    donor = CHARGED[gun]
    clip = src.clips(donor).get('reloadempty', {})
    handle, arm = (clip.get('bones', {}).get(bone, {}).get('position') for bone in ('action', 'leftarm'))
    if not _keyed(handle) or not _keyed(arm):
        return None
    back = min(float(k) for k, v in handle.items() if _vec(v)[2] > EPS)
    pulls = max(float(k) for k in handle if float(k) < back)                                  # the handle starts to move
    grab = max((float(k) for k in arm if float(k) < pulls - EPS), default=pulls)              # the hand is on it a moment before
    stroke = window(plain(clip), grab, last_key(clip, 'main', '_camera_', 'leftarm'), only=('main', '_camera_', 'leftarm', 'action'))
    stroke['bones']['bolt'] = copy.deepcopy(stroke['bones']['action'])
    shut = max(float(k) for k in stroke['bones']['action']['position'] if float(k) < float(stroke['animation_length']) - EPS)
    position = stroke['bones']['main']['position']
    for k, key in position.items():                 # all of it until the handle is home, half of it at the next key, then none
        part = 1 if float(k) <= shut + .05 else .5 if float(k) <= shut + .2 else 0
        if part:
            v = r4([a + b * part for a, b in zip(_vec(key), CHARGE_LIFT)])
            position[k] = {**key, 'vector': v} if isinstance(key, dict) and 'vector' in key else v
    # the same place on this gun: its arm bone is not exactly where the donor's is, and each gun's hand rests where its own idle
    # clip has it
    there, here = (bone_of(src.json(f'geckolib/models/item/{g}.geo.json'), 'leftarm') for g in (donor, gun))
    theirs, mine = at_rest(src.clips(donor)), at_rest(own)
    shift = {'rotation': [a - b for a, b in zip(there.get('rotation', [0, 0, 0]), here.get('rotation', [0, 0, 0]))],
             'position': [a - b for a, b in zip(there['pivot'], here['pivot'])], 'scale': [0, 0, 0]}
    for name, channel in stroke['bones']['leftarm'].items():
        for k, key in channel.items():
            v = _vec(key)
            v = mine[name] if all(abs(x - y) < 1e-3 for x, y in zip(v, theirs[name])) else r4([x + y for x, y in zip(v, shift[name])])
            channel[k] = {**key, 'vector': v} if isinstance(key, dict) and 'vector' in key else v
    return stroke


def charging_rack(src, gun, own):
    """Working the charging handle from the rest pose (R with rounds in the magazine and none in the chamber): the left hand
    leaves the handguard for the handle (NEW: that way there), then the donor's pull."""
    stroke = charging_stroke(src, gun, own)
    if stroke is None:
        return None
    clip, rest = lead_in(stroke, CHARGE_REACH), at_rest(own)
    for name, channel in clip['bones']['leftarm'].items():
        channel[fmt(0)] = rest[name]
    return around(clip, 0, CHARGE_REACH, CHARGE_AROUND)


def charged_reload(src, gun, own, clip, cues):
    """An empty reload of a gun with no magazine in it, ending with the charging handle pulled instead of the bolt release hit
    (PZ racks a gun it has just put a magazine into; with no magazine in the gun nothing has to hold the bolt back for the
    release to let go): the clip until the new magazine is in, then the hand goes from the magazine to the handle (NEW: that
    way there) and pulls it, the stroke `rack` has. The bolt stands where the clip has it (back, after the last round) until
    the handle is let go and goes home with it; a shut bolt goes back with the handle.
    Returns the clip, when the gun counts as loaded (it is back at rest) and the sound marks (the action's mark moves to the
    pull)."""
    stroke = charging_stroke(src, gun, own)
    travel = clip.get('bones', {}).get('magazine', {}).get('position')
    if stroke is None or not _keyed(travel):
        return None
    far = max((t for t, d in away(travel) if d > MAGAZINE_NEAR), default=None)
    seated = min((t for t, d in away(travel) if far is not None and t > far and d < EPS), default=None)    # the new magazine is in
    if seated is None:
        return None
    cut = round(seated + .07, 4)
    handle = sorted((float(k), _vec(v)[2]) for k, v in stroke['bones']['action']['position'].items())
    back = min(t for t, z in handle if z > EPS)                 # the handle is all the way back,
    pulls = max(t for t, _ in handle if t < back)               # starts to move,
    let_go = max(t for t, z in handle if z > EPS)               # is let go
    home = min(t for t, _ in handle if t > let_go)              # and is home
    stands = sample(clip['bones'].get('bolt', {}).get('position'), 0, REST['position'])
    out = around(join(window(clip, 0, cut), stroke, CHARGE_REACH), cut, cut + CHARGE_REACH, CHARGE_AROUND)
    on_handle = cut + CHARGE_REACH
    rest = on_handle + float(stroke['animation_length'])
    out['animation_length'] = round(rest + .2, 4)
    if stands[2] > EPS:
        out['bones']['bolt']['position'] = {fmt(0): r4(stands), fmt(on_handle + let_go): r4(stands), fmt(on_handle + home): [0, 0, 0],
                                            fmt(out['animation_length']): [0, 0, 0]}
    marks = [[t, kind] for t, kind in cues if t < cut]
    marks += [[round(on_handle + pulls - .04, 4), 2], [round(on_handle + home + .15, 4), 3]]
    return out, round(rest, 4), marks


def bolt_released(clip):
    """An empty reload whose bolt stands back for the whole clip (the reference set shuts it by going back to idle) and whose hand hits
    the bolt release: the bolt goes home under that hit. Returns the clip, changed or not."""
    bolt = clip.get('bones', {}).get('bolt', {}).get('position')
    hand = clip.get('bones', {}).get('leftarm', {}).get('position')
    marks = [float(k) for k, v in clip.get('sound_effects', {}).items() if cue_kind(v.get('effect', '')) == 2]
    if bolt is None or _keyed(bolt) or not _keyed(hand) or not marks:
        return clip
    open_at = _vec(bolt)
    near = [(_vec(v)[0], float(k)) for k, v in hand.items() if marks[-1] <= float(k) <= marks[-1] + .15]      # the hand is on the gun
    hit = min(near)[1] if near else marks[-1] + .05
    out = copy.deepcopy(clip)
    out['bones']['bolt']['position'] = {fmt(0): r4(open_at), fmt(hit): r4(open_at), fmt(hit + RACK_SNAP): [0, 0, 0],
                                        fmt(float(clip['animation_length'])): [0, 0, 0]}
    return out


def bolt_open(clip, travel):
    """A clip with the bolt standing back all through it (the gun after its last round)."""
    out = copy.deepcopy(clip)
    out.setdefault('bones', {})['bolt'] = {'position': {'vector': [0, 0, travel]}}
    return out


def emptied(idle, idleempty):
    """What the empty gun's idle pose has that the loaded one's has not (the slide or bolt back, no round in sight), as
    {bone: {channel: value}}."""
    state = {}
    for bone, channels in idleempty.get('bones', {}).items():
        for name, channel in channels.items():
            if not _keyed(channel) and channel != idle.get('bones', {}).get(bone, {}).get(name):
                state.setdefault(bone, {})[name] = copy.deepcopy(channel)
    return state


def held_empty(clip, state):
    """A clip played with the empty gun: what stands open on it stays open all through the clip (the reference set running clips
    only move the gun, so a gun run with would shut its slide for as long as the run lasts)."""
    out = copy.deepcopy(clip)
    for bone, channels in state.items():
        out.setdefault('bones', {}).setdefault(bone, {}).update(copy.deepcopy(channels))
    return out


def open_inspection(own):
    """The inspection of an empty pistol with its slide left back. the reference set (M9, M1911) lets the locked slide go at the
    start (the right thumb on the slide stop) and has the left hand work it twice near the end: a press check, then back to
    stay open. An empty gun's slide stands back before and after, and PZ has put nothing into it, so here it stands back all
    through the clip: the thumb does not press, the left hand does not come to the slide (it stays where the inspection has
    it until the moment it would have let go, then goes back to rest), and the gun is held still for that time instead of
    taking the pulls. Returns the clip, or None when the slide does not shut in the gun's own clip."""
    clip, idle = own.get('inspectempty'), own.get('idleempty')
    slide = clip.get('bones', {}).get('slide', {}).get('position') if clip else None
    if not _keyed(slide) or idle is None:
        return None
    back = sample(idle.get('bones', {}).get('slide', {}).get('position'), 0, REST['position'])
    moves = sorted((float(k), _vec(v)[2]) for k, v in slide.items())
    shut = next((i for i, (_, z) in enumerate(moves) if abs(z) < EPS), None)
    pulled = next((i for i, (_, z) in enumerate(moves) if shut is not None and i > shut and z > EPS), None)
    if back[2] < EPS or shut is None:
        return None
    out = plain(clip)
    out['bones']['slide']['position'] = {'vector': r4(back)}
    out['bones'].pop('rightarm', None)
    arm = out['bones'].get('leftarm', {})
    if pulled is None or not _keyed(arm.get('position')):
        return out
    taken = moves[pulled - 1][0]                                                    # the hand takes hold of the slide
    held = sorted((float(k), _vec(v)) for k, v in arm['position'].items())
    rest_at = held[-1][0]
    away_at = max(t for t, _ in held if t < taken - EPS)                            # its last moment away from the gun
    still = [t for (t, v), (_, before) in zip(held[1:], held) if taken < t < rest_at and v == before]
    leaves = still[-1] if still else moves[-1][0]                                   # its last moment holding the slide
    for name, channel in arm.items():
        if _keyed(channel):
            away = r4(sample(channel, away_at, REST[name]))
            for k in [k for k in channel if away_at + EPS < float(k) < rest_at - EPS]:
                del channel[k]
            channel[fmt(leaves)] = away
    for name in ('rotation', 'position'):                                           # the gun takes no pulls
        channel = out['bones'].get('main', {}).get(name)
        if _keyed(channel):
            for k in [k for k in channel if taken + .1 < float(k) < leaves - .05]:
                del channel[k]
    return out


# taking the magazine out: the hand's way down and out of sight with it (seconds, and where to: left, down, back), then back up
UNLOAD_CARRY, UNLOAD_AWAY, UNLOAD_BACK = .22, (-3.0, -18.0, 8.0), .38
# guns whose reload knocks the old magazine away instead of the hand taking it (measured: 31 units from the hand): no such clip
MAGAZINE_FLUNG = ()


def magazine_unload(own):
    """Taking the magazine out by hand and putting none in (PZ's shift+R; the reference set has no clip for it). The gun's own reload
    until the old magazine is out of the grip; NEW from there: the hand takes it down and out of sight instead of bringing the
    next one up, and comes back empty while the gun returns to rest the way its reload ends. The reload's new magazine is not
    shown. Returns the clip, when the gun is back at rest, and the sound mark of the magazine leaving."""
    clip = own.get('reload')
    travel = clip.get('bones', {}).get('magazine', {}).get('position') if clip else None
    tilt = clip.get('bones', {}).get('main', {}).get('rotation') if clip else None
    if not _keyed(travel) or not _keyed(tilt) or not _keyed(clip['bones'].get('leftarm', {}).get('position')):
        return None
    keys = sorted((float(k), _vec(v)) for k, v in travel.items())
    first = next((i for i, (_, v) in enumerate(keys) if math.hypot(*v) > 8), None)      # the first key clear of the grip
    if not first:
        return None
    leaves, out = keys[first - 1][0], keys[first][0]
    turned = sorted((float(k), math.hypot(*_vec(v))) for k, v in tilt.items())
    comes_back = max(t for t, size in turned if size >= .6 * max(size for _, size in turned))   # the last key of the reload's tilt
    if comes_back <= out:
        return None
    end = last_key(clip, 'main', '_camera_')
    held = window(plain(clip), 0, out, only=('main', '_camera_', 'leftarm', 'rightarm', 'magazine'))
    held['bones']['magazine2'] = {'scale': {'vector': [0, 0, 0]}}
    unload = join(held, window(plain(clip), comes_back, end, only=('main', '_camera_', 'rightarm')), UNLOAD_CARRY)
    away = out + UNLOAD_CARRY
    for bone in ('leftarm', 'magazine'):
        position = unload['bones'][bone]['position']
        position[fmt(away)] = {'vector': r4([a + b for a, b in zip(sample(position, out, REST['position']), UNLOAD_AWAY)]), 'easing': 'easeInSine'}
    for t, v in ((0, 1), (away, 1), (away + .01, 0)):                   # out of sight it is gone: PZ has it in the inventory
        set_key(unload, 'magazine', 'scale', t, [v, v, v])
    rest = at_rest(own)
    for name in ('rotation', 'position'):                               # the hand comes back empty
        channel = unload['bones']['leftarm'].setdefault(name, {fmt(0): list(REST[name])})
        channel[fmt(away + UNLOAD_BACK)] = {'vector': rest[name], 'easing': 'easeInOutSine'}
    if _keyed(unload['bones']['leftarm'].get('scale')):                 # and is as big as the idle clip has it
        unload['bones']['leftarm']['scale'][fmt(away + UNLOAD_BACK)] = rest['scale']
    rest = out + UNLOAD_CARRY + (end - comes_back)
    unload['animation_length'] = round(max(rest, away + UNLOAD_BACK) + .15, 4)
    marks = [float(k) for k, v in clip.get('sound_effects', {}).items() if cue_kind(v.get('effect', '')) == 0]
    mark = next((t for t in sorted(marks) if leaves - .15 <= t <= out), leaves)
    return unload, round(rest, 4), [[round(mark, 4), 0]]


# A magazine gun with no magazine in it (PZ: shift+R took it out, or the gun never had one). Its clips are `<clip>nomag`: the
# same hold, draw, inspection, run and rack with the magazine not there, and reloads that only put one in.
NOMAG = ('idle', 'draw', 'inspect', 'running', 'rack')
# a reload's cut: the time from the pose it starts in to the pose it ends in, how long before the magazine comes out the hand is
# on its way to it, and how near its seat a magazine counts as in the gun
NOMAG_GAP, NOMAG_REACH, MAGAZINE_NEAR = .1, .3, 3.0


def without_magazine(clip, bones):
    """The same clip for a gun whose magazine is not in it: the magazine bones are not shown."""
    out = copy.deepcopy(clip)
    for bone in bones:
        out.setdefault('bones', {}).setdefault(bone, {})['scale'] = {'vector': [0, 0, 0]}
    return out


def away(channel):
    """A magazine's position keys as (time, how far from its seat)."""
    return sorted((float(k), math.hypot(*_vec(v))) for k, v in channel.items())


def reload_without_magazine(clip, phase, cues):
    """A reload of a gun with no magazine in it: the gun's reload without the part in which the old magazine comes out. Point
    Blank's reloads are of two kinds. The new magazine is a bone of its own, brought up before the old one is taken out (the
    pistols' reload): the old one is not shown, the hand goes from where it arrives with the new magazine to where it starts
    putting it in, and does not carry an old one away afterwards. Or the same bone goes out and comes back as the new one: it
    is shown from where it is furthest away (in the hand), and the hand goes there without reaching for the magazine well
    first. Returns the clip, when the gun counts as loaded, and the sound marks (none for a magazine coming out): everything
    after the cut is that much earlier."""
    bones = clip.get('bones', {})
    old, new = (bones.get(bone, {}).get('position') for bone in ('magazine', 'magazine2'))
    if not _keyed(old):
        return None
    travel = away(old)
    gone = next((i for i, (_, d) in enumerate(travel) if d > MAGAZINE_NEAR), None)
    if not gone:
        return None
    leaves = travel[gone - 1][0]                                    # the old magazine's last moment in the gun
    second_bone = _keyed(new) and max(d for _, d in away(new)) > MAGAZINE_NEAR
    if second_bone:
        coming = [(t, d) for t, d in away(new) if t > leaves]
        seat = next((i for i, (_, d) in enumerate(coming) if d <= MAGAZINE_NEAR), None)
        if not seat:
            return None
        resumes, seated = coming[seat - 1][0], coming[seat][0]      # the new magazine about to go in, and in
    else:
        resumes = max(t for t, d in travel if d >= .9 * max(d for _, d in travel))
    gap = min(NOMAG_GAP, resumes - leaves)
    first, second = window(clip, 0, leaves), window(clip, resumes, float(clip['animation_length']))

    def drop(part, t0, t1):         # the left arm's keys between two times: it goes straight from the one before to the one after
        for channel in part['bones'].get('leftarm', {}).values():
            if _keyed(channel):
                for k in [k for k in channel if t0 + EPS < float(k) < t1 - EPS]:
                    del channel[k]
    if not second_bone:
        drop(first, max(0, leaves - NOMAG_REACH), leaves + 1)
    elif any(t > seated and d > MAGAZINE_NEAR for t, d in travel):
        drop(second, seated - resumes, last_key(clip, 'leftarm') - resumes)
    out = join(first, second, gap)
    if second_bone:
        out['bones']['magazine'] = {'scale': {'vector': [0, 0, 0]}}
    else:
        for t, v in ((0, 0), (leaves + gap - .01, 0), (leaves + gap, 1)):
            set_key(out, 'magazine', 'scale', t, [v, v, v])
    cut = resumes - leaves - gap
    marks = [[round(t if t <= leaves else max(0.0, t - cut), 4), kind] for t, kind in cues if kind != 0]
    return out, round(phase - cut, 4) if phase else phase, marks


def cue_kind(name):
    """Which of PZ's own gun sounds stands for a sound mark of a clip: 0 magazine out, 1 magazine in, 2 the action, 3 the gun
    settling. Only the mark's time and what it is for are used; the reference set sounds themselves are never read."""
    if 'unload' in name:
        return 0
    if 'reload' in name:
        return 1
    if name == 'pistol_slide' or 'foley' in name or name.endswith(('_up', '_upnew', 'inspectup', 'inspectturn')):
        return None
    if any(k in name for k in ('slide', 'action', 'hit', 'bolt', '_back', '_forward', 'pump')):
        return 2
    if any(k in name for k in ('draw', 'rest', 'inspect_down')):
        return 3
    return None


def sound_cues(own, names=('reload', 'reloadempty', 'inspect', 'inspectempty')):
    out = {}
    for name in names:
        marks = []
        for t, effect in sorted(((float(k), v.get('effect', '')) for k, v in own.get(name, {}).get('sound_effects', {}).items())):
            kind = cue_kind(effect)
            if kind is not None and (name.startswith('reload') or t > .5):      # an inspection starts silently
                marks.append([round(t, 4), kind])
        if marks:
            out[name] = marks[:7]
    return out


def reload_phases(src, gun):
    """How far into each reload clip the reference set counts the gun as loaded (the rest of the clip is the return to rest)."""
    out = {}
    for phase in src.data(gun).get('phasedReloads', []):
        name = phase.get('animation', '')[len(PREFIX):]
        if phase.get('phase') == 'RELOADING' and name in ('reload', 'reloadempty'):
            out['reloadEmptyPhase' if name == 'reloadempty' else 'reloadPhase'] = phase['duration'] / 1000.0
    return out


def recipe_magazine(src, gun, pistol):
    own, clips = base_clips(src, gun, pistol)
    info = reload_phases(src, gun)
    if gun in HAND_STAYS:
        for name in ('inspect', 'inspectempty'):
            if name in clips:
                clips[name] = without(clips[name], 'rightarm')
    rack = plain(own['rack']) if 'rack' in own else \
        (hand_rack(own) if gun in HAND_RACKED else charging_rack(src, gun, own) if gun in CHARGED else None) or magazine_rack(own)
    if rack is not None:
        clips['rack'] = rack
    if gun in CHARGED:
        if 'reloadempty' in own:
            clips['reloadempty'] = plain(bolt_released(own['reloadempty']))
        travel = bolt_travel(own)
        for name in ('idle', 'draw'):                   # after its last round the bolt stands back
            if travel is not None and name in clips and name + 'empty' not in clips:
                clips[name + 'empty'] = bolt_open(clips[name], travel)
    racked = racked_reload(own) if HAND_RACKED.get(gun) == 'press' else None
    if racked is not None:
        clips['reloadempty'], info['reloadEmptyPhase'], marks = racked
        info['cues'] = {'reloadempty': marks}
    unload = None if gun in MAGAZINE_FLUNG else magazine_unload(own)
    if unload is not None:
        clips['unload'], info['unloadPhase'], marks = unload
        info.setdefault('cues', {})['unload'] = marks
    # the empty gun: its slide stays back while it is looked at, and while it is run with
    looked = open_inspection(own)
    if looked is not None:
        clips['inspectempty'] = looked              # nothing works the slide in it: none of the action's sound marks
        info.setdefault('cues', {})['inspectempty'] = [m for m in sound_cues(own, ('inspectempty',)).get('inspectempty', []) if m[1] != 2]
    if 'idle' in clips and 'idleempty' in clips:
        state = emptied(clips['idle'], clips['idleempty'])
        for name in MOTION:
            if name in clips and state:
                clips[name + 'empty'] = held_empty(clips[name], state)
    # the same gun with no magazine in it
    names = {b['name'] for b in bones_of(src.json(f'geckolib/models/item/{gun}.geo.json'))}
    marks = sound_cues(own, ('reload', 'reloadempty'))
    marks.update(info.get('cues', {}))
    for name, key in (('reload', 'reloadPhase'), ('reloadempty', 'reloadEmptyPhase')):
        bare = reload_without_magazine(clips[name], info.get(key, 0), marks.get(name, [])) if name in clips else None
        if bare is not None and gun in CHARGED and name == 'reloadempty':       # no bolt release hit: the handle is pulled
            bare = charged_reload(src, gun, own, bare[0], bare[2]) or bare
        if bare is not None:
            clips[name + 'nomag'], info[key.replace('Phase', 'NoMagPhase')], cues = bare
            info.setdefault('cues', {})[name + 'nomag'] = cues
    for name in NOMAG:
        if name in clips:
            clips[name + 'nomag'] = without_magazine(clips[name], [b for b in ('magazine', 'magazine2') if b in names])
    return clips, info


def recipe_pump(src, gun):
    """Pump shotgun: the reference set fire clip already works the pump; shells go in one by one (prepare, load, finish)."""
    own, clips = base_clips(src, gun, False)
    fire = own['fire']
    pump_start = min(float(k) for k, v in fire['bones']['pump']['position'].items() if any(abs(x) > 1e-6 for x in _vec(v))) - .1
    pump_end = last_key(fire, 'pump', 'action', 'shell')
    # the stroke alone, without the recoil that precedes it in the fire clip
    clips['rack'] = lead_in(window(fire, max(0, pump_start - .02), min(float(fire['animation_length']), pump_end + .25), drop=('main', '_camera_')), .08)
    clips['loadstart'] = plain(own['prepare'])
    clips['load'] = plain(own['load'])
    clips['loadend'] = plain(own['finish'])
    return clips, {'fireRacks': True}


def recipe_break(src, gun):
    """Break-action double barrel: open (the shells fly out), both shells in, close."""
    own, clips = base_clips(src, gun, False)
    clips['loadstart'] = plain(own['prepareempty'])
    clips['load'] = concat(plain(own['loadfirst']), plain(own['loadsecond']))
    clips['loadend'] = plain(own['finishfull'])
    return clips, {'loadAll': True}


def revolver_rounds_geo(geo):
    """PZ loads a revolver one round at a time. The six rounds of the model become six bones on the cylinder (the client shows as
    many as the gun holds), and one more round rides the hand that brings it (the speed-loader bone, its loader body never shown)."""
    bullets = bone_of(geo, 'bullets')
    cubes = bullets['cubes']
    bullets['parent'] = 'rotatey'
    bullets['cubes'] = []
    for i, c in enumerate(cubes):
        bones_of(geo).append({'name': f'bullet{i + 1}', 'parent': 'bullets', 'pivot': list(bullets['pivot']), 'cubes': [c]})
    bones_of(geo).append({'name': 'round', 'parent': 'speedloader', 'pivot': list(bullets['pivot']), 'cubes': [copy.deepcopy(cubes[0])]})
    return geo


def recipe_revolver(src, gun):
    """Revolver: swing the cylinder out and empty it, one round in per trip of the hand, close. Cut from the one speed-loader
    reload clip (the loader's trip is the hand's trip; the loader itself and five of its six rounds are not shown)."""
    own, clips = base_clips(src, gun, True)
    reload = own['reload']
    out_at = last_key(reload, 'bullets')                      # the spent cases are gone, the loader comes next
    loader_in = min(float(k) for k, v in reload['bones']['loader']['scale'].items() if _vec(v)[0] > .5)
    loader_gone = last_key(reload, 'loader')
    close_at = min(float(k) for k, v in reload['bones']['chamber']['rotation'].items() if float(k) > loader_gone and abs(_vec(v)[2]) > 1)
    start = window(reload, 0, loader_in - .1)
    # the cases that fell out are gone before the hand comes back
    for t, v in ((0, 1), (loader_in - .25, 1), (loader_in - .15, 0)):
        set_key(start, 'bullets', 'scale', t, [v, v, v])
    start['bones']['round'] = {'scale': [0, 0, 0]}
    load = without(window(reload, loader_in - .1, loader_gone), 'bullets', 'loader')
    load['bones']['loader'] = {'scale': [0, 0, 0]}
    shown = round(out_at - (loader_in - .1), 4)               # the moment the reference set shows the new rounds on the loader
    for t, v in ((0, 0), (shown - .001, 0), (shown, 1)):
        set_key(load, 'round', 'scale', t, [v, v, v])
    end = without(window(reload, close_at - .05, float(reload['animation_length'])), 'bullets', 'loader')
    end['bones']['round'] = {'scale': [0, 0, 0]}
    end['bones']['loader'] = {'scale': [0, 0, 0]}
    clips['loadstart'], clips['load'], clips['loadend'] = start, load, end
    clips.pop('reload', None)
    return clips, {'roundBones': [f'bullet{i}' for i in range(1, 7)], 'roundsShowAll': ['loadstart'], 'clipBones': {'round': ['load'], 'loader': []},
                   'marks': {'spentOut': round(out_at, 4), 'loaderIn': round(loader_in, 4), 'loaderGone': round(loader_gone, 4), 'close': round(close_at, 4)}}


def recipe_bolt(src, gun):
    """Bolt action fed round by round through the open bolt: the fire clip works the bolt; loading is cut from the empty reload
    (raise and open, the hand's trip for a round with the magazine left where it is, close and lower)."""
    own, clips = base_clips(src, gun, False)
    fire, empty = own['fire'], own['reloadempty']
    opened = last_key({'bones': {'b': {'position': {k: v for k, v in empty['bones']['bolt1']['position'].items()
                                                    if float(k) < float(empty['animation_length']) / 2}}}}, 'b')
    mag_first = min(float(k) for k, v in empty['bones']['magazine']['position'].items() if any(abs(x) > 1e-6 for x in _vec(v))) - .13
    mag_last = last_key(empty, 'magazine')
    closing = min(float(k) for k, v in empty['bones']['bolt1']['position'].items() if float(k) > mag_last and sum(abs(x) for x in _vec(v)) > 1)
    drop = ('magazine', 'bullet', 'bullet1')
    clips['loadstart'] = without(window(empty, 0, max(opened, mag_first)), *drop)
    load_from = max(opened, mag_first)
    clips['load'] = without(window(empty, load_from, mag_last), *drop)
    away = max((float(k) for k, v in empty['bones']['magazine']['position'].items() if abs(_vec(v)[1]) > 20), default=(load_from + mag_last) / 2)
    round_in_hand(clips['load'], round(away - load_from, 4))
    clips['loadend'] = without(window(empty, closing - .05, float(empty['animation_length'])), *drop)
    bolt_start = min(float(k) for k, v in fire['bones']['bolt2']['rotation'].items() if any(abs(x) > 1e-6 for x in _vec(v))) - .07
    bolt_end = last_key(fire, 'bolt1', 'bolt2')
    clips['rack'] = lead_in(window(fire, max(0, bolt_start - .02), min(float(fire['animation_length']), bolt_end + .2), drop=('main', '_camera_', 'action', 'action2')))
    for name in ('reload', 'reloadempty'):
        clips.pop(name, None)
    return clips, {'fireRacks': True, 'loadendCloses': True, 'clipBones': {'handround': ['load']}}


STEEL = (72, 59, 79, 62)       # citoricxs.png: receiver side
GUARD = (39, 10, 42, 14)       # citoricxs.png: trigger guard
LEVER_PIVOT = [0, 18.3, -6.0]
LEVER_OPEN = -50.0


def lever_geo(geo):
    """A lever-action rifle made from the stock, steel and barrels of the over/under (the reference set has no lever action): the
    lower barrel reads as the magazine tube, the barrels are shortened to carbine length, the receiver is lengthened back over
    the stock head, and the trigger guard becomes a lever with a finger loop on its own bone (pivot at the front of the receiver)."""
    gun = bone_of(geo, 'gun')
    cubes = gun['cubes']
    # the bars of the guard (not the trigger) move to the lever bone, with a loop that follows the grip
    guard = [c for c in cubes if c['origin'][1] < 17.0 and c['origin'][2] < 2.5 and c['size'][0] == 3]
    gun['cubes'] = [c for c in cubes if not any(c is g for g in guard)]
    loop = [bar((14.65, 0.6), (8.0, 5.6), 3, 1, GUARD, -0.2), bar((8.0, 5.3), (7.6, 9.6), 3, 1, GUARD, -0.2), bar((7.7, 9.4), (10.9, 11.4), 3, 1, GUARD, -0.2)]
    bones_of(geo).append({'name': 'lever', 'parent': 'gun', 'pivot': list(LEVER_PIVOT), 'cubes': guard + loop})
    # receiver: the block of the stock head behind the steel becomes steel
    for c in gun['cubes']:
        if c['origin'] == [-2, 19, -6.1] and c['size'] == [4, 3.75, 4]:
            c['uv'] = flat_cube([0, 0, 0], [1, 1, 1], STEEL)['uv']
    # carbine length: everything forward of the fore-end is shortened
    squeeze_z(geo, ('barrels', 'barrel1', 'barrel2', 'muzzleflash', '_cb_suppressor'), -41.5, 0.62)
    return hand_round(geo, 'shell')


def recipe_lever(src, gun):
    """Clips for the made lever action, from those of the over/under: its recoil, then the lever worked (new motion: the lever
    swings down and forward about its pivot with the right hand in the loop, and the case comes out); loading reuses the
    over/under raise / hand trip / lower with the barrels left shut."""
    own, clips = base_clips(src, gun, False)
    shut = ('barrels', 'bone', 'shell', 'shell2', 'action')
    for name in list(clips):
        clips[name] = without(clips[name], *shut)
    # the trip of the hand while the lever swings: the centre of the loop, about 13 behind and 9 below the pivot, turned by LEVER_OPEN
    a = math.radians(LEVER_OPEN)
    ry, rz = -9.0, 13.0
    dy, dz = ry * math.cos(a) + rz * math.sin(a) - ry, -ry * math.sin(a) + rz * math.cos(a) - rz

    def cycle(clip, t0, shell_from=None):
        down, held, up = t0 + .14, t0 + .24, t0 + .42
        for t, v in ((t0, 0), (down, LEVER_OPEN), (held, LEVER_OPEN), (up, 0)):
            set_key(clip, 'lever', 'rotation', t, [v, 0, 0])
            k = v / LEVER_OPEN
            set_key(clip, 'rightarm', 'position', t, [0, round(dy * k, 3), round(dz * k, 3)])
            set_key(clip, 'rightarm', 'rotation', t, [round(-14 * k, 3), 0, 0])
        if shell_from is not None:
            # the empty case leaves as the lever opens: the ejection path of the over/under, from its opening clip
            start = .5667
            for channel in ('rotation', 'position'):
                for k, v in shell_from['bones']['shell'][channel].items():
                    if float(k) >= start - EPS:
                        set_key(clip, 'shell', channel, down - .04 + float(k) - start, _vec(v))
            set_key(clip, 'shell', 'scale', 0, [0, 0, 0])
            set_key(clip, 'shell', 'scale', down - .05, [0, 0, 0])
            set_key(clip, 'shell', 'scale', down - .04, [1, 1, 1])
            set_key(clip, 'shell', 'scale', down + .28, [1, 1, 1])
            set_key(clip, 'shell', 'scale', down + .30, [0, 0, 0])
        return up
    fire = clips['fire']
    end = cycle(fire, .26, own['prepareempty'])
    fire['animation_length'] = round(max(float(fire['animation_length']), end + .3), 4)
    rack = {'animation_length': 0, 'bones': {}}
    end = cycle(rack, .04, own['prepareempty'])
    rack['animation_length'] = round(end + .12, 4)
    clips['rack'] = rack
    clips['loadstart'] = without(plain(own['prepareempty']), *shut)
    clips['load'] = round_in_hand(without(plain(own['loadfirst']), *shut), .02)
    clips['loadend'] = without(plain(own['finishfull']), *shut)
    return clips, {'fireRacks': True, 'clipBones': {'handround': ['load']}}


def shift_z(geo, name, dz, forward_of=None):
    """Moves a bone (its pivot and its own cubes, or only those forward of a z) along the barrel."""
    b = bone_of(geo, name)
    if 'pivot' in b and (forward_of is None or b['pivot'][2] < forward_of):
        b['pivot'][2] = round(b['pivot'][2] + dz, 4)
    for c in b.get('cubes', []):
        if forward_of is not None and c['origin'][2] + c['size'][2] > forward_of:
            continue
        c['origin'][2] = round(c['origin'][2] + dz, 4)
        if 'pivot' in c:
            c['pivot'][2] = round(c['pivot'][2] + dz, 4)


def cube_centre(c):
    """Where the middle of a cube really is (model units), with its own rotation about its pivot applied the way GeckoLib does."""
    centre = [c['origin'][i] + c['size'][i] / 2 for i in range(3)]
    if 'rotation' not in c:
        return centre
    px, py, pz = c.get('pivot', [0, 0, 0])
    x, y, z = -centre[0] + px, centre[1] - py, centre[2] - pz           # mirrored in x, relative to the pivot
    rx, ry, rz = (math.radians(a) for a in (-c['rotation'][0], -c['rotation'][1], c['rotation'][2]))
    y, z = y * math.cos(rx) - z * math.sin(rx), y * math.sin(rx) + z * math.cos(rx)
    x, z = x * math.cos(ry) + z * math.sin(ry), -x * math.sin(ry) + z * math.cos(ry)
    x, y = x * math.cos(rz) - y * math.sin(rz), x * math.sin(rz) + y * math.cos(rz)
    return [-(x - px), y + py, z + pz]


def drop_behind(geo, name, z_from):
    """Removes the cubes of a bone whose middle lies behind z_from (the butt of a stock)."""
    b = bone_of(geo, name)
    b['cubes'] = [c for c in b.get('cubes', []) if cube_centre(c)[2] < z_from]


def m870_sawn_geo(geo):
    """PZ sawed-off pump gun: barrel and magazine tube cut just ahead of the fore-end, the butt cut off behind the grip."""
    cut = -56.0
    muzzle = bone_of(geo, 'muzzleflash')['pivot'][2]
    cut_z(geo, ('gun',), cut)
    shift_z(geo, 'ironsights', cut - muzzle + 3.5, forward_of=cut)        # the front bead goes back to the new muzzle
    for name in ('muzzleflash', '_cb_suppressor'):
        shift_z(geo, name, cut - bone_of(geo, name)['pivot'][2])
    drop_behind(geo, 'gun', 13.0)
    return geo


def citori_sawn_geo(geo):
    """PZ sawed-off double barrel: both barrels cut just ahead of the fore-end, the butt cut off behind the grip."""
    cut = -47.0
    cut_z(geo, ('barrels', 'barrel1', 'barrel2'), cut)
    for name in ('muzzleflash', '_cb_suppressor'):
        shift_z(geo, name, cut - bone_of(geo, name)['pivot'][2])
    drop_behind(geo, 'gun', 13.0)
    return geo


def rhino_barrel(factor):
    def edit(geo):
        if factor != 1:
            squeeze_z(geo, ('gun', 'muzzleflash', '_cb_suppressor'), -10.5, factor)
        return revolver_rounds_geo(geo)
    return edit


def box_rects(cube):
    """The texture rectangles (u0, v0, u1, v1) a cube draws from."""
    uv = cube.get('uv')
    if isinstance(uv, dict):
        out = []
        for f in uv.values():
            (u, v), (w, h) = f['uv'], f['uv_size']
            out.append((min(u, u + w), min(v, v + h), max(u, u + w), max(v, v + h)))
        return out
    u, v = uv
    dx, dy, dz = (math.floor(x) for x in cube['size'])
    return [(u + dz, v, u + dz + 2 * dx, v + dz), (u, v + dz, u + 2 * (dx + dz), v + dz + dy)]


def walnut_stock(png, geo):
    """PZ hunting rifles have wooden stocks and blued barrels: the coloured (camouflage) texels of the bolt rifle become walnut,
    shade for shade, and those of the barrel (the cubes that reach the muzzle) plain steel; the grey steel is left as it is."""
    import numpy as np
    import pngio
    img = pngio.read_bytes(png).astype(float)
    rgb = img[..., :3]
    hi, lo = rgb.max(-1), rgb.min(-1)
    coloured = ((hi - lo) / np.maximum(hi, 1) > .08) & (hi > 35) & (img[..., 3] > 0)
    light = (rgb @ np.array([.3, .59, .11])) / 255
    wood = np.clip((.55 + .75 * light)[..., None] * np.array([128.0, 74.0, 41.0]), 0, 255)
    steel = np.clip((34 + 46 * light)[..., None] * np.array([1.0, 1.0, 1.06]), 0, 255)
    barrel = np.zeros(coloured.shape, bool)
    for c in bone_of(geo, 'gun')['cubes']:
        if c['origin'][2] < -70:
            for u0, v0, u1, v1 in box_rects(c):
                barrel[int(v0):int(math.ceil(v1)), int(u0):int(math.ceil(u1))] = True
    brass = np.zeros(coloured.shape, bool)                    # the cartridge keeps its brass
    for c in bone_of(geo, 'bullet1')['cubes']:
        for u0, v0, u1, v1 in box_rects(c):
            brass[int(v0):int(math.ceil(v1)), int(u0):int(math.ceil(u1))] = True
    coloured &= ~brass
    rgb[coloured & ~barrel] = wood[coloured & ~barrel]
    rgb[coloured & barrel] = steel[coloured & barrel]
    return pngio.to_bytes(img.astype(np.uint8))


def brass_case(png, geo):
    """The lever action throws out a rifle case, not a red shotgun shell: the texels of that one cube become brass."""
    import numpy as np
    import pngio
    img = pngio.read_bytes(png).astype(float)
    light = (img[..., :3] @ np.array([.3, .59, .11])) / 255
    brass = np.clip((.6 + 1.1 * light)[..., None] * np.array([190.0, 150.0, 62.0]), 0, 255)
    for u0, v0, u1, v1 in box_rects(bone_of(geo, 'shell')['cubes'][0]):
        img[int(v0):int(math.ceil(v1)), int(u0):int(math.ceil(u1)), :3] = brass[int(v0):int(math.ceil(v1)), int(u0):int(math.ceil(u1))]
    return pngio.to_bytes(img.astype(np.uint8))


def c14_hunting_geo(geo):
    """A hunting rifle carries no bipod."""
    gun = bone_of(geo, 'gun')
    gun['cubes'] = [c for c in gun['cubes'] if not (cube_centre(c)[1] < 16.0 and cube_centre(c)[2] < -45.0)]     # the folded bipod under the fore-end
    return hand_round(geo, 'bullet1')


# ---------------------------------------------------------------- a magazine in the hand

# One magazine to hold per pistol, each MADE from that pistol's own magazine: they are different magazines for different
# cartridges, and no model is shared. type: PZ's magazine item. gun: the model it is taken from. mouth: the top of the collar,
# its front, the inside and the outside of its back wall and half its width, in that gun model's numbers (measured on its
# magazine). round: a cartridge's width and length, and whether its case has a rim. out: how far the magazine stands out of the
# fist (up and forward along its own slant). patch: free space in the gun's texture for the texels painted here.
MAGAZINES = {
    'mag9mm': {'type': 'Base.9mmClip', 'gun': 'm9', 'mouth': (20.7, -2.3, 2.1, 2.75, 1.45), 'round': (1.5, 3.5, False),
               'out': (0, 6.5, -2.0), 'patch': (96, 0)},
    'mag45': {'type': 'Base.45Clip', 'gun': 'm1911a1', 'mouth': (20.8, -2.25, 2.35, 3.0, 1.45), 'round': (1.8, 3.8, False),
              'out': (0, 6.5, -2.0), 'patch': (95, 15)},
    'mag44': {'type': 'Base.44Clip', 'gun': 'deserteagle', 'mouth': (20.45, -2.15, 3.1, 3.75, 1.5), 'round': (1.75, 4.8, True),
              'out': (0, 6.5, -1.35), 'patch': (78, 0)},
    # A rifle's: `neck`: the case narrows to a neck and the bullet is slim and pointed. `stagger`: the rounds lie in two columns,
    # this far to each side of the middle (the top one to the left, the next beside and below it). `lips`: how much of the
    # mouth's back the lips cover. `left`: where the left arm bone is and how it is turned; on the rifle it is out at the
    # handguard, here it is under the right fist, where the pistols' is (the M9's, measured from its right arm).
    'mag556': {'type': 'Base.556Clip', 'gun': 'm16a1', 'mouth': (21.8, -16.3, -9.9, -9.2, 1.8), 'round': (1.4, 6.0, False),
               'out': (0, 5.45, 13.65), 'patch': (145, 1), 'neck': True, 'stagger': .7, 'lips': 4.6,
               'left': ([10.7, 2.275, 20.3], [-118.95832, 6.70101, -29.35181])},
}
# the mouth: how far down the collar goes, how high the lips stand over it, how much of its back they cover
COLLAR, LIPS, LIPS_LONG = 2.3, .9, 2.7
STAGGER_DROP = .5       # how much lower than the top round the one beside it lies
# tilted so that the mouth is looked into, and about as far from the eye as a pistol's grip is (found by rendering candidates).
# The first placement, [-5, -16.5, -11.5], had the mouth at less than half this distance: the magazine filled most of the height
# of the view.
MAGAZINE_VIEW = {'rotation': [25, 15, 8], 'translation': [-1.5, -19.5, -23]}


def under_view(view, point, left=0.0, clear=0.0):
    """From a point of the model (the .geo.json's numbers) to the nearest place out of sight under the first-person view with
    these display transforms: at the same distance from the eye, `left` to the left of it on the screen and `clear` below the
    lower edge. Returns the way there in the model's numbers (what an animated bone's position is given in)."""
    import numpy as np
    import geoview
    seen = geoview.first_person_matrix({'display': {'firstperson_righthand': view}})
    eye = seen @ np.array([-point[0] / 16, point[1] / 16, point[2] / 16, 1.0])
    edge = eye[2] * math.tan(math.radians(geoview.FOV / 2))             # the lower edge at that distance (eye[2] is negative)
    there = np.linalg.inv(seen) @ np.array([eye[0] - left / 16, edge - clear / 16, eye[2], 1.0])
    return r4([float(v) for v in (-there[0] * 16 - point[0], there[1] * 16 - point[1], there[2] * 16 - point[2])])


def patches(spec):
    """A magazine's five 4x4 texel patches: brass case, its head with the primer, copper bullet, the mouth's steel, the follower."""
    u, v = spec['patch']
    return [(u + 5 * i, v, u + 5 * i + 4, v + 4) for i in range(5)]


def cartridge(x, y, z, spec):
    """A cartridge lying nose forward (towards -z) with its corner at (x, y, z): case (with a rim, if it has one), bullet, its
    nose; a rifle's has its neck between case and bullet."""
    (width, length, rim), (brass, head, copper, _, _) = spec['round'], patches(spec)
    if spec.get('neck'):
        nose, ogive, neck = round(length * .09, 3), round(length * .15, 3), round(length * .12, 3)
        cubes = [flat_cube([x, y, z + nose + ogive + neck], [width, width, round(length - nose - ogive - neck, 3)], brass),
                 flat_cube([x + .2, y + .2, z + nose + ogive], [width - .4, width - .4, neck], brass),
                 flat_cube([x + .3, y + .3, z + nose], [width - .6, width - .6, ogive], copper),
                 flat_cube([x + .5, y + .5, z], [width - 1.0, width - 1.0, nose], copper)]
        u0, v0, u1, v1 = head
        cubes[0]['uv']['south'] = {'uv': [u0, v0], 'uv_size': [u1 - u0, v1 - v0]}
        return cubes
    nose, ogive = round(length * .12, 3), round(length * .26, 3)
    case = round(length - nose - ogive, 3)
    cubes = [flat_cube([x, y, z + nose + ogive], [width, width, case], brass),
             flat_cube([x + .15, y + .15, z + nose], [width - .3, width - .3, ogive], copper),
             flat_cube([x + .35, y + .35, z], [width - .7, width - .7, nose], copper)]
    if rim:
        cubes.append(flat_cube([x - .1, y - .1, z + length - .25], [width + .2, width + .2, .25], brass))
    u0, v0, u1, v1 = head
    for cube in ([cubes[0], cubes[-1]] if rim else [cubes[0]]):         # the back of the case (and of its rim)
        cube['uv']['south'] = {'uv': [u0, v0], 'uv_size': [u1 - u0, v1 - v0]}
    return cubes


def moved(bone, by):
    """A bone with its pivot and cubes somewhere else."""
    bone['pivot'] = [round(a + b, 4) for a, b in zip(bone['pivot'], by)]
    for c in bone.get('cubes', []):
        c['origin'] = [round(a + b, 4) for a, b in zip(c['origin'], by)]
        if 'pivot' in c:
            c['pivot'] = [round(a + b, 4) for a, b in zip(c['pivot'], by)]
    return bone


def magazine_item_geo(geo, spec):
    """A pistol's magazine as a thing held by itself (PZ fills it by hand; the reference set has no such item). MADE here: the gun
    model's own magazine (body, base plate), and on top of it a mouth that can be looked into (a collar that squares off the
    slanted top, the two feed lips over its back half, the back wall), the round lying in it (`top`, shown when the magazine holds
    any), the follower standing in the mouth instead when it holds none (`follower`: a dark plate with a lug at its back; under
    the rounds it is out of sight), the same cartridge once more for the hand that feeds it (`round`, at rest exactly where `top`
    is), and the gun's two arm bones. The right hand holds it the way it holds the grip, the magazine standing out of the fist.
    A magazine with two columns (`stagger`): the top round lies to the left, the next one (`next`, shown when it holds two or
    more) beside and below it, and the follower is a low plate with a rib where that second round would be; with one round in,
    the rib is seen beside it (`held`, the parent of `top`, has a copy of the rib: the round next to it covers it)."""
    magazine, right, left = (copy.deepcopy(bone_of(geo, n)) for n in ('magazine', 'rightarm', 'leftarm'))
    magazine['parent'] = 'main'
    if 'tilt' in spec:                  # held leaning this way in the fist instead of the way it leans in the gun
        magazine['rotation'] = [spec['tilt'], 0, 0]
    (top_y, front, back, outside, side), (width, length, _), (_, _, _, steel, dark) = spec['mouth'], spec['round'], patches(spec)
    stagger = spec.get('stagger', 0)
    inner, lips_front = round(stagger + width / 2 + (.05 if stagger else .25), 4), round(back - spec.get('lips', LIPS_LONG), 4)
    if 'left' in spec:
        pivot, rotation = spec['left']
        left = dict(moved(left, [a - b for a, b in zip(pivot, left['pivot'])]), rotation=list(rotation))

    def box(origin, size, rect):
        return flat_cube(r4(origin), r4(size), rect)
    magazine['cubes'] += [
        box([-side, top_y - COLLAR, front], [2 * side, COLLAR, outside - front], steel),                # collar
        box([-side, top_y, lips_front], [side - inner, LIPS, back - lips_front], steel),               # lips
        box([inner, top_y, lips_front], [side - inner, LIPS, back - lips_front], steel),
        box([-side, top_y, back], [2 * side, LIPS, outside - back], steel)]                            # back wall
    follower = {'name': 'follower', 'parent': 'magazine', 'pivot': [0, top_y, 0], 'cubes': [
        box([-(inner - .05), top_y + .1, front + .25], [2 * (inner - .05), .75, back - front - .25], dark),
        box([-.5, top_y + .85, back - 1.3], [1.0, .3, 1.3], steel)]}
    seat = r4([stagger - width / 2, top_y + .15, back - length])
    top = {'name': 'top', 'parent': 'magazine', 'pivot': r4([0, seat[1] + width / 2, back]), 'cubes': cartridge(*seat, spec)}
    fed = dict(copy.deepcopy(top), name='round', parent='main')
    parts = [magazine, top, follower, fed]
    if stagger:
        low = r4([-stagger - width / 2, seat[1] - STAGGER_DROP, seat[2]])
        beside = cartridge(*low, spec)
        (cx, cy, cz), (_, _, case) = beside[0]['origin'], beside[0]['size']          # the rib is inside that round's case
        rib = box([cx + .05, top_y, cz + .05], [width - .1, low[1] + width - .05 - top_y, case - .1], dark)
        follower['cubes'] = [box([-(inner - .05), top_y + .1, front + .25], [2 * (inner - .05), .3, back - front - .25], dark), rib]
        held = {'name': 'held', 'parent': 'magazine', 'pivot': list(top['pivot']), 'cubes': [copy.deepcopy(rib)]}
        top['parent'] = 'held'
        parts = [magazine, held, top, {'name': 'next', 'parent': 'magazine', 'pivot': list(top['pivot']), 'cubes': beside}, follower, fed]
    out = copy.deepcopy(geo)
    out['minecraft:geometry'][0]['bones'] = [{'name': 'main', 'pivot': [0, 12, 2]}] + [moved(b, spec['out']) for b in parts] + [right, left]
    return out


def paint_rounds(png, spec):
    """The texels of the cartridge and of the magazine's mouth, each lit from above, in a corner of the texture nothing else uses."""
    import numpy as np
    import pngio
    img = pngio.read_bytes(png)
    shades = [[(224, 186, 112), (196, 154, 76), (196, 154, 76), (150, 114, 44)],        # brass
              [(170, 130, 56), (150, 114, 44), (150, 114, 44), (120, 90, 34)],          # the case head
              [(168, 96, 66), (140, 74, 50), (140, 74, 50), (108, 56, 39)],             # copper
              [(78, 77, 84), (63, 62, 68), (58, 57, 62), (47, 45, 52)],                 # the mouth's steel
              [(40, 39, 44), (36, 35, 38), (36, 35, 38), (30, 29, 33)]]                 # the follower
    for (u0, v0, u1, v1), rows in zip(patches(spec), shades):
        if img[v0:v1, u0:u1, 3].any():
            raise SystemExit('the cartridge texels would overwrite the gun texture')
        for i, rgb in enumerate(rows):
            img[v0 + i, u0:u1] = (*rgb, 255)
    u0, v0, _, _ = patches(spec)[1]
    img[v0 + 1:v0 + 3, u0 + 1:u0 + 3] = (196, 190, 170, 255)        # the primer
    return pngio.to_bytes(img.astype(np.uint8))


def hand_end(geo, arm, rotation=(0, 0, 0), position=(0, 0, 0), scale=(1, 1, 1)):
    """Where the hand end of an arm placeholder is (in the .geo.json's numbers) when an animation has turned, moved and scaled
    the bone."""
    import numpy as np
    import geoview
    model = geoview.Model(geo)
    (ox, oy, oz), (sx, _, sz) = (model.by_name[arm]['cubes'][0][k] for k in ('origin', 'size'))
    q = model.world_matrices({arm: {'rotation': rotation, 'position': position, 'scale': scale}})[arm] @ np.array([-(ox + sx / 2) / 16, oy / 16, (oz + sz / 2) / 16, 1.0])
    return [-q[0] * 16, q[1] * 16, q[2] * 16]


def backwards(clip):
    """The same clip run from its end to its start."""
    length = float(clip['animation_length'])
    out = {'animation_length': length, 'bones': {}}
    for bone, channels in clip['bones'].items():
        nb = out['bones'][bone] = {}
        for name, channel in channels.items():
            if not _keyed(channel):
                nb[name] = copy.deepcopy(channel)
                continue
            keys = {fmt(length - float(k)): r4(_vec(v)) for k, v in channel.items()}
            keys.setdefault(fmt(0), r4(sample(channel, length, REST[name])))
            keys.setdefault(fmt(length), r4(sample(channel, 0, REST[name])))
            nb[name] = keys
    return out


# the left arm's turn while it feeds a round (found by looking: it comes up from below and the left and leaves the round in
# sight beside the hand), and where the hand's end is from the middle of the round it holds
FEED_ARM, FEED_GRIP = (-25.0, -30.0, -45.0), (3.2, .2, .6)
# a round's way into the magazine: when it is picked up out of sight, is over the open front of the mouth, lies on the round
# below, is pressed down, and is pushed back under the lips; and how far forward of where it ends up it goes down
FEED_TIMES, FEED_FORWARD = (.08, .27, .32, .38, .44), 2.0
FEED_LENGTH = .62
# where it is picked up: under the lower edge of the view (wherever the magazine is held in it), this far to the left of the
# mouth on the screen and this much further down than the round is long
FEED_LEFT, FEED_UNDER = 5.5, 1.0
# the magazine coming up into the hand: from under the view (this much of a margin), turned back; when it is nearly up, and up
DRAW_UNDER, DRAW_TURN, DRAW_TIMES = 4.0, -20.0, (.32, .45)


def recipe_feed(src, gun, spec):
    """A magazine held by itself and filled by hand. All of it is NEW motion (the reference set has nothing like it): `load` puts one
    round in and takes the hand back to rest (`loadMark` is when the round is in: PZ adds it then), `unloadround` is the same
    backwards (`unloadMark`: the round is out from under the lips), `draw` brings the magazine up. The same two clips serve a
    magazine with rounds in it and an empty one: whatever is in the mouth (`top`, or `follower` when there is none) gives way
    under the round that is pressed in, and comes up again under the one that is taken out; which of the two is drawn follows
    PZ's count (`roundBones`, `emptyBones`)."""
    geo = magazine_item_geo(src.json(f'geckolib/models/item/{gun}.geo.json'), spec)
    width, length, view = spec['round'][0], spec['round'][1], spec.get('view', MAGAZINE_VIEW)
    cubes = bone_of(geo, 'top')['cubes']
    seat = [(min(c['origin'][i] for c in cubes) + max(c['origin'][i] + c['size'][i] for c in cubes)) / 2 for i in range(3)]
    # measured from where the round lies once it is in: (seconds, to the left, up, back)
    path = list(zip(FEED_TIMES, (0, 0, 0, 0, 0), (0, width + .7, width - .5, 0, 0), (0, -FEED_FORWARD - .6, -FEED_FORWARD, -FEED_FORWARD, 0)))
    path[0] = (FEED_TIMES[0], *under_view(view, seat, FEED_LEFT, length + FEED_UNDER))
    reach = hand_end(geo, 'leftarm', FEED_ARM)

    def arm(offset):        # the arm's own shift that puts its hand on a round `offset` from the seat
        return r4([seat[i] + offset[i] + FEED_GRIP[i] - reach[i] for i in range(3)])
    picked, _, placed, pressed, mark = FEED_TIMES
    load = {'animation_length': FEED_LENGTH, 'bones': {}}
    set_key(load, 'leftarm', 'rotation', 0, [0, 0, 0])
    set_key(load, 'leftarm', 'position', 0, [0, 0, 0])
    set_key(load, 'round', 'position', 0, path[0][1:])
    for t, *offset in path:
        set_key(load, 'round', 'position', t, offset)
        set_key(load, 'leftarm', 'rotation', t, FEED_ARM)
        set_key(load, 'leftarm', 'position', t, arm(offset))
    for t, v in ((0, 0), (picked - .02, 0), (picked, 1)):                          # the round is first seen in the hand
        set_key(load, 'round', 'scale', t, [v, v, v])
    down = -(width + .05)
    for t, y in ((0, 0), (placed, 0), (pressed, down), (FEED_LENGTH, down)):       # what is in the mouth gives way into the magazine
        set_key(load, 'top', 'position', t, [0, y, 0])
        set_key(load, 'follower', 'position', t, [0, y, 0])
    for t, y in ((0, 0), (placed, 0), (pressed, -.45), (mark + .08, 0), (FEED_LENGTH, 0)):      # the magazine takes the push
        set_key(load, 'main', 'position', t, [0, y, 0])
    set_key(load, 'leftarm', 'rotation', mark + .07, FEED_ARM)                     # the hand lets go, and goes back
    set_key(load, 'leftarm', 'position', mark + .07, arm((.6, 1.6, 1.2)))
    set_key(load, 'leftarm', 'rotation', FEED_LENGTH, [0, 0, 0])
    set_key(load, 'leftarm', 'position', FEED_LENGTH, [0, 0, 0])
    set_key(load, 'round', 'position', FEED_LENGTH, [0, 0, 0])
    high = [0, max(c['origin'][1] + c['size'][1] for c in cubes), seat[2]]         # the top of what stands in the mouth
    nearly, up = DRAW_TIMES
    draw = {'animation_length': up, 'bones': {}}
    for t, turn, shift in ((0, [DRAW_TURN, 0, 0], under_view(view, high, 0, DRAW_UNDER)), (nearly, [2, 0, 0], [0, .4, 0]), (up, [0, 0, 0], [0, 0, 0])):
        set_key(draw, 'main', 'rotation', t, turn)
        set_key(draw, 'main', 'position', t, shift)
    clips = {'idle': {'animation_length': .1, 'loop': True, 'bones': {}}, 'draw': draw, 'load': load, 'unloadround': backwards(load)}
    # roundBones: the n-th is drawn when the magazine holds at least n
    return clips, {'roundBones': ['held', 'next'] if spec.get('stagger') else ['top'], 'emptyBones': ['follower'],
                   'clipBones': {'round': ['load', 'unloadround']}, 'loadMark': mark, 'unloadMark': round(FEED_LENGTH - pressed, 4)}


# Held still, a gun or a magazine is not quite still (the reference set idle clips are one pose). NEW motion, the same for every
# kind and every idle clip (idle, idleempty, idlenomag): the breath. The `main` bone, which carries the model, both arms and
# the sights, rises and falls once a loop, the muzzle a little with it,
# and drifts to each side twice. The loop starts and ends in the pose the clip had, where every other clip ends. Keyed densely
# and linearly, so the offline viewer and the game show the same curve. Aimed, the game fades it out (GunItem: the sights are
# aligned with the model at rest and have to stay where the shot goes).
BREATH = 3.0                            # seconds a loop
# up and down, and to each side (model units, 1/16 block), for a model held as far from the eye as a pistol is (blocks): a long
# gun is held nearer (a pump shotgun at less than a third of that) and moves that much less, so that it is the same on the
# screen for every kind
BREATH_RISE, BREATH_DRIFT, BREATH_AT = .4, .1, 1.9
BREATH_PITCH, BREATH_ROLL = .4, .3      # the muzzle up and down, and the roll with the drift (degrees)
BREATH_STEPS = 24


def held_at(geo, base):
    """How far from the eye (blocks) the first-person view of an item's base model has the model's `main` pivot."""
    import numpy as np
    import geoview
    x, y, z = bone_of(geo, 'main')['pivot']
    return -float((geoview.first_person_matrix(base) @ np.array([-x / 16, y / 16, z / 16, 1.0]))[2])


def breathing(clip, near=1.0, bone='main'):
    """An idle clip with the breath in it. `near`: how far from the eye the model is held, as a part of BREATH_AT."""
    out = copy.deepcopy(clip)
    out['animation_length'], out['loop'] = BREATH, True
    held = {name: sample(out.get('bones', {}).get(bone, {}).get(name), 0, REST[name]) for name in ('rotation', 'position')}
    for name in held:
        out.setdefault('bones', {}).setdefault(bone, {})[name] = {}
    for i in range(BREATH_STEPS + 1):
        a = 2 * math.pi * i / BREATH_STEPS
        t = BREATH * i / BREATH_STEPS
        rise, drift = math.sin(a), math.sin(2 * a)
        turn = [held['rotation'][0] - BREATH_PITCH * rise, held['rotation'][1], held['rotation'][2] + BREATH_ROLL * drift]
        shift = [held['position'][0] + BREATH_DRIFT * near * drift, held['position'][1] + BREATH_RISE * near * rise, held['position'][2]]
        set_key(out, bone, 'rotation', t, [v + 0.0 for v in r4(turn)])          # + 0.0: no negative zeros in the file
        set_key(out, bone, 'position', t, [v + 0.0 for v in r4(shift)])
    return out


