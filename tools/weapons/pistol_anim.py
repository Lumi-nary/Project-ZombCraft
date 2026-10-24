"""Our own clips for our own pistols (pistol.py's layout): what the hands and the gun do, solved into the bones' turns and moves
and sampled into GeckoLib keys with m1a_anim's rig. No key is taken from the reference set.

    python pistol_anim.py m9 --out DIR      writes gun_m9.geo.json, gun_m9.png and gun_m9.animation.json there, for a look
                                            (build_own.py calls clips() and DATA through the kind's <kind>_anim module)

The rest hold is the layout's own (pistol.py): the right fist round the grip, the left hand round it. The clips:
  draw, fire        the gun comes up from below, the left hand joining it; the kick, the slide's cycle, the hammer falling and
                    cocked again by the slide
  reload            the gun turned in, the catch pressed: the magazine drops a little, the left hand strips it down and away,
                    comes back with the new one, lines it up under the grip and pushes it home
  reloadempty       the same with the slide held back by the slide stop and an empty magazine; then the hand goes over the top,
                    takes the back of the slide, pulls it the rest of the way and lets go: it flies home
  rack              from a shut slide: over the top, pull it all the way back, let go
  inspect           the left hand off, the gun turned to show its left side, then rolled to show the right, and back
  running*          held low and in while running, bobbing twice a loop
build.py derives the rest from these (unload, the empty and the no-magazine clips), as for every gun.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np

from m1a_anim import Clip, Keys, Rig, left_arm, near, shown, still_left
from m1a_anim import rifle as view


class Spec:
    """One pistol's numbers for these clips (model numbers, 1/16 block). Subclassed by each pistol's <kind>_anim module."""
    name = 'pistol'
    LEFT_REST = {'rotation': (0.0, 0.0, 0.0), 'position': (0.0, 0.0, 0.0), 'scale': (1.0, 1.0, 1.0)}
    TRAVEL, LOCK = 5.0, 4.6                 # the slide all the way back, and held by the slide stop
    HAMMER_DOWN = 62.0                      # the hammer's turn from cocked to resting on the firing pin
    TILT = 13.4                             # the grip's lean: the magazine goes in and out along it
    LUG = (0.0, 19.0, 2.0)                  # the magazine's pivot (it is never rocked)
    GRIP_MAG = (2.2, 9.0, 2.5)              # the middle of the hand on a magazine, in its own upright numbers
    ON_SLIDE = (.8, 26.6, 4.6)              # the middle of the hand over the back of the shut slide
    # the whole view's poses (rotation x y z, position x y z): turned in for the magazine, rolled to give the slide's top to the
    # left hand, the two sides shown, running
    RELOAD = (-24, -16, -30, -2.0, 8.0, -1.0)
    RACK = (-6, -10, 12, -1.5, 3.0, 1.0)
    LEFT_SIDE = (-6, 58, 22, -3.0, 3.0, 1.5)
    RIGHT_SIDE = (-4, -48, -28, 2.0, 2.5, 1.5)
    RUN = (30, -26, 12, 1.5, -5.0, 2.0)
    DRAW_FROM = (9.0, 2.0, 0.0)             # where the left hand comes from as the gun comes up
    OFF_GRIP = (6.0, 5.0, 3.0)              # on its way down off the grip to the magazine
    AWAY = (9.0, -20.0, 4.0)                # how far the old magazine is taken (from its seat): out of sight
    UNDER = 4.5                             # how far under the grip the new one is lined up
    OVER = (40.0, -5.0, 10.0)               # the shoulder the arm reaches over the slide from: well out to the left, low and a
                                            # little behind the slide, so the forearm comes over from the lower left and never
                                            # points its fist at the eye (release/BUGS.md B2: from in front of the slide it did)
    TO_SLIDE = (6.0, 28.5, 1.0)             # on its way over the slide, and leaving it (down and to the left, not back at the eye)
    FROM_SLIDE = (12.0, 20.0, -4.0)
    PUT_AWAY = (8.0, -6.0, 10.0)            # the left hand out of sight while the gun is looked at
    DATA = {'phasedReloads': [
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': False}, 'duration': 1350, 'animation': 'animation.model.reload'},
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': True}, 'duration': 1750, 'animation': 'animation.model.reloadempty'}],
        'viewRecoilAmplitude': 3.0}

    @classmethod
    def axis(cls):
        """Down the grip (model numbers): the way a magazine comes out."""
        a = math.radians(cls.TILT)
        return np.array([0.0, -math.cos(a), math.sin(a)])


# ---------------------------------------------------------------- bones of the action

def slide(clip, path):
    clip.set('slide', 'position', lambda t: (0, 0, float(path(t))))


def hammer(clip, path):
    clip.set('hammer', 'rotation', lambda t: (float(path(t)), 0, 0))


def emptied(clip, rig):
    """The gun after its last round: the slide held back, no round on top of either magazine."""
    slide(clip, Keys((0, rig.spec.LOCK)))
    shown(clip, 'bullet', Keys((0, 0)))
    shown(clip, 'bullet2', Keys((0, 0)))


def on_slide(rig, back):
    s = rig.spec
    return (s.ON_SLIDE[0], s.ON_SLIDE[1], s.ON_SLIDE[2] + back)


# ---------------------------------------------------------------- clips

def idle(rig, empty=False):
    c = Clip(rig, .1, loop=True)
    still_left(c)
    if empty:
        emptied(c, rig)
    return c.build()


def draw(rig, empty=False):
    c = Clip(rig, .55)
    view(c, Keys((0, (34, -10, 18, 1.5, -12, 4)), (.32, (-4, 1, -2, 0, .4, -.3), 'out'), (.45, (1, -.3, .5, 0, -.1, .1)),
                 (.55, (0, 0, 0, 0, 0, 0))))
    left_arm(c, [(0, .1, ('to', rig.spec.DRAW_FROM, None, 'io')), (.1, .42, ('to', None, None, 'out'))])
    c.sound(0, rig.spec.name + '_draw')
    if empty:
        emptied(c, rig)
    return c.build()


def fire(rig):
    s = rig.spec
    c = Clip(rig, .3)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.035, (-7, .5, -1.5, 0, .6, 1.4), 'out'), (.13, (1.2, -.2, .4, 0, -.1, -.2)),
                 (.3, (0, 0, 0, 0, 0, 0))))
    slide(c, Keys((0, 0), (.025, s.TRAVEL, 'lin'), (.065, 0, 'lin')))
    hammer(c, Keys((0, s.HAMMER_DOWN), (.012, s.HAMMER_DOWN), (.03, 0, 'lin')))      # struck, then cocked by the slide
    still_left(c)
    return c.build()


def reload(rig, empty=False):
    """Times: the gun turns in (.25), the catch lets the magazine drop (.33), the hand strips it away (gone at .68), comes back
    with the new one, lines it up under the grip (1.0) and pushes it home (1.12); the empty one then racks the slide."""
    s = rig.spec
    axis = s.axis()
    end = 1.75 if not empty else 2.05
    c = Clip(rig, end)
    keys = [(0, (0, 0, 0, 0, 0, 0)), (.25, s.RELOAD), (.6, near(s.RELOAD, (1.5, -1, 2, 0, -.3, 0))),
            (1.12, near(s.RELOAD, (-1, 1, -2, 0, .2, 0))), (1.17, near(s.RELOAD, (-3, .5, -2.5, 0, .7, .2)), 'out')]
    if empty:
        keys += [(1.32, s.RACK), (1.42, near(s.RACK, (1, 0, -1, 0, .2, .5))), (1.47, near(s.RACK, (-3, .5, 1.5, 0, .3, .6)), 'out'),
                 (2.0, (0, 0, 0, 0, 0, 0))]
    else:
        keys += [(1.6, (0, 0, 0, 0, 0, 0))]
    view(c, Keys(*keys))
    away = np.array(s.AWAY)
    old_path = Keys((0, (0, 0, 0)), (.25, (0, 0, 0)), (.33, tuple(axis * 3.5), 'in'), (.68, tuple(axis * 6 + away), 'in'))
    new_path = Keys((.68, tuple(axis * 6 + away)), (1.0, tuple(axis * s.UNDER), 'out'), (1.12, (0, 0, 0), 'io'))

    def mag(path, name):
        return lambda t: rig.magazine(name, 0.0, path(t))
    old, new = mag(old_path, 'magazine'), mag(new_path, 'magazine2')
    c.matrix('magazine', old)
    c.matrix('magazine2', lambda t: new(max(t, .68)))
    shown(c, 'magazine', Keys((0, 1), (.68, 1, 'lin'), (.681, 0, 'lin')))
    shown(c, 'magazine2', Keys((0, 0), (.68, 0, 'lin'), (.681, 1, 'lin')))
    segments = [(0, .18, ('to', s.OFF_GRIP, None, 'in')),                      # down off the grip,
                (.18, .33, ('to', rig.on_magazine(old(.33)), None, 'out')),     # onto the magazine as it drops,
                (.33, .68, ('carry', old)), (.68, 1.14, ('carry', new))]       # away with it, back with the new one
    c.sound(.27, s.name + '_unload')
    c.sound(1.12, s.name + '_reload')
    if not empty:
        segments += [(1.14, 1.45, ('to', None, None, 'io'))]
        c.sound(1.55, s.name + '_rest')
    else:
        segments += [(1.14, 1.26, ('to', s.TO_SLIDE, s.OVER, 'in')), (1.26, 1.32, ('to', on_slide(rig, s.LOCK), s.OVER, 'out')),
                     (1.32, 1.42, ('follow', lambda t: (on_slide(rig, s.LOCK + (s.TRAVEL - s.LOCK) * min(1, (t - 1.32) / .1)), s.OVER))),
                     (1.42, 1.54, ('to', s.FROM_SLIDE, s.OVER, 'out')), (1.54, 1.85, ('to', None, None, 'io'))]
        slide(c, Keys((0, s.LOCK), (1.32, s.LOCK), (1.42, s.TRAVEL), (1.43, s.TRAVEL, 'lin'), (1.47, 0, 'in')))
        shown(c, 'bullet', Keys((0, 0)))
        c.sound(1.44, s.name + '_action')
        c.sound(1.95, s.name + '_rest')
    left_arm(c, segments)
    return c.build()


def rack(rig):
    """From a shut slide: over the top, all the way back, let go."""
    s = rig.spec
    c = Clip(rig, 1.0)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.22, s.RACK), (.44, near(s.RACK, (1, 0, -1, 0, .2, .5))),
                 (.5, near(s.RACK, (-3, .5, 1.5, 0, .3, .6)), 'out'), (1.0, (0, 0, 0, 0, 0, 0))))
    left_arm(c, [(0, .12, ('to', s.TO_SLIDE, s.OVER, 'in')), (.12, .24, ('to', on_slide(rig, 0), s.OVER, 'out')),
                 (.24, .44, ('follow', lambda t: (on_slide(rig, s.TRAVEL * min(1, max(0, (t - .24) / .2))), s.OVER))),
                 (.44, .56, ('to', s.FROM_SLIDE, s.OVER, 'out')), (.56, .9, ('to', None, None, 'io'))])
    slide(c, Keys((0, 0), (.24, 0), (.44, s.TRAVEL), (.45, s.TRAVEL, 'lin'), (.49, 0, 'in')))
    c.sound(.46, s.name + '_action')
    return c.build()


def inspect(rig, empty=False):
    s = rig.spec
    c = Clip(rig, 3.2)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.5, s.LEFT_SIDE), (1.3, near(s.LEFT_SIDE, (2, -4, 3, 0, .2, 0))), (1.9, s.RIGHT_SIDE),
                 (2.55, near(s.RIGHT_SIDE, (-2, 4, -3, 0, .2, 0))), (3.05, (0, 0, 0, 0, 0, 0)), (3.2, (0, 0, 0, 0, 0, 0))))
    left_arm(c, [(0, .3, ('to', s.PUT_AWAY, None, 'in')), (.3, 2.75, ('to', s.PUT_AWAY, None, 'lin')),
                 (2.75, 3.1, ('to', None, None, 'out'))])
    c.sound(3.0, s.name + '_rest')
    if empty:
        emptied(c, rig)
    return c.build()


def running(rig):
    c = Clip(rig, .6, loop=True)
    base = np.array(rig.spec.RUN, float)

    def at(t):
        a = 2 * math.pi * t / .6
        return base + np.array([2.0 * math.sin(2 * a), 1.5 * math.sin(a), 2.5 * math.sin(a), .4 * math.sin(a), -.8 * abs(math.sin(a)), 0])
    c.set('main', 'rotation', lambda t: at(t)[:3])
    c.set('main', 'position', lambda t: at(t)[3:])
    still_left(c)
    return c.build()


def running_start(rig):
    c = Clip(rig, .3)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.3, rig.spec.RUN)))
    still_left(c)
    return c.build()


def running_end(rig):
    c = Clip(rig, .4)
    view(c, Keys((0, rig.spec.RUN), (.4, (0, 0, 0, 0, 0, 0))))
    still_left(c)
    return c.build()


def clips(geo, spec):
    """Every clip of a pistol, by its logical name, for its model `geo`."""
    rig = Rig(geo, spec)
    return {'idle': idle(rig), 'idleempty': idle(rig, True), 'draw': draw(rig), 'drawempty': draw(rig, True), 'fire': fire(rig),
            'reload': reload(rig), 'reloadempty': reload(rig, True), 'rack': rack(rig), 'inspect': inspect(rig),
            'inspectempty': inspect(rig, True), 'running': running(rig), 'runningstart': running_start(rig),
            'runningend': running_end(rig)}


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    ap = argparse.ArgumentParser()
    ap.add_argument('kind')
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    model, anim = __import__(a.kind), __import__(a.kind + '_anim')
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = model.geometry()
    made = anim.clips(geo)
    (out / f'gun_{a.kind}.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / f'gun_{a.kind}.png').write_bytes(model.texture())
    (out / f'gun_{a.kind}_base.json').write_text(json.dumps({'display': model.skeleton()['display']}))
    (out / f'gun_{a.kind}.animation.json').write_text(json.dumps({'format_version': '1.8.0', 'animations': {
        'animation.model.' + k: v for k, v in made.items()}}, separators=(',', ':')))
    for k, v in made.items():
        print(f"{k:12s} {v['animation_length']:5.2f}s  " + ' '.join(f'{b}' for b in v['bones']))
