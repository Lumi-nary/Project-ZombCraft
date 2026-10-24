"""Our own clips for the M1A Rifle (m1a.py's model on the MK14's skeleton): what the hands and the rifle do, solved into the
bones' turns and moves and sampled into GeckoLib keys. No key is taken from the reference set.

    python m1a_anim.py --out DIR      writes gun_m1a.geo.json and gun_m1a.animation.json there, for a look
                                      (build.py calls clips() and DATA for the kind `m1a`)

The rest hold is kept from the first pass: the left arm where the MK14's idle has it (the forend in the palm), the right where
m1a.GRIP puts it (the fist round the wrist). The clips:
  draw, fire        the rifle comes up from below, the left hand meeting the forend; the kick and the bolt's cycle
  reload            the M14 way: the left hand takes the magazine, thumb on the catch, rocks it forward and pulls it down and
                    away; brings the new one up, hooks its front in first and rocks it back until the catch takes it
  reloadempty       the same with an empty magazine, the bolt held back; then the left hand goes back to the forend while
                    the right hand pulls the operating rod handle a little further back and lets it fly
  rack              from a shut bolt: the right hand pulls the handle all the way back and lets go
The handle is on the right of these rifles, so the right hand works it: it leaves the wrist, takes the handle from behind
with the fingers, pulls and lets go, while the left hand stays on the forend (as PZ's own rifle rack, Bob_Reload_Bolt_Rack, has
it; the first pass reached over the receiver with the left hand, which aimed that arm back at the eye: release/BUGS.md B2).
  inspect           the rifle turned to show its left side, then rolled to show the right (the operating rod), and back
  running*          held low and turned in while running, bobbing twice a loop
The derived clips (unload, the empty and the no-magazine ones) are made from these by build.py, as for the other guns.

How it is made: an arm reaching for something is turned the least from its rest pose that points it from a shoulder (off the
screen, chosen per reach) at the thing, and slid along itself so the middle of the hand is on it. A magazine in the hand is
fixed to it (one rigid transform from the moment it is taken), so the two never drift apart. Between reaches the arm's own
channels are eased from one pose to the next. Everything is sampled FPS times a second; keys that a straight line between their
neighbours reproduces are dropped.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np

import geoview

FPS = 30
PALM = 2.5                      # from the end of an arm back to the middle of its hand


class Spec:
    """One rifle's numbers for these clips (model numbers, 1/16 block). These are the M1A's; another rifle of the same action
    (js14_anim.py) subclasses it with its own."""
    name = 'm1a'                                    # its sound marks are named after it
    LEFT_REST = {'rotation': (0.0, 0.0, -5.0), 'position': (5.02289, -2.70283, 4.45835), 'scale': (1.2, 1.4, 1.2)}
    TRAVEL = 10.0                   # how far the bolt and the operating rod go back
    HANDLE = (-4.3, 25.85, -24.9)   # the operating rod handle with the bolt home
    ON_HANDLE = (1.2, 3.2, .3)      # the middle of the hand from the handle: over it, a little behind
    GRIP_MAG = (3.0, 11.5, -19.5)   # the middle of the hand on a magazine, in its own numbers: on its left face, below the middle
    LUG = (0.0, 21.0, -24.1)        # its front top (the lug it is hooked in by first), in its own numbers
    EMPTY_ROCK, ROCK = 18.0, 20.0   # how far a magazine is rocked (degrees, the back down) to come out and to go in
    # the whole view's poses (rotation, position): raised, the muzzle turned left and up, rolled to bring the magazine well into
    # sight; while the right hand works the handle, rolled and turned to show the rifle's right side (searched offline: no arm
    # end faces the eye, the handle stays in view); running, across the body
    TILT = (-10, -13, -23, -1.5, 5.5, 1.0)
    RACK = (-6, 12, 28, 1.0, 1.0, -3.0)
    RUN = (6, -48, 22, 3.0, 1.5, -3.0)
    DRAW_FROM = (4.0, 12.0, -36.0)          # where the left hand comes from as the rifle comes up
    OFF_FOREND = (6.0, 14.0, -28.0)         # on its way down off the forend, back towards the magazine
    # the right hand on the handle, from the handle (model numbers): its middle on the right of the knob, a little under and
    # behind it; where it comes from and where it drops to after letting go (under the right side, not back towards the eye,
    # or the fist's end faces it for a moment); the shoulder the right arm reaches from (low, behind and to the right, so the
    # arm comes up from under the view)
    ON_HANDLE = (-1.6, -1.0, 1.4)
    TO_HANDLE = (-5.0, -8.0, -2.0)
    FROM_HANDLE = (-5.0, -7.0, 0.0)
    RIGHT_SHOULDER = (-16.0, -20.0, 30.0)
    DATA = {'phasedReloads': [
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': False}, 'duration': 1950, 'animation': 'animation.model.reload'},
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': True}, 'duration': 2500, 'animation': 'animation.model.reloadempty'}],
        'viewRecoilAmplitude': 3.0}          # how hard a shot kicks the view (.308 battle rifle); what a shot does stays PZ's

    @classmethod
    def mag_paths(cls, rig):
        """The old magazine's way out (0 to 1.12 s) and the new one's way in (1.12 to 1.62 s), as Keys of (rock, move x, y, z):
        rocked forward about its front lug and pulled down and away; the new one hooked in front and rocked back home."""
        out = Keys((0, (0, 0, 0, 0)), (.45, (0, 0, 0, 0)), (.6, (cls.EMPTY_ROCK, 0, -.6, -.5), 'io'),
                   (.78, (cls.EMPTY_ROCK, .4, -13.0, -1.0), 'in'), (1.12, (cls.EMPTY_ROCK + 25, 6.0, -48.0, 10.0), 'in'))
        back = Keys((1.12, (cls.ROCK + 30, 5.0, -46.0, 12.0)), (1.38, (cls.ROCK + 6, .4, -9.0, -.8), 'out'), (1.5, (cls.ROCK, 0, -.4, -.3)),
                    (1.62, (0, 0, 0, 0), 'in'))
        return out, back


DATA = Spec.DATA


# ---------------------------------------------------------------- numbers

def g(p):
    """Model numbers (1/16 block, x to the rifle's left) to geoview's space (blocks, x mirrored)."""
    return np.array([-p[0] / 16, p[1] / 16, p[2] / 16])


def m(v):
    return np.array([-v[0] * 16, v[1] * 16, v[2] * 16])


def shift(v):
    out = np.eye(4)
    out[:3, 3] = v
    return out


def turn_x(degrees):
    """A turn about the rifle's x: positive puts the back of what is turned down."""
    return geoview.rot_zyx(math.radians(degrees), 0, 0)


def align(a, b):
    """The least rotation taking unit vector a to unit vector b."""
    v, c = np.cross(a, b), float(np.dot(a, b))
    if c < -.9999:
        raise ValueError('opposite directions')
    k = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + k + k @ k / (1 + c)


def euler(r, near=None):
    """Angles (x, y, z) in radians with r = Rz Ry Rx, the one of its two solutions nearer `near`."""
    b = math.asin(max(-1.0, min(1.0, -r[2, 0])))
    a, c = math.atan2(r[2, 1], r[2, 2]), math.atan2(r[1, 0], r[0, 0])
    sols = [(a, b, c), (a + math.pi, math.pi - b, c + math.pi)]
    if near is None:
        return sols[0]
    def unwrap(s):
        return tuple(x + 2 * math.pi * round((n - x) / (2 * math.pi)) for x, n in zip(s, near))
    return min((unwrap(s) for s in sols), key=lambda s: sum((x - n) ** 2 for x, n in zip(s, near)))


def ease(x, how):
    x = max(0.0, min(1.0, x))
    return {'lin': x, 'in': 1 - math.cos(x * math.pi / 2), 'out': math.sin(x * math.pi / 2)}.get(how, (1 - math.cos(x * math.pi)) / 2)


class Keys:
    """Values at times, eased from one to the next (each key says how it is reached: 'io' in and out, 'in', 'out', 'lin')."""
    def __init__(self, *keys):
        self.keys = [(float(t), np.array(v, float), how) for t, v, *rest in keys for how in [rest[0] if rest else 'io']]

    def __call__(self, t):
        keys = self.keys
        if t <= keys[0][0]:
            return keys[0][1]
        for (t0, v0, _), (t1, v1, how) in zip(keys, keys[1:]):
            if t <= t1:
                return v0 + (v1 - v0) * ease((t - t0) / (t1 - t0) if t1 > t0 else 1, how)
        return keys[-1][1]


# ---------------------------------------------------------------- the rig

REST = {'rotation': (0.0, 0.0, 0.0), 'position': (0.0, 0.0, 0.0), 'scale': (1.0, 1.0, 1.0)}


def rest_of(spec, arm):
    """An arm's rest channels: the left arm's are the spec's LEFT_REST, the right arm rests where its bone is (RIGHT_REST)."""
    return spec.LEFT_REST if arm == 'leftarm' else getattr(spec, 'RIGHT_REST', REST)


class Rig:
    def __init__(self, geo, spec=Spec):
        self.model = geoview.Model(geo)
        self.bone = self.model.by_name
        self.spec = spec
        self.shoulders = {}                 # where the rest hold has each arm's shoulder (model numbers)
        for arm in ('leftarm', 'rightarm'):
            if arm in self.bone:
                rest = self.local(arm, **rest_of(spec, arm))
                self.shoulders[arm] = m((rest @ np.append(g(self.bone[arm]['pivot']), 1))[:3])
        self.shoulder = self.shoulders['leftarm']
        self.rest_grip = self.grip_of(self.local('leftarm', **spec.LEFT_REST))

    def local(self, name, rotation=(0, 0, 0), position=(0, 0, 0), scale=(1, 1, 1)):
        """The bone's matrix relative to its parent for the given channel values."""
        return self.model.bone_matrix(self.bone[name], {name: {'rotation': rotation, 'position': position, 'scale': scale}})

    def channels(self, name, matrix, scale=(1, 1, 1), near=None):
        """The rotation and position values that put the bone at `matrix` (relative to its parent)."""
        b = self.bone[name]
        rx, ry, rz = b.get('rotation', [0, 0, 0])
        pivot = g(b.get('pivot', [0, 0, 0]))
        rs = matrix[:3, :3]
        near_rad = None if near is None else (math.radians(-near[0] - rx), math.radians(-near[1] - ry), math.radians(near[2] + rz))
        a, bb, c = euler(rs / np.array(scale), near_rad)
        rotation = (-math.degrees(a) - rx, -math.degrees(bb) - ry, math.degrees(c) - rz)
        position = m(matrix[:3, 3] - pivot + rs @ pivot)
        return np.array(rotation), position

    def end_of(self, matrix, arm='leftarm'):
        """An arm matrix's end (model numbers) and its direction (geoview's space)."""
        pivot = g(self.bone[arm]['pivot'])
        end = (matrix @ np.append(pivot + np.array([0, -1.5, 0]), 1))[:3]
        start = (matrix @ np.append(pivot, 1))[:3]
        return end, (end - start) / np.linalg.norm(end - start)

    def grip_of(self, matrix, arm='leftarm'):
        end, d = self.end_of(matrix, arm)
        return m(end - d * PALM / 16)

    def reach(self, grip, shoulder=None, base=None, arm='leftarm'):
        """An arm's matrix (the left one unless named) with the middle of its hand at `grip`, pointing from `shoulder` (model
        numbers; its rest hold's shoulder when None)."""
        base = base or rest_of(self.spec, arm)
        scale = np.array(base['scale'])
        pivot = g(self.bone[arm]['pivot'])
        r0 = self.local(arm, base['rotation'], (0, 0, 0), base['scale'])[:3, :3] / scale
        d0 = r0 @ np.array([0.0, -1.0, 0.0])
        d = g(grip) - g(self.shoulders[arm] if shoulder is None else shoulder)
        d /= np.linalg.norm(d)
        r = align(d0, d) @ r0
        end = g(grip) + d * PALM / 16
        start = end - d * 1.5 * scale[1]
        out = np.eye(4)
        out[:3, :3] = r * scale
        out[:3, 3] = start - (r * scale) @ pivot
        return out

    def magazine(self, name='magazine', rock=0.0, move=(0, 0, 0)):
        """A magazine bone's matrix: at its seat, rocked `rock` degrees about its front lug (the back down), moved by `move`."""
        seat = self.local(name)
        lug = (seat @ np.append(g(self.spec.LUG), 1))[:3]
        return shift(g(move)) @ shift(lug) @ turn_x(rock) @ shift(-lug) @ seat

    def on_magazine(self, mag):
        """Where the middle of the hand goes on a magazine bone's matrix (model numbers)."""
        return m((mag @ np.append(g(self.spec.GRIP_MAG), 1))[:3])


# ---------------------------------------------------------------- clips

class Clip:
    """A clip built bone by bone: channel functions of time, sampled into keys."""
    def __init__(self, rig, length, loop=False):
        self.rig, self.length, self.loop = rig, length, loop
        self.parts = {}                 # (bone, channel) -> function of t giving a 3-vector
        self.sounds = {}

    def set(self, bone, channel, fn):
        self.parts[(bone, channel)] = fn if callable(fn) else (lambda t, v=np.array(fn, float): v)

    def sound(self, t, effect):
        self.sounds[t] = effect

    def matrix(self, bone, fn, scale=(1, 1, 1)):
        """A bone placed by a function of time giving its matrix; its rotation stays continuous from sample to sample."""
        last = {}

        def value(t, which):
            key = round(t, 6)
            if key not in last:
                near = last.get('near')
                rot, pos = self.rig.channels(bone, fn(t), scale, near)
                last['near'] = rot
                last[key] = (rot, pos)
            return last[key][which]
        self.set(bone, 'rotation', lambda t: value(t, 0))
        self.set(bone, 'position', lambda t: value(t, 1))
        if tuple(scale) != (1, 1, 1):
            self.set(bone, 'scale', scale)

    def build(self):
        times = [i / FPS for i in range(int(math.floor(self.length * FPS + 1e-6)) + 1)]
        if abs(times[-1] - self.length) > 1e-6:
            times.append(self.length)
        bones = {}
        for (bone, channel), fn in self.parts.items():
            values = [np.array(fn(t), float) for t in times]
            if all(np.allclose(v, values[0], atol=1e-4) for v in values):
                keys = {'vector': rounded(values[0])}
            else:
                keys = {fmt(times[i]): {'vector': rounded(values[i])} for i in thin(times, values, .03 if channel == 'position' else .08)}
            bones.setdefault(bone, {})[channel] = keys
        out = {'loop': self.loop, 'animation_length': round(self.length, 4), 'bones': bones}
        if self.sounds:
            out['sound_effects'] = {fmt(t): {'effect': e} for t, e in sorted(self.sounds.items())}
        return out


def rounded(v):
    return [round(float(x), 4) + 0.0 for x in v]


def fmt(t):
    return ('%.4f' % t).rstrip('0').rstrip('.') if t else '0.0'


def thin(times, values, tol):
    """The samples to keep: the first and last, and every one a straight line from the last kept to the next would miss."""
    keep, i = [0], 0
    while i < len(times) - 1:
        j = i + 1
        while j + 1 < len(times):
            t0, t1 = times[i], times[j + 1]
            ok = all(np.max(np.abs(values[i] + (values[j + 1] - values[i]) * (times[k] - t0) / (t1 - t0) - values[k])) <= tol
                     for k in range(i + 1, j + 1))
            if not ok:
                break
            j += 1
        keep.append(j)
        i = j
    return keep


def left_arm(clip, segments, arm='leftarm'):
    """An arm (the left one unless named) through segments (t0, t1, what): what = ('to', grip, shoulder, how) eases its
    channels to the pose reaching `grip` (None: the rest hold); ('follow', fn) has the hand on a moving point
    fn(t) -> (grip, shoulder); ('carry', bone_fn, grip) keeps it fixed to a magazine whose matrix bone_fn(t) gives, as it was
    held at t0."""
    rig = clip.rig
    base = rest_of(rig.spec, arm)
    scale = base['scale']
    rest = (np.array(base['rotation']), np.array(base['position']))
    plan, at = [], rest
    for t0, t1, what in segments:
        if what[0] == 'to':
            grip, shoulder, how = what[1], what[2], what[3]
            goal = rest if grip is None else rig.channels(arm, rig.reach(grip, shoulder, arm=arm), scale, at[0])
            plan.append((t0, t1, 'to', at, goal, how))
            at = goal
        elif what[0] == 'follow':
            fn = what[1]
            plan.append((t0, t1, 'fn', lambda t, fn=fn: rig.reach(*fn(t), arm=arm)))
            at = rig.channels(arm, rig.reach(*fn(t1), arm=arm), scale, at[0])
        else:
            bone_fn = what[1]
            hold = np.linalg.inv(bone_fn(t0)) @ arm_matrix(rig, at, arm)
            plan.append((t0, t1, 'fn', lambda t, b=bone_fn, h=hold: b(t) @ h))
            at = rig.channels(arm, bone_fn(t1) @ hold, scale, at[0])
    cache = {'near': rest[0]}

    def pose(t):
        key = round(t, 6)
        if key in cache:
            return cache[key]
        seg = next((s for s in plan if s[0] - 1e-9 <= t <= s[1] + 1e-9), None)
        if seg is None:
            before = [s for s in plan if s[1] < t]
            value = rest if not before else (seg_end(before[-1]))
        elif seg[2] == 'to':
            t0, t1, _, a, b, how = seg
            k = ease((t - t0) / (t1 - t0) if t1 > t0 else 1, how)
            value = (a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k)
        else:
            value = rig.channels(arm, seg[3](t), scale, cache['near'])
        cache['near'] = value[0]
        cache[key] = value
        return value

    def seg_end(s):
        return s[4] if s[2] == 'to' else rig.channels(arm, s[3](s[1]), scale, cache['near'])
    clip.set(arm, 'rotation', lambda t: pose(t)[0])
    clip.set(arm, 'position', lambda t: pose(t)[1])
    clip.set(arm, 'scale', scale)


def arm_matrix(rig, channels, arm='leftarm'):
    return rig.local(arm, tuple(channels[0]), tuple(channels[1]), rest_of(rig.spec, arm)['scale'])


def still_left(clip):
    for name in ('rotation', 'position', 'scale'):
        clip.set('leftarm', name, clip.rig.spec.LEFT_REST[name])


def rifle(clip, path):
    """The whole view (rifle and arms, the `main` bone) along Keys of (rotation, position) six-vectors."""
    clip.set('main', 'rotation', lambda t: path(t)[:3])
    clip.set('main', 'position', lambda t: path(t)[3:])


def bolt(clip, path):
    for bone in ('action', 'action2'):
        clip.set(bone, 'position', lambda t: (0, 0, float(path(t))))


def shown(clip, bone, path):
    """Whether a bone is drawn, as Keys of 0/1 (keys with 'lin' just apart make a switch)."""
    clip.set(bone, 'scale', lambda t: [float(path(t))] * 3)


def idle(rig, empty=False):
    c = Clip(rig, .1, loop=True)
    still_left(c)
    if empty:
        bolt(c, Keys((0, rig.spec.TRAVEL)))
    return c.build()


def draw(rig, empty=False):
    c = Clip(rig, .85)
    rifle(c, Keys((0, (28, -12, 24, 1.5, -13, 5)), (.42, (-3, 1.5, -2, 0, .5, -.4), 'out'), (.62, (1, -.5, .6, 0, -.15, .1)),
                  (.85, (0, 0, 0, 0, 0, 0))))
    left_arm(c, [(0, .12, ('to', rig.spec.DRAW_FROM, None, 'io')), (.12, .5, ('to', None, None, 'out'))])
    c.sound(0, 'draw')
    if empty:
        bolt(c, Keys((0, rig.spec.TRAVEL)))
    return c.build()


def fire(rig):
    c = Clip(rig, .4)
    rifle(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.04, (-2.4, .3, -1.2, 0, .15, .9), 'out'), (.16, (.5, -.1, .35, 0, -.05, -.12)),
                  (.4, (0, 0, 0, 0, 0, 0))))
    bolt(c, Keys((0, 0), (.033, rig.spec.TRAVEL, 'lin'), (.083, 0, 'lin')))
    still_left(c)
    return c.build()


def near(pose, by):
    return tuple(a + b for a, b in zip(pose, by))


def on_handle(spec, back, offset=(0, 0, 0)):
    """Where the middle of the right hand is on the handle `back` from home, moved by `offset` (model numbers)."""
    return tuple(h + o + e + (back if i == 2 else 0) for i, (h, o, e) in enumerate(zip(spec.HANDLE, spec.ON_HANDLE, offset)))


def right_hand_rack(c, spec, t, back, pull):
    """The right hand from the wrist to the handle (there at t), pulling it from `back` a further `pull` back (in .28 s for a
    full stroke, .12 s for the empty reload's little pull), letting go, and back to the wrist; the bolt's own keys are the
    caller's. Returns when it lets go."""
    shoulder, stroke = spec.RIGHT_SHOULDER, .28 if pull > 2 else .12
    go = t + stroke
    left_arm(c, [(t - .3, t - .14, ('to', on_handle(spec, back, spec.TO_HANDLE), shoulder, 'in')),
                 (t - .14, t, ('to', on_handle(spec, back), shoulder, 'out')),
                 (t, go, ('follow', lambda u: (on_handle(spec, back + pull * ease((u - t) / stroke, 'io')), shoulder))),
                 (go, go + .12, ('to', on_handle(spec, back + pull, spec.FROM_HANDLE), shoulder, 'out')),
                 (go + .12, go + .48, ('to', None, None, 'io'))], 'rightarm')
    return go


def reload(rig, empty=False):
    """The M14 way. Times: the hand leaves the forend, takes the old magazine (.42), rocks it out and away (gone at 1.12), comes
    back with the new one, hooks it (1.5), rocks it home (1.62); the empty one then works the operating rod."""
    s = rig.spec
    tilt, work, TRAVEL = s.TILT, s.RACK, s.TRAVEL
    end = 2.45 if not empty else 2.85
    c = Clip(rig, end)
    keys = [(0, (0, 0, 0, 0, 0, 0)), (.32, tilt), (.62, near(tilt, (-1.5, 1, -2, .2, -.6, .2))), (1.1, near(tilt, (1, -1, 2, -.1, .3, -.2))),
            (1.62, near(tilt, (-2, 1, -3, .2, -.5, .2))), (1.7, near(tilt, (-.5, .3, -.8, 0, -.1, 0)))]
    if empty:
        keys += [(2.0, work), (2.18, near(work, (1, 0, -1, 0, .2, .4))), (2.24, near(work, (-1.5, .5, 1.5, 0, -.3, -.3))),
                 (2.85, (0, 0, 0, 0, 0, 0))]
    else:
        keys += [(2.45, (0, 0, 0, 0, 0, 0))]
    rifle(c, Keys(*keys))
    out_path, in_path = s.mag_paths(rig)

    def mag(path, name):
        return lambda t: rig.magazine(name, path(t)[0], path(t)[1:])
    old, new = mag(out_path, 'magazine'), mag(in_path, 'magazine2')
    c.matrix('magazine', old)
    c.matrix('magazine2', lambda t: new(max(t, 1.12)))
    shown(c, 'magazine', Keys((0, 1), (1.12, 1, 'lin'), (1.121, 0, 'lin')))
    shown(c, 'magazine2', Keys((0, 0), (1.12, 0, 'lin'), (1.121, 1, 'lin')))
    if empty:
        shown(c, 'bullet', Keys((0, 0)))
    on_old = rig.on_magazine(rig.magazine('magazine'))
    segments = [(0, .22, ('to', s.OFF_FOREND, None, 'in')),                 # down off the forend, back towards the magazine
                (.22, .42, ('to', on_old, None, 'out')),                    # and on it, thumb on the catch
                (.42, 1.12, ('carry', old)), (1.12, 1.62, ('carry', new)),
                (1.62, 1.72, ('to', [a + b for a, b in zip(rig.on_magazine(rig.magazine('magazine2')), (1.0, -2.5, .5))], None, 'out'))]
    if not empty:
        segments += [(1.72, 2.12, ('to', None, None, 'io'))]
        c.sound(.5, s.name + '_unload')
        c.sound(1.6, s.name + '_reload')
        c.sound(2.1, s.name + '_rest')
    else:
        # the left hand back to the forend; the right hand to the handle (held back), a little further back, let go: it flies home
        segments += [(1.72, 2.12, ('to', None, None, 'io'))]
        go = right_hand_rack(c, s, 2.0, TRAVEL, 1.2)
        bolt(c, Keys((0, TRAVEL), (2.0, TRAVEL), (go, TRAVEL + 1.2), (go + .01, TRAVEL + 1.2, 'lin'), (go + .05, 0, 'in')))
        c.sound(.5, s.name + '_unload')
        c.sound(1.6, s.name + '_reload')
        c.sound(2.15, s.name + '_action')
        c.sound(2.5, s.name + '_rest')
    left_arm(c, segments)
    return c.build()


def rack(rig):
    """From a shut bolt: the right hand leaves the wrist for the handle, pulls it all the way back and lets go; the left hand
    stays on the forend."""
    c = Clip(rig, 1.25)
    s = rig.spec
    work, TRAVEL = s.RACK, s.TRAVEL
    rifle(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.28, work), (.62, near(work, (1, 0, -1, 0, .2, .4))), (.7, near(work, (-1.5, .5, 1.5, 0, -.3, -.3))),
                  (1.25, (0, 0, 0, 0, 0, 0))))
    still_left(c)
    go = right_hand_rack(c, s, .3, 0, TRAVEL)
    bolt(c, Keys((0, 0), (.3, 0), (go, TRAVEL), (go + .01, TRAVEL, 'lin'), (go + .05, 0, 'in')))
    c.sound(.6, s.name + '_action')
    return c.build()


def inspect(rig):
    c = Clip(rig, 4.0)
    rifle(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.55, (-6, 24, 28, -2.0, 1.8, 2.5)), (1.5, (-4, 26, 31, -2.2, 2.0, 2.2)),
                  (2.35, (4, -20, -38, 1.5, 1.2, 2.0)), (3.15, (2, -22, -35, 1.7, 1.0, 1.8)), (3.8, (0, 0, 0, 0, 0, 0)),
                  (4.0, (0, 0, 0, 0, 0, 0))))
    still_left(c)
    c.sound(3.75, rig.spec.name + '_rest')
    return c.build()


def running(rig):
    c = Clip(rig, .6, loop=True)
    base = np.array(rig.spec.RUN, float)
    def at(t):
        a = 2 * math.pi * t / .6
        bob = np.array([1.5 * math.sin(2 * a), 1.2 * math.sin(a), 2.0 * math.sin(a), .4 * math.sin(a), -.7 * abs(math.sin(a)), 0])
        return base + bob
    c.set('main', 'rotation', lambda t: at(t)[:3])
    c.set('main', 'position', lambda t: at(t)[3:])
    still_left(c)
    return c.build()


def running_start(rig):
    c = Clip(rig, .3)
    rifle(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.3, rig.spec.RUN)))
    still_left(c)
    return c.build()


def running_end(rig):
    c = Clip(rig, .4)
    rifle(c, Keys((0, rig.spec.RUN), (.4, (0, 0, 0, 0, 0, 0))))
    still_left(c)
    return c.build()


def clips(geo, spec=Spec):
    """Every clip of a rifle (the M1A unless another Spec is given), by its logical name, for its model `geo`."""
    rig = Rig(geo, spec)
    return {'idle': idle(rig), 'idleempty': idle(rig, True), 'draw': draw(rig), 'drawempty': draw(rig, True), 'fire': fire(rig),
            'reload': reload(rig), 'reloadempty': reload(rig, True), 'rack': rack(rig), 'inspect': inspect(rig),
            'running': running(rig), 'runningstart': running_start(rig), 'runningend': running_end(rig)}


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build
    import m1a
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = m1a.geometry(json.loads((Path(__file__).resolve().parent / 'rifle_skeleton.json').read_text()))
    made = clips(geo)
    (out / 'gun_m1a.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_m1a.png').write_bytes(m1a.texture())
    (out / 'gun_m1a.animation.json').write_text(json.dumps({'format_version': '1.8.0', 'animations': {
        'animation.model.' + k: v for k, v in made.items()}}, separators=(',', ':')))
    for k, v in made.items():
        print(f"{k:12s} {v['animation_length']:5.2f}s  " + ' '.join(f'{b}' for b in v['bones']))
