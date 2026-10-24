"""Our own M16 Assault Rifle (PZ's Base.AssaultRifle): the model and its texture, made here from nothing but numbers (m1a.py's
cube helpers, packing and painting; rifle.py's joint layout). The kind keeps the id `m16a1` the game registered.

    python m16a1.py [--out DIR]     writes gun_m16a1.geo.json and gun_m16a1.png there, for a look

The side view follows PZ's own mesh at our rifles' scale (pzmesh.py M16_Rifle --length 109.8 --muzzle -74 --top 30.0: 201 units
a PZ metre, as the M1A against PZ's M14), placed so that its rear sight and butt are where the M1A's are: the flash hider at
z -74, the butt at 35.8, the top of the rear sight 30.0, the bottom of the magazine 1.8. PZ's M16 is the A2 kind: a birdcage
flash hider, the A-frame front sight with its post between two ears, a round handguard narrowing to the front with a cap and a
delta ring, the upper receiver with the carry handle and the rear sight's aperture between its ears, the ejection port with the
bolt carrier in it and a brass deflector behind on the right, the forward assist, the charging handle at the back; the lower
with the magazine well, the guard, the trigger, the bolt catch and selector on the left, the magazine release on the right; a
pistol grip leaning back 29 degrees, a fixed stock; a curved 30-round magazine. Black aluminium, black plastic furniture, dark
parkerized barrel. No part of it is taken from the reference set.
"""
import argparse
import json
import math
from pathlib import Path

import m1a
import pistol
import rifle
from m1a import Rifle

BORE, MUZZLE, BUTT = 22.85, -74.0, 35.8
UPPER_HALF, LOWER_HALF = 2.2, 2.0
UPPER_LOW, UPPER_TOP = 20.6, 25.7        # the upper receiver, under the carry handle
RECEIVER_FRONT, RECEIVER_BACK = -14.3, 6.0
PORT = (-10.0, -3.8, 23.0, 25.1)         # the ejection port on the right: z from, to, y from, to
SIGHT_Y, APERTURE_Z = 29.3, (1.8, 3.4)   # the line of sight; the aperture plate between the rear ears
FRONT_POST_Z = (-54.4, -54.0)
GRIP_AT, GRIP_TILT = (11.0, 5.3), 29.0   # the pistol grip: what it turns about (y, z), how far it leans back
MAG_TOP, MAG_Z, MAG_HALF_DEPTH, MAG_HALF = 19.5, -8.3, 3.45, 1.25
MAG_TILT, MAG_STRAIGHT, MAG_BEND = -11.0, 10.2, -4.0     # its lean, the straight top part's length, the slight bend below it
BOLT_TRAVEL = 6.0                       # how far the bolt carrier (and the charging handle, when pulled) goes back
ROUND = (1.1, 5.8)                      # a 5.56 cartridge: width and length (a neck and a pointed bullet)


class M16(rifle.Rifle):
    name = 'm16a1'
    MAIN = (0.0, 18.0, 0.0)
    BOLT = (0.0, 24.0, -6.0)
    CHARGING = (0.0, 25.2, 5.5)
    MAG = (0.0, MAG_TOP, MAG_Z)
    MAG_TILT = MAG_TILT
    SIGHT = (SIGHT_Y, APERTURE_Z[1])
    BORE, MUZZLE = BORE, MUZZLE
    EJECT = (-2.2, 24.1, -7.0)
    RIGHT = ((0.0, 11.5, 5.2), (-7.0, 2.0, 32.0))      # the fist round the pistol grip
    LEFT = ((1.5, 18.0, -36.0), (10.0, -14.0, 10.0))   # the support hand under the handguard (the arm from low enough that
                                                       # its shoulder end stays under the view: release/BUGS.md B2)
    FIRST_PERSON = {'rotation': [0, 0, 3], 'translation': [-1.0, -19.0, -7.5], 'scale': [.75, .75, .75]}


ALUMINIUM = ((22, 22, 24), (31, 31, 34), (41, 42, 45), (53, 54, 58), (68, 69, 74))       # black anodised
PLASTIC = ((18, 18, 19), (26, 26, 27), (35, 35, 37), (45, 45, 48), (58, 58, 62))
MATERIALS = dict(m1a.MATERIALS, **{
    'upper':  (ALUMINIUM, 2, False),
    'lower':  (ALUMINIUM, 2, False),
    'guard':  (PLASTIC, 2, False),       # handguard, grip, stock (ribs on the handguard: decor)
    'steel':  (m1a.STEEL, 2, False),     # barrel, front sight, flash hider, rings
    'dark':   (m1a.STEEL, 1, False),
    'bright': (m1a.STEEL[2:] + ((104, 106, 112), (126, 128, 134)), 2, False),    # the bolt carrier
    'mag':    (((30, 32, 30), (40, 43, 40), (52, 55, 51), (66, 69, 64), (82, 86, 80)), 2, False),   # a grey-green magazine
    'hole':   (((8, 8, 9),) * 5, 2, False),
})


def pistol_grip(r, half, up, down, half_depth, **more):
    return pistol.leaning_box(r, 'gun', 'guard', half, up, down, half_depth, GRIP_AT, GRIP_TILT, **more)


def rounded(r, bone, material, radius, z0, z1, cy=BORE, cx=0, sides=12):
    """A closed polygonal cylinder made from tangent panels and a solid core."""
    half = radius * math.sin(math.pi / sides)
    distance = radius * math.cos(math.pi / sides)
    wall = min(.22, radius * .3)
    r.box(bone, material, cx - radius * .68, cx + radius * .68,
          cy - radius * .68, cy + radius * .68, z0, z1)
    for i in range(sides):
        r.box(bone, material, cx - half - .025, cx + half + .025,
              cy + distance - wall, cy + distance + .015, z0, z1,
              turn=(0, 0, i * 360 / sides), about=(cx, cy, (z0 + z1) / 2))


def rifle_cubes():
    r = Rifle()
    g = 'gun'
    # ---- muzzle: the birdcage flash hider with its slots, the barrel
    rounded(r, g, 'steel', 1.45, MUZZLE, -69.0)
    for z in (-73.0, -71.4):
        r.box(g, 'hole', -1.5, 1.5, BORE - .25, BORE + .25, z, z + .9, inflate=.02)
    r.box(g, 'hole', -.45, .45, BORE - .45, BORE + .45, MUZZLE - .03, MUZZLE + .5)            # the bore
    rounded(r, g, 'steel', .95, -69.0, RECEIVER_FRONT)
    # ---- front sight: its base round the barrel, the A-frame up to the post between two ears, bayonet lug, sling swivel
    r.beam(g, 'steel', 1.35, BORE - 1.6, BORE + 1.5, -55.6, -52.4, cut=.35)
    r.slab(g, 'steel', .5, (-55.6, BORE + 1.3), (-54.8, 28.6), .9, up=True, cut=.15)         # the front leg
    r.slab(g, 'steel', .5, (-50.6, BORE + 1.3), (-53.6, 28.6), .9, up=False, cut=.15)        # the back leg, leaning forward
    r.box(g, 'steel', -.5, .5, 28.2, 28.8, -55.0, -53.4)                                      # the bridge at the top
    for side in (-1, 1):
        r.box(g, 'steel', min(side * .45, side * .8), max(side * .45, side * .8), 28.2, SIGHT_Y + .45, -55.0, -53.4)   # the ears
    r.box(g, 'dark', -.2, .2, 28.2, SIGHT_Y, *FRONT_POST_Z)                                  # the post
    r.box(g, 'steel', -.45, .45, BORE - 2.8, BORE - 1.5, -55.6, -54.0)                       # bayonet lug
    r.box(g, 'steel', -.8, .8, BORE - 3.0, BORE - 2.7, -53.2, -52.6)                          # sling swivel
    r.box(g, 'steel', -.8, .8, BORE - 2.8, BORE - 1.6, -53.2, -53.0)
    # ---- handguard: its cap, the two halves narrowing to the front, the delta ring and the barrel nut behind it
    r.beam(g, 'steel', 2.9, 20.4, 25.5, -50.3, -49.3, cut=.9)
    # Tapered round A2 furniture with physical ribs for the first-person silhouette.
    for i in range(12):
        z0 = -49.4 + i * 32.2 / 12
        z1 = -49.4 + (i + 1) * 32.2 / 12 + .025
        rounded(r, g, 'guard', 2.85 + (i + .5) / 12 * .85, z0, z1, cy=22.8)
    for i in range(11):
        z = -47.9 + i * 2.9
        rounded(r, g, 'guard', 2.85 + (z + 49.4) / 32.2 * .85 + .14, z, z + .6, cy=22.8)
    rounded(r, g, 'steel', 4.0, -17.3, -15.0, cy=22.8)
    r.beam(g, 'steel', 1.6, BORE - 1.6, BORE + 1.6, -15.1, RECEIVER_FRONT + .1, cut=.45)      # barrel nut
    # ---- upper receiver, the port on the right with the bolt carrier in it, deflector, forward assist
    pz0, pz1, py0, py1 = PORT
    r.box(g, 'upper', -1.6, UPPER_HALF, UPPER_LOW, UPPER_TOP, RECEIVER_FRONT, RECEIVER_BACK, edges=('bottom',))
    r.box(g, 'upper', -UPPER_HALF, -1.6, UPPER_LOW, UPPER_TOP, RECEIVER_FRONT, pz0, edges=('bottom',))
    r.box(g, 'upper', -UPPER_HALF, -1.6, UPPER_LOW, UPPER_TOP, pz1, RECEIVER_BACK, edges=('bottom',))
    r.box(g, 'upper', -UPPER_HALF, -1.6, UPPER_LOW, py0, pz0, pz1, edges=('bottom',))
    r.box(g, 'upper', -UPPER_HALF, -1.6, py1, UPPER_TOP, pz0, pz1, edges=())
    r.box('bolt', 'bright', -1.58, 1.0, py0 + .05, py1 - .05, -10.6, -2.4)                    # bolt carrier
    r.box(g, 'upper', -UPPER_HALF - .6, -UPPER_HALF, 24.3, 25.6, -3.6, -1.4)                 # brass deflector
    r.rod(g, 'dark', .55, -.6, 3.2, cy=23.9, cx=-UPPER_HALF - .4)                            # forward assist
    r.box(g, 'dark', -UPPER_HALF - 1.0, -UPPER_HALF - .1, 23.2, 24.6, 2.6, 3.6)
    # ---- carry handle: its front post, the bar, the rear sight between its ears, the windage knob
    r.box(g, 'upper', -1.2, 1.2, UPPER_TOP, 29.2, -13.9, -12.6)
    r.slab(g, 'upper', 1.25, (-13.9, 29.2), (-.2, 29.95), 1.05, cut=.3)
    r.box(g, 'upper', -1.4, 1.4, UPPER_TOP, 28.6, -.4, APERTURE_Z[1], edges=())
    r.slab(g, 'upper', 1.38, (APERTURE_Z[1] - .1, 29.9), (5.4, UPPER_TOP - .1), 2.2, cut=.3)   # its back, sloping down
    for side in (-1, 1):
        r.box(g, 'upper', min(side * .55, side * 1.35), max(side * .55, side * 1.35), 28.5, 30.0, APERTURE_Z[0], APERTURE_Z[1])
    y, (z0, z1) = SIGHT_Y, APERTURE_Z
    r.box(g, 'dark', -.55, .55, 28.5, y - .35, z0 + .4, z0 + .7)                             # the aperture: a hole in a plate
    r.box(g, 'dark', -.55, .55, y + .35, 29.9, z0 + .4, z0 + .7)
    r.box(g, 'dark', -.55, -.35, y - .35, y + .35, z0 + .4, z0 + .7)
    r.box(g, 'dark', .35, .55, y - .35, y + .35, z0 + .4, z0 + .7)
    r.knob(g, 'dark', .6, -1.9, -1.4, 28.2, .8)                                              # windage knob (right)
    # ---- charging handle at the back of the upper: its shaft and the T
    r.box('charging', 'dark', -.5, .5, 24.95, 25.55, 2.5, RECEIVER_BACK + .5)
    r.box('charging', 'dark', -1.6, 1.6, 24.9, 25.6, RECEIVER_BACK - .1, RECEIVER_BACK + .7)
    # ---- lower receiver: the body, the magazine well, the guard, trigger, catch, selector, release, pins
    r.box(g, 'lower', -LOWER_HALF, LOWER_HALF, 15.8, UPPER_LOW, -13.8, RECEIVER_BACK, edges=('bottom',))
    r.beam(g, 'lower', LOWER_HALF - .05, 13.5, 16.0, -13.4, -5.9, cut=.35, top=False)          # the well's mouth
    r.bars(g, 'lower', .5, [(-5.4, 16.6), (-5.4, 13.7), (-4.7, 13.2), (.6, 13.2), (1.1, 13.8)], .8)
    r.bars(g, 'dark', .3, [(-.9, 17.4), (-1.1, 16.0), (-1.6, 15.0), (-2.2, 14.6)], .5)       # trigger
    r.box(g, 'dark', LOWER_HALF, LOWER_HALF + .3, 18.0, 20.2, -6.6, -5.2)                    # bolt catch (left)
    r.knob(g, 'dark', .55, LOWER_HALF, LOWER_HALF + .3, 19.4, 2.6)                           # selector (left)
    r.box(g, 'dark', LOWER_HALF, LOWER_HALF + .25, 19.1, 19.7, 2.6, 4.2)
    r.knob(g, 'dark', .55, -LOWER_HALF - .4, -LOWER_HALF, 17.4, -4.8)                        # magazine release (right)
    for z in (-13.2, 5.3):                                                                    # takedown pins
        r.box(g, 'dark', -LOWER_HALF - .08, LOWER_HALF + .08, 19.9, 20.5, z - .3, z + .3)
    # ---- pistol grip, stock, butt plate
    pistol_grip(r, 1.5, 5.5, 4.3, 2.71, edges=())
    r.beam(g, 'guard', 1.75, 20.2, 24.1, RECEIVER_BACK, 20.0, cut=.6, bottom=False)          # stock: its top, narrow in front,
    r.beam(g, 'guard', 2.2, 20.0, 24.1, 19.9, BUTT - .8, cut=.6, bottom=False)               # wider behind
    r.slab(g, 'guard', 1.7, (6.6, 18.8), (20.0, 14.7), 5.3, up=True, cut=.6)                 # its underside, falling to the toe
    r.slab(g, 'guard', 2.15, (19.9, 14.73), (BUTT - .8, 10.1), 5.4, up=True, cut=.6)
    r.slab(g, 'guard', 2.12, (19.9, 19.0), (BUTT - .8, 14.4), 5.0, up=True, inside=True)       # filling up to its top
    r.box(g, 'guard', -1.7, 1.7, 18.0, 22.0, RECEIVER_BACK - .2, 8.0, edges=())
    r.beam(g, 'dark', 2.4, 10.0, 24.2, BUTT - .8, BUTT, cut=.5)                               # butt plate
    r.box(g, 'dark', -.6, .6, 10.4, 11.6, 31.0, 33.0)                                        # rear sling swivel
    # ---- magazines (upright here; their bones lean them forward): the straight top, the bend, the floor plate, the top round
    t, f, bk, h = MAG_TOP, MAG_Z - MAG_HALF_DEPTH, MAG_Z + MAG_HALF_DEPTH, MAG_HALF
    joint = t - MAG_STRAIGHT
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        r.beam(bone, 'mag', h, joint, t, f, bk, cut=.3)
        r.beam(bone, 'mag', h - .02, joint - 6.4, joint + .4, f, bk, cut=.3, turn=(MAG_BEND, 0, 0), about=(0, joint, bk))
        r.beam(bone, 'dark', h + .15, joint - 7.0, joint - 6.3, f - .3, bk + .3, cut=.2, turn=(MAG_BEND, 0, 0), about=(0, joint, bk))
        for side in (-1, 1):
            r.box(bone, 'mag', min(side * (h - .15), side * h), max(side * (h - .15), side * h), t, t + .3, MAG_Z - .5, bk)
        width, length = ROUND
        cx, cy, nose = .55, t + .1, MAG_Z - length / 2 - .1
        r.rod(round_bone, 'brass', width / 2, nose + 1.6, nose + length, cy=cy, cx=cx)
        r.rod(round_bone, 'brass', width / 2 - .2, nose + 1.1, nose + 1.6, cy=cy, cx=cx)
        r.rod(round_bone, 'copper', width / 2 - .25, nose, nose + 1.1, cy=cy, cx=cx)
    return r.cubes


def decor():
    """A pattern on the sides: the handguard's ribs."""
    rib = PLASTIC[0]

    def ribs(z, y):
        return rib if -48.5 < z < -18.0 and math.floor(z) % 3 == 0 else None
    return {'guard': ribs}


FEED_FROM = (-.7, .45, -.55)            # the left forearm while it feeds a round into the held magazine: in from the left
FEED_SHOULDER = (8.9, 1.3, 22.9)        # the left arm's pivot for the held magazine: under the right fist (where the pistols' is
                                        # from theirs), not out at the handguard
MAGAZINE_OUT, MAGAZINE_TILT = (0, 3.7, 10.8), 13.0     # the magazine moved from its seat into the right fist, standing
                                                      # out of it and leaning back as a pistol's grip does (in the
                                                      # rifle it leans forward: its mouth would be under the eye)


def magazine_spec(png):
    """The held magazine (see m9.magazine_spec): 5.56, double stack (the top round to the left), a bottle-necked round."""
    import build
    return {'type': 'Base.556Clip', 'gun': 'm16a1',
            'mouth': (MAG_TOP, round(MAG_Z - MAG_HALF_DEPTH - .02, 4), round(MAG_Z + MAG_HALF_DEPTH - .5, 4),
                      round(MAG_Z + MAG_HALF_DEPTH + .02, 4), MAG_HALF + .02),
            'round': (ROUND[0], ROUND[1], False), 'neck': True, 'stagger': .55, 'lips': 3.6, 'out': MAGAZINE_OUT, 'tilt': MAGAZINE_TILT,
            'patch': pistol.free_patch(png), 'left': pistol.feed_arm(FEED_SHOULDER, FEED_FROM, build.FEED_ARM)}


def geometry(skeleton=None):
    """The model on our own rifle layout (the argument is ignored: build_own.py hands every kind the MK14-derived layout)."""
    return pistol.geometry(M16, rifle_cubes(), rifle.bones)


def skeleton():
    return pistol.skeleton(M16, rifle.bones)


def texture():
    return m1a.texture(rifle_cubes(), MATERIALS, decor())


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = geometry()
    (out / 'gun_m16a1.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_m16a1.png').write_bytes(texture())
    (out / 'gun_m16a1_base.json').write_text(json.dumps({'display': skeleton()['display']}))
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
