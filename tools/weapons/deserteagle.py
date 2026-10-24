"""Our own B-F Pistol (PZ's Base.Pistol3, a Desert Eagle in .44 Magnum): the model and its texture, made here from nothing but
numbers (m1a.py's cube helpers, packing and painting; pistol.py's joint layout and patterns). The kind keeps the id
`deserteagle` the game registered.

    python deserteagle.py [--out DIR]   writes gun_deserteagle.geo.json and gun_deserteagle.png there, for a look

The side view follows PZ's own mesh at the M9's scale (pzmesh.py D_E_Pistol --length 32.74 --muzzle -23 --top 26.0): the muzzle
at z -23, the tip of the beavertail at 9.5, the top of the sights 25.75, the bottom of the grip 4.95 at the front. A Desert
Eagle: the tall fixed barrel over the front three fifths, its top narrowing in steps (the triangular profile), the front sight
on its muzzle end and the lower front sloping back; behind it the slide with the ejection port on the right, the bolt in it, the
rear sight, the ambidextrous safety and grooves; under them the frame with the barrel release, a guard with a straight sloping
front, the trigger, the slide stop and the magazine catch on the left; the deep grip leaning back 13.5 degrees with black
panels, the beavertail, a cocked round spur hammer. Brushed stainless. No part of it is taken from the reference set.

Bones (pistol.py): the slide (with the bolt, the rear sight and the safety) goes back SLIDE_TRAVEL with a shot; the barrel stays;
the hammer turns about its pin; the single-stack magazine leans with the grip, its top .44 round (rimmed) on `bullet`.
"""
import argparse
import json
import math
from pathlib import Path

import m1a
import pistol
from m1a import Rifle

UH, FH, PANEL = 2.25, 2.05, 2.25        # half the width of the barrel and slide, the frame, the grip panels
LOW, TOP, TOP_BACK = 19.6, 24.95, 25.1  # the bottom of barrel and slide, the barrel's top, the slide's top
MUZZLE, BARREL_END, PORT_END, SLIDE_BACK = -23.0, -3.0, 1.0, 6.5               # along the gun (z)
BORE = 23.1
SIGHT_Y, REAR_SIGHT = 25.75, (4.3, 5.6)
GRIP_AT, TILT = (10.0, 3.67), 13.5      # (y, z) the grip turns about, and how far it leans back
GRIP_UP, GRIP_DOWN, GRIP_HALF_DEPTH = 8.4, 4.2, 4.55
MAG_TOP, MAG_Z, MAG_HALF_DEPTH, MAG_HALF = 17.4, 1.89, 3.25, 1.25
MAG_LENGTH, PLATE_DOWN = 11.6, 11.88    # the body down its own length, and the middle of the base plate
SLIDE_TRAVEL, SLIDE_LOCK = 5.5, 5.0
ROUND = (1.6, 5.4)                      # a .44 Magnum cartridge: width and length (and a rim)


class DesertEagle(pistol.Pistol):
    name = 'deserteagle'
    MAIN = (0.0, 17.0, 4.0)
    SLIDE = (0.0, 22.3, 1.0)
    HAMMER = (0.0, 21.6, 7.1)
    MAG = (0.0, MAG_TOP, MAG_Z)
    TILT = TILT
    SIGHT = (SIGHT_Y, REAR_SIGHT[1])
    BORE, MUZZLE = BORE, MUZZLE
    EJECT = (-UH, 23.2, -1.0)
    RIGHT = ((0.0, 13.0, 2.9), (-6.0, 5.0, 26.0))      # the fist round the grip, the arm reaching out level
    LEFT = ((3.9, 11.2, 1.6), (10.0, 1.0, 25.0))       # the support hand round it, from the left


STAINLESS = ((92, 94, 98), (118, 120, 125), (142, 145, 150), (166, 169, 174), (190, 193, 198))
GRIP_TONES = ((16, 16, 17), (24, 24, 26), (32, 32, 35), (42, 42, 46), (54, 54, 58))
MATERIALS = dict(m1a.MATERIALS, **{
    'slide':  (STAINLESS, 2, False),
    'frame':  (STAINLESS, 2, False),
    'barrel': (STAINLESS, 2, False),
    'bolt':   (((70, 72, 76), (92, 94, 99), (112, 115, 120), (134, 137, 142), (156, 159, 165)), 2, False),
    'dark':   (((30, 30, 32), (42, 42, 45), (56, 56, 60), (72, 72, 77), (90, 90, 96)), 1, False),    # sights, levers, trigger
    'grip':   (GRIP_TONES, 2, False),
    'mag':    (((26, 28, 34), (36, 39, 46), (48, 51, 60), (62, 66, 76), (80, 84, 96)), 2, False),
    'hole':   (((8, 8, 9),) * 5, 2, False),
})


def grip_box(r, bone, material, half_x, up, down, half_depth, **more):
    return pistol.leaning_box(r, bone, material, half_x, up, down, half_depth, GRIP_AT, TILT, **more)


def upper(r, bone, z0, z1, top, half=UH, right_low=None, low=LOW):
    """A part of the upper (barrel or slide) from z0 to z1: full width to 23.7, then narrowing in two steps to its top, the
    Desert Eagle's triangular profile. `right_low`: the right side only this high (the ejection port); `low`: its bottom."""
    if right_low is None:
        r.box(bone, bone_material(bone), -half, half, low, 23.7, z0, z1, edges=('bottom',))
    else:
        r.box(bone, bone_material(bone), -.4, half, low, 23.7, z0, z1, edges=('bottom',))
        r.box(bone, bone_material(bone), -half, -.4, low, right_low, z0, z1, edges=('bottom',))
    r.box(bone, bone_material(bone), -half + .4, half - .4, 23.65, 24.4, z0, z1, edges=(), inflate=-.005)
    r.box(bone, bone_material(bone), -half + .8, half - .8, 24.35, top, z0, z1, edges=('top',), inflate=-.01)


def bone_material(bone):
    return 'slide' if bone == 'slide' else 'barrel'


def pistol_cubes():
    r = Rifle()
    s, g = 'slide', 'gun'
    # ---- the fixed barrel: the upper from the muzzle, its lower front sloping back, the bore, the front sight
    upper(r, g, MUZZLE, -21.3, TOP, low=22.1)                                                # the muzzle end, above the slope,
    upper(r, g, -21.35, BARREL_END, TOP, half=UH - .01)                                      # and the rest
    r.slab(g, 'barrel', UH - .02, (MUZZLE, 22.2), (-21.3, 17.85), 2.2, up=True, cut=.3)      # the lower front, sloping back
    r.box(g, 'hole', -.55, .55, BORE - .55, BORE + .55, MUZZLE - .03, MUZZLE + .5)            # the bore
    r.slab(g, 'dark', .35, (-22.7, TOP - .05), (-21.6, SIGHT_Y), .7, up=False, cut=0)        # front sight: a ramp up to its blade
    r.box(g, 'dark', -.35, .35, TOP - .1, SIGHT_Y, -21.7, -21.3)
    r.box(g, 'bolt', -1.4, 1.4, 21.4, 24.0, BARREL_END - .6, BARREL_END + .05)               # the chamber end, seen in the port
    # ---- the slide: the port on the right at its front, the bolt in it, the back sloping down, rear sight, safety
    upper(r, s, BARREL_END, PORT_END, TOP_BACK, half=UH - .01, right_low=21.8)
    upper(r, s, PORT_END - .05, 5.7, TOP_BACK)
    r.box(s, 'slide', -UH, UH, LOW, 23.7, 5.6, SLIDE_BACK, edges=('bottom',))
    r.slab(s, 'slide', UH - .5, (5.6, TOP_BACK), (SLIDE_BACK, 24.0), 1.4, cut=.4)
    r.box(s, 'bolt', -1.2, 1.2, 21.6, 23.9, BARREL_END + .05, PORT_END)                       # the bolt
    z0, z1 = REAR_SIGHT
    r.box(s, 'dark', -.8, .8, TOP_BACK - .05, 25.45, z0, z1)                                 # rear sight: base and ears
    for side in (-1, 1):
        r.box(s, 'dark', min(side * .25, side * .8), max(side * .25, side * .8), 25.45, SIGHT_Y, z0, z1)
        x0, x1 = (UH, UH + .35) if side > 0 else (-UH - .35, -UH)                            # the ambidextrous safety
        r.knob(s, 'dark', .55, x0, x1, 23.0, 4.6)
        r.box(s, 'dark', min(side * UH, side * (UH + .25)), max(side * UH, side * (UH + .25)), 21.8, 23.0, 4.3, 4.9,
              turn=(-30, 0, 0), about=(0, 23.0, 4.6))
    # ---- frame: under the barrel, above the guard, the guard, trigger, levers
    r.box(g, 'frame', -FH, FH, 17.85, LOW, -21.3, -10.4, edges=('bottom',))
    r.box(g, 'frame', -FH, FH, 18.2, LOW, -10.5, SLIDE_BACK, edges=('bottom',))
    r.bars(g, 'frame', .55, [(-10.6, 17.9), (-8.0, 14.3), (-7.3, 13.7), (-2.6, 13.4), (-1.9, 14.0)], 1.0)
    r.bars(g, 'dark', .35, [(-5.0, 18.2), (-5.2, 16.9), (-5.8, 15.7), (-6.5, 15.2)], .55)     # trigger
    r.knob(g, 'dark', .5, FH, FH + .3, 18.9, -9.4)                                           # barrel release (left)
    r.box(g, 'dark', FH, FH + .25, 18.5, 19.4, -4.2, -.6)                                    # slide stop (left)
    r.knob(g, 'dark', .5, FH, FH + .3, 15.6, -1.6)                                           # magazine catch (left)
    # ---- grip, leaning back; its sloping bottom; the beavertail; black panels
    grip_box(r, g, 'frame', FH, GRIP_UP, GRIP_DOWN, GRIP_HALF_DEPTH, edges=())
    r.slab(g, 'frame', FH - .02, (-.15, 4.95), (9.3, 6.2), 1.8, up=True, cut=.35)
    r.box(g, 'frame', -FH + .02, FH - .02, 16.6, 18.4, -2.0, 6.0, edges=())                  # the wedge between grip and frame
    r.box(g, 'frame', -FH + .01, FH - .01, 13.6, 18.3, -3.9, -2.2, edges=())                 # the front strap up behind the guard
    r.slab(g, 'frame', FH - .04, (5.6, 21.0), (9.3, 20.4), 1.3, cut=.3)                       # beavertail
    r.box(g, 'frame', -FH + .03, FH - .03, 17.2, 20.2, 5.0, 7.6, edges=())
    y, z = GRIP_AT
    for side in (-1, 1):
        x0, x1 = (FH, PANEL) if side > 0 else (-PANEL, -FH)
        r.box(g, 'grip', x0, x1, y - 4.0, y + 6.2, z - 3.6, z + 3.4, turn=(TILT, 0, 0), about=(0, y, z), edges=())
    # ---- hammer, cocked: a round spur leaning back
    h = 'hammer'
    r.box(h, 'dark', -.55, .55, 20.8, 22.6, 6.6, 7.7)
    r.slab(h, 'dark', .55, (7.0, 23.3), (9.1, 24.0), 1.3, cut=.25)
    # ---- magazines (upright here; their bones lean them back): body, lips, base plate, the top round
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        front, back = MAG_Z - MAG_HALF_DEPTH, MAG_Z + MAG_HALF_DEPTH
        r.beam(bone, 'mag', MAG_HALF, MAG_TOP - MAG_LENGTH, MAG_TOP, front, back, cut=.3)
        for side in (-1, 1):
            r.box(bone, 'mag', min(side * (MAG_HALF - .2), side * MAG_HALF), max(side * (MAG_HALF - .2), side * MAG_HALF), MAG_TOP,
                  MAG_TOP + .3, MAG_Z - .8, back)
        y = MAG_TOP - PLATE_DOWN
        r.box(bone, 'grip', -MAG_HALF - .15, MAG_HALF + .15, y - .3, y + .3, front - .15, back + .15)
        width, length = ROUND
        cy, nose = MAG_TOP + .2, MAG_Z - length / 2 - .1
        r.rod(round_bone, 'brass', width / 2, nose + 1.9, nose + length - .2, cy=cy)
        r.rod(round_bone, 'brass', width / 2 + .1, nose + length - .2, nose + length, cy=cy)            # the rim
        r.rod(round_bone, 'copper', width / 2 - .1, nose + .6, nose + 1.9, cy=cy)
        r.rod(round_bone, 'copper', width / 2 - .4, nose, nose + .6, cy=cy)
    return r.cubes


def decor():
    """Patterns on the sides: the slide's grooves, the grip panels' texture."""
    groove, cut = STAINLESS[1], (28, 28, 31)
    return {'slide': pistol.serrations(1.8, 5.2, LOW + .5, 23.5, groove),
            'grip': pistol.checkering(GRIP_AT, TILT, (-3.1, 2.9), (-3.5, 5.7), cut)}


def geometry(skeleton=None):
    """The model on our own pistol layout (the argument is ignored: build_own.py hands every kind the rifles' layout)."""
    return pistol.geometry(DesertEagle, pistol_cubes())


def skeleton():
    return pistol.skeleton(DesertEagle)


def texture():
    return m1a.texture(pistol_cubes(), MATERIALS, decor())


FEED_FROM = (-.7, .45, -.55)            # the left forearm while it feeds a round into the held magazine: in from the left


def magazine_spec(png):
    """The held magazine (see m9.magazine_spec): .44 Magnum, rimmed, single stack."""
    import build
    left = next(b for b in pistol.bones(DesertEagle) if b['name'] == 'leftarm')
    return {'type': 'Base.44Clip', 'gun': 'deserteagle',
            'mouth': (MAG_TOP, round(MAG_Z - MAG_HALF_DEPTH - .02, 4), round(MAG_Z + MAG_HALF_DEPTH - .45, 4),
                      round(MAG_Z + MAG_HALF_DEPTH + .02, 4), MAG_HALF + .02),
            'round': (ROUND[0], ROUND[1], True), 'out': (0, 6.5, round(-6.5 * math.tan(math.radians(TILT)), 4)),
            'patch': pistol.free_patch(png), 'left': pistol.feed_arm(left['pivot'], FEED_FROM, build.FEED_ARM)}


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = geometry()
    (out / 'gun_deserteagle.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_deserteagle.png').write_bytes(texture())
    (out / 'gun_deserteagle_base.json').write_text(json.dumps({'display': skeleton()['display']}))
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
