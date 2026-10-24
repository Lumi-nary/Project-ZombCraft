"""Our own M9 Pistol (PZ's Base.Pistol): the model and its texture, made here from nothing but numbers (m1a.py's cube helpers,
packing and painting; pistol.py's joint layout).

    python m9.py [--out DIR]       writes gun_m9.geo.json and gun_m9.png there, for a look (build_own.py calls geometry() and texture())

The side view follows PZ's own mesh (tools/weapons/pzmesh.py M9_Pistol --length 29.5 --muzzle -20 --top 25.5: the muzzle at
z -20, the back of the cocked hammer at 9.5, the top of the sights 25.4, the bottom of the magazine 5.9): a black slide with
the Beretta's open top over the barrel and the ejection port on the right, the front sight on its nose, a dovetailed rear
sight, the safety-decocker levers and grip serrations at its back; under it the frame with the dust cover, a big rounded
trigger guard, the grip leaning back 13.4 degrees with checkered panels, a cocked spur hammer. Widths are PZ's too (slide 3.8,
frame 2.8): slimmer than the real gun. No part of it is taken from the reference set.

Bones (pistol.py): the slide (and the sights and levers on it) goes back SLIDE_TRAVEL with a shot; the hammer turns about its
pin; the magazine leans with the grip, its top round on `bullet`. Three-dot sights: a white dot on the back of the front blade
and on each rear ear.
"""
import argparse
import json
import math
from pathlib import Path

import m1a
import pistol
from m1a import Rifle

SH, FH, PANEL = 1.9, 1.4, 1.58          # half the width of the slide, the frame, the grip panels
SLIDE_LOW, SLIDE_TOP, SLIDE_TOP_BACK = 20.7, 24.6, 24.9
NOSE, OPEN_END, PORT, BREECH, SLIDE_BACK = -19.0, -16.6, -5.0, 1.2, 7.2     # along the slide (z)
WALL = .65                              # the open top's side walls
BORE, MUZZLE, BARREL_R = 23.1, -20.0, .9
SIGHT_Y, REAR_SIGHT = 25.4, (2.9, 4.1)
GRIP_AT, TILT = (10.0, 4.2), 13.4       # (y, z) the grip turns about, and how far it leans back
GRIP_UP, GRIP_DOWN, GRIP_HALF_DEPTH = 8.84, 2.97, 3.5
MAG_TOP, MAG_Z, MAG_HALF_DEPTH, MAG_HALF = 19.0, 2.06, 2.08, .98
MAG_LENGTH, PLATE_DOWN = 12.9, 13.16    # the body down its own length, and the middle of the base plate
SLIDE_TRAVEL, SLIDE_LOCK = 5.0, 4.6     # how far a shot or a hand takes the slide back, and where the slide stop holds it
ROUND = (1.4, 3.6)                      # a 9 mm cartridge: width and length


class M9(pistol.Pistol):
    name = 'm9'
    MAIN = (0.0, 17.0, 4.0)
    SLIDE = (0.0, 22.5, -6.0)
    HAMMER = (0.0, 20.4, 7.6)
    MAG = (0.0, MAG_TOP, MAG_Z)
    TILT = TILT
    SIGHT = (SIGHT_Y, REAR_SIGHT[1])
    BORE, MUZZLE = BORE, MUZZLE
    EJECT = (-1.9, 23.8, -1.6)
    RIGHT = ((0.0, 13.0, 3.4), (-6.0, 5.0, 26.0))      # the fist round the grip, the arm reaching out level
    LEFT = ((3.4, 11.5, 2.2), (10.0, 1.0, 25.0))       # the support hand round it, from the left
    FIRST_PERSON = {'rotation': [0, 0, 6], 'translation': [-1.5, -21.5, -20.0]}


# five tones each, dark to light, the tone a face is painted in, and whether it has a grain (m1a.paint)
SLIDE_TONES = ((20, 20, 22), (30, 30, 33), (41, 41, 45), (54, 54, 59), (70, 71, 77))
GRIP_TONES = ((16, 16, 17), (24, 24, 26), (32, 32, 35), (42, 42, 46), (54, 54, 58))
MATERIALS = dict(m1a.MATERIALS, **{
    'slide':  (SLIDE_TONES, 2, False),          # Bruniton black
    'frame':  (((24, 24, 25), (34, 34, 36), (45, 45, 48), (58, 58, 62), (74, 75, 79)), 2, False),   # black anodised aluminium
    'barrel': (m1a.STEEL, 2, False),
    'dark':   (SLIDE_TONES, 1, False),          # sights, hammer, trigger, levers
    'grip':   (GRIP_TONES, 2, False),           # black plastic, checkered (decor)
    'plate':  (GRIP_TONES, 2, False),           # the magazine's base plate
    'medal':  (((70, 70, 72), (92, 92, 95), (112, 112, 116), (134, 134, 138), (156, 156, 160)), 1, False),   # the grip medallion
    'mag':    (((26, 28, 34), (36, 39, 46), (48, 51, 60), (62, 66, 76), (80, 84, 96)), 2, False),   # blued steel
    'dot':    (((210, 210, 200),) * 5, 2, False),
    'hole':   (((8, 8, 9),) * 5, 2, False),
})


def grip_box(r, bone, material, half_x, up, down, half_depth, **more):
    """A box of the grip: upright about GRIP_AT, then leant back with it."""
    return pistol.leaning_box(r, bone, material, half_x, up, down, half_depth, GRIP_AT, TILT, **more)


def pistol_cubes():
    r = Rifle()
    s, g = 'slide', 'gun'
    # ---- slide: the nose with the front sight, the open top's walls, the port on the right, the back with the rear sight
    r.beam(s, 'slide', SH, SLIDE_LOW, SLIDE_TOP, NOSE, OPEN_END, cut=.45)
    r.box(s, 'slide', SH - WALL, SH, SLIDE_LOW, SLIDE_TOP, OPEN_END, -2.4)                    # left wall
    r.box(s, 'slide', SH - WALL + .01, SH - .01, SLIDE_LOW, SLIDE_TOP_BACK, -2.4, BREECH + .1)
    r.box(s, 'slide', -SH, -(SH - WALL), SLIDE_LOW, SLIDE_TOP, OPEN_END, PORT)                # right wall,
    r.box(s, 'slide', -SH, -(SH - WALL), SLIDE_LOW, 22.9, PORT, BREECH + .1)                  # low under the port
    r.beam(s, 'slide', SH, SLIDE_LOW, SLIDE_TOP_BACK, BREECH, 5.5, cut=.45)
    r.slab(s, 'slide', SH - .02, (5.4, SLIDE_TOP_BACK), (SLIDE_BACK, 23.7), 2.6, cut=.45)     # the back of its top, sloping down
    r.box(s, 'slide', -SH, SH, SLIDE_LOW, 23.3, 5.5, SLIDE_BACK, edges=('bottom',))
    r.box(s, 'dark', -.3, .3, SLIDE_TOP, SIGHT_Y, -18.4, -17.4)                               # front sight blade
    r.box(s, 'dot', -.15, .15, 24.95, 25.2, -17.42, -17.35)
    z0, z1 = REAR_SIGHT
    r.box(s, 'dark', -1.05, 1.05, SLIDE_TOP_BACK, 25.1, z0, z1)                               # rear sight: its base,
    for side in (-1, 1):                                                                      # its two ears, a dot on each
        r.box(s, 'dark', min(side * .32, side * 1.05), max(side * .32, side * 1.05), 25.1, SIGHT_Y, z0, z1)
        r.box(s, 'dot', min(side * .55, side * .85), max(side * .55, side * .85), 25.12, 25.34, z1 - .02, z1 + .05)
        x0, x1 = (SH, SH + .35) if side > 0 else (-SH - .35, -SH)                             # safety-decocker levers
        r.knob(s, 'dark', .5, x0, x1, 23.4, 5.0)
        r.box(s, 'dark', min(side * SH, side * (SH + .25)), max(side * SH, side * (SH + .25)), 22.2, 23.4, 4.72, 5.28,
              turn=(25, 0, 0), about=(0, 23.4, 5.0))
    r.box(s, 'dark', -SH - .06, -SH + .02, 23.6, 24.0, BREECH, 2.6)                          # extractor
    # ---- barrel (it stays when the slide goes back): out of the nose, along the open top, its chamber in the port
    r.rod(g, 'barrel', BARREL_R, MUZZLE, -3.0, cy=BORE)
    r.box(g, 'hole', -.42, .42, BORE - .42, BORE + .42, MUZZLE - .03, MUZZLE + .5)            # the bore
    r.box(g, 'barrel', -1.15, 1.15, 22.1, 24.2, -3.0, BREECH)                                 # the chamber and locking block
    r.box(g, 'hole', -1.2, 1.2, SLIDE_LOW, 22.3, OPEN_END - .2, -3.0, edges=())              # what is under the barrel
    # ---- frame: dust cover, above the guard, the guard, trigger and levers
    r.beam(g, 'frame', FH, 19.5, SLIDE_LOW, -18.7, -9.0, cut=.35, top=False)
    r.box(g, 'frame', -FH, FH, 18.4, SLIDE_LOW, -9.0, 7.4, edges=('bottom',))
    r.bars(g, 'frame', .5, [(-9.1, 19.0), (-8.9, 17.5), (-8.3, 16.0), (-7.4, 15.0), (-6.0, 14.6), (-1.3, 14.55), (-.6, 15.2)], .9)
    r.bars(g, 'dark', .3, [(-3.4, 18.5), (-3.7, 17.4), (-4.3, 16.4), (-5.0, 15.9)], .5)     # trigger: its tip curves forward
    r.box(g, 'dark', FH, FH + .2, 18.8, 19.5, -8.6, -6.4)                                    # takedown lever (left)
    r.knob(g, 'dark', .45, FH, FH + .3, 19.15, -6.7)
    r.knob(g, 'dark', .35, -FH - .2, -FH, 19.15, -7.0)                                       # its button (right)
    r.box(g, 'dark', FH, FH + .22, 19.3, 20.2, -2.0, 1.8)                                    # slide stop (left)
    r.box(g, 'dark', FH, FH + .25, 18.9, 19.5, .8, 1.8)
    r.box(g, 'dark', FH, FH + .32, 15.6, 16.6, -.2, .9)                                      # magazine catch (left)
    # ---- grip, leaning back; its bottom level with the base plate; the tang over the hand
    grip_box(r, g, 'frame', FH, GRIP_UP, GRIP_DOWN, GRIP_HALF_DEPTH, edges=())
    r.box(g, 'frame', -FH, FH, 6.3, 8.0, 1.55, 7.75, edges=('bottom',))
    r.box(g, 'frame', -FH + .02, FH - .02, 17.5, 18.5, -.9, 5.5, edges=())            # the wedge between the grip's top and the frame
    r.box(g, 'frame', -FH, FH, 18.9, 20.2, 7.4, 8.4, edges=('bottom',))
    for side in (-1, 1):
        x0, x1 = (FH, PANEL) if side > 0 else (-PANEL, -FH)
        y, z = GRIP_AT
        r.box(g, 'grip', x0, x1, y - 2.3, y + 6.6, z - 3.0, z + 3.0, turn=(TILT, 0, 0), about=(0, y, z), edges=())
        mx0, mx1 = (PANEL, PANEL + .05) if side > 0 else (-PANEL - .05, -PANEL)
        r.box(g, 'medal', mx0, mx1, y + 4.6, y + 5.4, z - .4, z + .4, turn=(TILT, 0, 0), about=(0, y, z), edges=())
    # ---- hammer, cocked: its hub on the pin and the spur back over the tang
    h = 'hammer'
    r.box(h, 'dark', -.55, .55, 19.7, 21.2, 7.2, 8.1)
    r.slab(h, 'dark', .55, (7.4, 21.5), (9.4, 20.6), 1.3, cut=.2)
    # ---- magazines (upright here; their bones lean them back with the grip): body, feed lips, base plate, the top round
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        front, back = MAG_Z - MAG_HALF_DEPTH, MAG_Z + MAG_HALF_DEPTH
        r.beam(bone, 'mag', MAG_HALF, MAG_TOP - MAG_LENGTH, MAG_TOP, front, back, cut=.25)
        for side in (-1, 1):
            r.box(bone, 'mag', min(side * (MAG_HALF - .35), side * MAG_HALF), max(side * (MAG_HALF - .35), side * MAG_HALF), MAG_TOP,
                  MAG_TOP + .35, MAG_Z - .4, back)
        y = MAG_TOP - PLATE_DOWN
        r.box(bone, 'plate', -1.3, 1.3, y - .35, y + .35, MAG_Z - 2.6, MAG_Z + 2.6, turn=(-TILT, 0, 0), about=(0, y, MAG_Z))
        width, length = ROUND
        cy, cx, nose = MAG_TOP + .25, .3, MAG_Z - length / 2
        r.rod(round_bone, 'brass', width / 2, nose + 1.3, nose + length, cy=cy, cx=cx)
        r.rod(round_bone, 'copper', width / 2 - .15, nose + .45, nose + 1.3, cy=cy, cx=cx)
        r.rod(round_bone, 'copper', width / 2 - .35, nose, nose + .45, cy=cy, cx=cx)
    return r.cubes


def decor():
    """Patterns on the sides: serrations at the back of the slide, checkering on the grip panels (in the panel's own numbers)."""
    groove, cut = SLIDE_TONES[0], (28, 28, 31)      # a groove; a checkering square, a little darker than the panel
    return {'slide': pistol.serrations(5.6, SLIDE_BACK - .1, SLIDE_LOW + .4, SLIDE_TOP_BACK - .4, groove),
            'grip': pistol.checkering(GRIP_AT, TILT, (-2.0, 2.0), (-1.3, 3.6), cut)}


def geometry(skeleton=None):
    """The model on our own pistol layout (the argument is ignored: build_own.py hands every kind the rifles' layout)."""
    return pistol.geometry(M9, pistol_cubes())


def skeleton():
    return pistol.skeleton(M9)


def texture():
    return m1a.texture(pistol_cubes(), MATERIALS, decor())


FEED_FROM = (-.7, .45, -.55)            # the left forearm while it feeds a round into the held magazine: in from the left


def magazine_spec(png):
    """The held magazine for build.py's magazine_item_geo/recipe_feed: its mouth in this model's numbers (the magazine bone's,
    upright), a 9 mm round, how far it stands out of the fist (up along its lean), a free patch of this texture, and the left
    arm (where this gun has it, turned for the feed)."""
    import build
    left = next(b for b in pistol.bones(M9) if b['name'] == 'leftarm')
    return {'type': 'Base.9mmClip', 'gun': 'm9',
            'mouth': (MAG_TOP, round(MAG_Z - MAG_HALF_DEPTH - .02, 4), round(MAG_Z + MAG_HALF_DEPTH - .45, 4),
                      round(MAG_Z + MAG_HALF_DEPTH + .02, 4), MAG_HALF + .02),
            'round': (ROUND[0], ROUND[1], False), 'out': (0, 6.5, round(-6.5 * math.tan(math.radians(TILT)), 4)),
            'patch': pistol.free_patch(png), 'left': pistol.feed_arm(left['pivot'], FEED_FROM, build.FEED_ARM)}


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = geometry()
    (out / 'gun_m9.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_m9.png').write_bytes(texture())
    (out / 'gun_m9_base.json').write_text(json.dumps({'display': skeleton()['display']}))
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
