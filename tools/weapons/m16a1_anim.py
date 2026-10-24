"""Our own clips for our own M16 (m16a1.py on rifle.py's layout): what the hands and the rifle do, solved into the bones' turns
and moves and sampled into GeckoLib keys with m1a_anim's rig. No key is taken from the reference set.

    python pistol_anim.py m16a1 --out DIR      writes the model, texture and clips there, for a look

The rest hold is the layout's own (rifle.py): the right fist round the pistol grip, the left hand under the handguard. The clips:
  draw, fire        the rifle comes up from below, the left hand meeting the handguard; the kick and the bolt carrier's cycle
  reload            the rifle turned in, the release pressed: the magazine drops a little, the left hand strips it down and
                    away, comes back with the new one, lines it up under the well and pushes it home
  reloadempty       the same with the bolt carrier held back and an empty magazine; then the hand slaps the bolt catch on the
                    left and the carrier flies home
  (reloademptycharged) the same, ending with the charging handle pulled instead: build_own.py makes the empty reload of a
                    rifle with no magazine in it from this one (owner 2026-10-09: "should be racking not pressing button")
  rack              the hand goes to the charging handle at the back, pulls it and the carrier all the way back, lets go
  inspect           the rifle turned to show its left side, then rolled to show the right (the port), and back
  running*          held low and across while running, bobbing twice a loop
build.py derives the rest from these (unload, the empty and the no-magazine clips), as for every gun.
"""
import math

import numpy as np

import m16a1 as gun
from m1a_anim import Clip, Keys, Rig, ease, left_arm, near, shown, still_left
from m1a_anim import rifle as view


class Spec:
    name = 'm16'
    LEFT_REST = {'rotation': (0.0, 0.0, 0.0), 'position': (0.0, 0.0, 0.0), 'scale': (1.0, 1.0, 1.0)}
    TRAVEL = gun.BOLT_TRAVEL                # the bolt carrier back after a shot, held back on an empty rifle, and the pull
    LUG = (0.0, gun.MAG_TOP, gun.MAG_Z)     # the magazine's pivot (it is never rocked)
    GRIP_MAG = (2.6, 9.0, gun.MAG_Z)        # the middle of the hand on a magazine, in its own upright numbers
    UNDER = 4.5                             # how far under the well the new magazine is lined up
    AWAY = (10.0, -26.0, 6.0)               # how far the old magazine is taken (from its seat): out of sight
    OFF_GUARD = (6.0, 12.0, -22.0)          # the left hand on its way down off the handguard to the magazine
    ON_HANDLE = (1.6, 26.7, 7.4)            # the middle of the hand on the charging handle's left wing
    TO_HANDLE = (5.0, 22.0, 6.0)            # on its way there (from under the rifle), and leaving
    FROM_HANDLE = (5.0, 21.0, 13.0)
    OVER = (8.0, -15.0, 40.0)               # the shoulder the arm reaches the handle from: below and behind the eye, so the arm
                                            # comes up from under the view and never points its hand end at the eye (B2)
    ON_CATCH = (4.2, 19.4, -5.9)            # the middle of the hand pressing the bolt catch (left side, over the well)
    NEAR_CATCH = (6.5, 17.0, -9.0)
    # the whole view's poses (rotation x y z, position x y z): turned in for the magazine, pulled down and away for the handle,
    # the two sides shown, running
    RELOAD = (8, -18, -40, -3.0, 9.0, -8.0)
    CHARGE = (8, -35, 25, -4.0, 4.0, -16.0)
    LEFT_SIDE = (-6, 55, 8, -4.0, 3.0, -8.0)
    RIGHT_SIDE = (4, -22, -34, 1.5, 1.5, 2.0)
    RUN = (8, -45, 24, 3.0, 1.0, -3.0)
    DRAW_FROM = (6.0, 8.0, -30.0)
    DATA = {'phasedReloads': [
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': False}, 'duration': 1700, 'animation': 'animation.model.reload'},
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': True}, 'duration': 2000, 'animation': 'animation.model.reloadempty'}],
        'viewRecoilAmplitude': 1.6}          # the current M16's kick, unchanged

    @classmethod
    def axis(cls):
        """Down the magazine (model numbers): the way it comes out, forward a little with its lean."""
        a = math.radians(gun.MAG_TILT)
        return np.array([0.0, -math.cos(a), math.sin(a)])


DATA = Spec.DATA
NOMAG_EMPTY = ('reloademptycharged', 2.2)     # the clip the no-magazine empty reload is made from, and when it counts as loaded


def bolt(clip, path):
    clip.set('bolt', 'position', lambda t: (0, 0, float(path(t))))


def handle(clip, path):
    clip.set('charging', 'position', lambda t: (0, 0, float(path(t))))


def emptied(clip, rig):
    bolt(clip, Keys((0, rig.spec.TRAVEL)))
    shown(clip, 'bullet', Keys((0, 0)))
    shown(clip, 'bullet2', Keys((0, 0)))


def idle(rig, empty=False):
    c = Clip(rig, .1, loop=True)
    still_left(c)
    if empty:
        emptied(c, rig)
    return c.build()


def draw(rig, empty=False):
    c = Clip(rig, .75)
    view(c, Keys((0, (26, -12, 22, 1.5, -13, 5)), (.4, (-3, 1.5, -2, 0, .5, -.4), 'out'), (.58, (1, -.5, .6, 0, -.15, .1)),
                 (.75, (0, 0, 0, 0, 0, 0))))
    left_arm(c, [(0, .12, ('to', rig.spec.DRAW_FROM, None, 'io')), (.12, .5, ('to', None, None, 'out'))])
    c.sound(0, rig.spec.name + '_draw')
    if empty:
        emptied(c, rig)
    return c.build()


def fire(rig):
    c = Clip(rig, .3)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.035, (-2.2, .3, -1.0, 0, .15, .8), 'out'), (.13, (.4, -.1, .3, 0, -.05, -.1)),
                 (.3, (0, 0, 0, 0, 0, 0))))
    bolt(c, Keys((0, 0), (.03, rig.spec.TRAVEL, 'lin'), (.075, 0, 'lin')))
    still_left(c)
    return c.build()


def reload(rig, empty=False, charge=False):
    """Times: the rifle turns in (.3), the release lets the magazine drop (.45), the hand strips it away (gone at .85), comes back
    with the new one, lines it up under the well (1.2) and pushes it home (1.35); the empty one then slaps the bolt catch, or with
    `charge` pulls the charging handle (released at 1.9)."""
    s = rig.spec
    axis = s.axis()
    end = 2.1 if not empty else 2.8 if charge else 2.45
    c = Clip(rig, end)
    keys = [(0, (0, 0, 0, 0, 0, 0)), (.3, s.RELOAD), (.7, near(s.RELOAD, (1.5, -1, 2, 0, -.3, 0))),
            (1.35, near(s.RELOAD, (-1, 1, -2, 0, .2, 0))), (1.42, near(s.RELOAD, (-2.5, .5, -2, 0, .6, .2)), 'out')]
    if empty and charge:
        keys += [(1.65, s.CHARGE), (1.9, near(s.CHARGE, (1, 0, -1, 0, .2, .5))), (1.97, near(s.CHARGE, (-2, .5, 1.5, 0, .3, .6)), 'out'),
                 (2.65, (0, 0, 0, 0, 0, 0))]
    elif empty:
        keys += [(1.55, near(s.RELOAD, (-1, 1, 3, 0, .2, 0))), (1.6, near(s.RELOAD, (1, 0, 4, 0, -.2, 0)), 'out'),
                 (2.3, (0, 0, 0, 0, 0, 0))]
    else:
        keys += [(1.95, (0, 0, 0, 0, 0, 0))]
    view(c, Keys(*keys))
    away = np.array(s.AWAY)
    old_path = Keys((0, (0, 0, 0)), (.38, (0, 0, 0)), (.45, tuple(axis * 2.5), 'in'), (.85, tuple(axis * 6 + away), 'in'))
    new_path = Keys((.85, tuple(axis * 6 + away)), (1.2, tuple(axis * s.UNDER), 'out'), (1.35, (0, 0, 0), 'io'))

    def mag(path, name):
        return lambda t: rig.magazine(name, 0.0, path(t))
    old, new = mag(old_path, 'magazine'), mag(new_path, 'magazine2')
    c.matrix('magazine', old)
    c.matrix('magazine2', lambda t: new(max(t, .85)))
    shown(c, 'magazine', Keys((0, 1), (.85, 1, 'lin'), (.851, 0, 'lin')))
    shown(c, 'magazine2', Keys((0, 0), (.85, 0, 'lin'), (.851, 1, 'lin')))
    segments = [(0, .22, ('to', s.OFF_GUARD, None, 'in')),                      # down off the handguard,
                (.22, .45, ('to', rig.on_magazine(old(.45)), None, 'out')),     # onto the magazine as it drops,
                (.45, .85, ('carry', old)), (.85, 1.37, ('carry', new))]       # away with it, back with the new one
    c.sound(.4, s.name + '_unload')
    c.sound(1.35, s.name + '_reload')
    if not empty:
        segments += [(1.37, 1.7, ('to', None, None, 'io'))]
        c.sound(1.9, s.name + '_rest')
    elif charge:
        def on(back):
            return (s.ON_HANDLE[0], s.ON_HANDLE[1], s.ON_HANDLE[2] + back)
        segments += [(1.37, 1.55, ('to', s.TO_HANDLE, s.OVER, 'in')), (1.55, 1.67, ('to', on(0), s.OVER, 'out')),
                     (1.67, 1.9, ('follow', lambda t: (on(s.TRAVEL * ease((t - 1.67) / .23, 'io')), s.OVER))),
                     (1.9, 2.02, ('to', s.FROM_HANDLE, s.OVER, 'out')), (2.02, 2.4, ('to', None, None, 'io'))]
        bolt(c, Keys((0, s.TRAVEL), (1.9, s.TRAVEL), (1.91, s.TRAVEL, 'lin'), (1.95, 0, 'in')))
        handle(c, Keys((0, 0), (1.67, 0), (1.9, s.TRAVEL), (1.91, s.TRAVEL, 'lin'), (1.95, 0, 'in')))
        shown(c, 'bullet', Keys((0, 0)))
        c.sound(1.93, s.name + '_action')
        c.sound(2.6, s.name + '_rest')
    else:
        segments += [(1.37, 1.48, ('to', s.NEAR_CATCH, None, 'in')), (1.48, 1.55, ('to', s.ON_CATCH, None, 'out')),
                     (1.55, 1.95, ('to', None, None, 'io'))]
        bolt(c, Keys((0, s.TRAVEL), (1.55, s.TRAVEL), (1.56, s.TRAVEL, 'lin'), (1.6, 0, 'in')))
        shown(c, 'bullet', Keys((0, 0)))
        c.sound(1.56, s.name + '_action')
        c.sound(2.25, s.name + '_rest')
    left_arm(c, segments)
    return c.build()


def rack(rig):
    """The charging handle: the rifle pulled down and away, the hand on the handle, all the way back with the carrier, let go."""
    s = rig.spec
    c = Clip(rig, 1.3)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.3, s.CHARGE), (.55, near(s.CHARGE, (1, 0, -1, 0, .2, .5))),
                 (.62, near(s.CHARGE, (-2, .5, 1.5, 0, .3, .6)), 'out'), (1.3, (0, 0, 0, 0, 0, 0))))

    def on(back):
        return (s.ON_HANDLE[0], s.ON_HANDLE[1], s.ON_HANDLE[2] + back)
    left_arm(c, [(0, .18, ('to', s.TO_HANDLE, s.OVER, 'in')), (.18, .32, ('to', on(0), s.OVER, 'out')),
                 (.32, .55, ('follow', lambda t: (on(s.TRAVEL * ease((t - .32) / .23, 'io')), s.OVER))),
                 (.55, .68, ('to', s.FROM_HANDLE, s.OVER, 'out')), (.68, 1.1, ('to', None, None, 'io'))])
    pulled = Keys((0, 0), (.32, 0), (.55, s.TRAVEL), (.56, s.TRAVEL, 'lin'), (.6, 0, 'in'))
    bolt(c, pulled)
    handle(c, pulled)
    c.sound(.58, s.name + '_action')
    return c.build()


def inspect(rig, empty=False):
    s = rig.spec
    c = Clip(rig, 4.0)
    view(c, Keys((0, (0, 0, 0, 0, 0, 0)), (.55, s.LEFT_SIDE), (1.5, near(s.LEFT_SIDE, (2, 2, 3, -.2, .2, -.3))), (2.35, s.RIGHT_SIDE),
                 (3.15, near(s.RIGHT_SIDE, (-2, -2, 3, .2, -.5, -.2))), (3.8, (0, 0, 0, 0, 0, 0)), (4.0, (0, 0, 0, 0, 0, 0))))
    still_left(c)
    c.sound(3.75, s.name + '_rest')
    if empty:
        emptied(c, rig)
    return c.build()


def running(rig):
    c = Clip(rig, .6, loop=True)
    base = np.array(rig.spec.RUN, float)

    def at(t):
        a = 2 * math.pi * t / .6
        return base + np.array([1.5 * math.sin(2 * a), 1.2 * math.sin(a), 2.0 * math.sin(a), .4 * math.sin(a), -.7 * abs(math.sin(a)), 0])
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


def clips(geo, spec=Spec):
    rig = Rig(geo, spec)
    return {'idle': idle(rig), 'idleempty': idle(rig, True), 'draw': draw(rig), 'drawempty': draw(rig, True), 'fire': fire(rig),
            'reload': reload(rig), 'reloadempty': reload(rig, True), 'reloademptycharged': reload(rig, True, True), 'rack': rack(rig),
            'inspect': inspect(rig),
            'inspectempty': inspect(rig, True), 'running': running(rig), 'runningstart': running_start(rig),
            'runningend': running_end(rig)}
