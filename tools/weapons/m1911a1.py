"""Our own M1911 Pistol (PZ's Base.Pistol2): the model and its texture, made here from nothing but numbers (m1a.py's cube
helpers, packing and painting; pistol.py's joint layout and patterns). The kind keeps the id `m1911a1` the game registered.

    python m1911a1.py [--out DIR]   writes gun_m1911a1.geo.json and gun_m1911a1.png there, for a look

The side view follows PZ's own mesh at the M9's scale (pzmesh.py 1911_Pistol --length 29.35 --muzzle -20 --top 25.1): the
muzzle at z -20, the tip of the grip safety at 9.4, the top of the sights 25.05, the bottom of the grip 6.35 at the front. An
M1911A1: a closed slide with a small ejection port on the right, the barrel bushing and the recoil spring plug in its nose, tiny
GI sights, grooves at its back; under it the frame with a straight sliding trigger, the slide stop and the thumb safety on the
left, the grip leaning back 15.5 degrees with brown checkered panels and their screws, the beavertail of the grip safety, the
lined mainspring housing, a cocked spur hammer. Parkerized grey steel. No part of it is taken from the reference set.

Bones (pistol.py): the slide (with the bushing, the plug and the sights) goes back SLIDE_TRAVEL with a shot; the hammer turns
about its pin; the single-stack magazine leans with the grip, its top round on `bullet`.
"""
import argparse
import json
import math
from pathlib import Path

import m1a
import pistol
from m1a import Rifle

SH, FH, PANEL = 1.95, 1.7, 1.92         # half the width of the slide, the frame, the grip panels
SLIDE_LOW, SLIDE_TOP, NOSE_LOW = 20.0, 24.5, 19.25
NOSE, NOSE_END, PORT, PORT_END, SLIDE_BACK = -19.3, -14.8, -4.6, 0.0, 5.8      # along the slide (z)
BORE, MUZZLE, BARREL_R, BUSHING_R = 23.0, -20.0, .85, 1.15
SIGHT_Y, REAR_SIGHT = 25.05, (3.0, 3.8)
GRIP_AT, TILT = (10.0, 4.04), 15.5      # (y, z) the grip turns about, and how far it leans back
GRIP_UP, GRIP_DOWN, GRIP_HALF_DEPTH = 9.0, 2.6, 4.1
MAG_TOP, MAG_Z, MAG_HALF_DEPTH, MAG_HALF = 18.0, 1.82, 2.45, 1.15     # its top round under the frame's top
MAG_LENGTH, PLATE_DOWN = 11.1, 11.21    # the body down its own length, and the middle of the base plate
SLIDE_TRAVEL, SLIDE_LOCK = 5.0, 4.6
ROUND = (1.5, 4.3)                      # a .45 ACP cartridge: width and length


class M1911(pistol.Pistol):
    name = 'm1911a1'
    MAIN = (0.0, 17.0, 4.0)
    SLIDE = (0.0, 22.3, -6.0)
    HAMMER = (0.0, 21.0, 6.3)
    MAG = (0.0, MAG_TOP, MAG_Z)
    TILT = TILT
    SIGHT = (SIGHT_Y, REAR_SIGHT[1])
    BORE, MUZZLE = BORE, MUZZLE
    EJECT = (-1.95, 23.4, -2.3)
    RIGHT = ((0.0, 13.0, 3.1), (-6.0, 5.0, 26.0))      # the fist round the grip, the arm reaching out level
    LEFT = ((3.6, 11.5, 1.9), (10.0, 1.0, 25.0))       # the support hand round it, from the left


PARKER = ((40, 42, 40), (52, 55, 52), (65, 68, 64), (80, 83, 78), (98, 101, 95))     # parkerized steel, a little green
BROWN = ((44, 26, 18), (60, 36, 24), (76, 47, 31), (94, 60, 40), (114, 76, 52))      # the A1's brown plastic grips
MATERIALS = dict(m1a.MATERIALS, **{
    'slide':  (PARKER, 2, False),
    'frame':  (PARKER, 2, False),
    'barrel': (((62, 63, 66), (78, 80, 83), (96, 98, 102), (116, 118, 122), (138, 140, 145)), 2, False),   # bright steel
    'dark':   (PARKER, 1, False),           # sights, hammer, trigger, safeties, screws
    'grip':   (BROWN, 2, False),            # checkered (decor)
    'mag':    (((26, 28, 34), (36, 39, 46), (48, 51, 60), (62, 66, 76), (80, 84, 96)), 2, False),   # blued
    'hole':   (((8, 8, 9),) * 5, 2, False),
})


def grip_box(r, bone, material, half_x, up, down, half_depth, **more):
    return pistol.leaning_box(r, bone, material, half_x, up, down, half_depth, GRIP_AT, TILT, **more)


def pistol_cubes():
    r = Rifle()
    s, g = 'slide', 'gun'
    # ---- slide: the nose down to the plug, the body with the port on the right, the back; bushing, plug, sights
    r.beam(s, 'slide', SH, NOSE_LOW, SLIDE_TOP, NOSE, NOSE_END, cut=.45)
    r.beam(s, 'slide', SH - .01, SLIDE_LOW, SLIDE_TOP, NOSE_END - .1, PORT, cut=.45)
    r.beam(s, 'slide', SH - .02, SLIDE_LOW, SLIDE_TOP, PORT_END, SLIDE_BACK, cut=.45)
    r.box(s, 'slide', -.6, SH, SLIDE_LOW, SLIDE_TOP - .45, PORT, PORT_END)                    # beside the port: the left and
    r.box(s, 'slide', -.6, SH - .45, SLIDE_TOP - .5, SLIDE_TOP, PORT, PORT_END)               # its top edge,
    r.box(s, 'slide', -SH, -.6, SLIDE_LOW, 22.4, PORT, PORT_END)                              # under the port
    r.rod(s, 'slide', BUSHING_R, MUZZLE + .05, NOSE + .1, cy=BORE)                            # barrel bushing
    r.rod(s, 'steel', .6, NOSE - .3, NOSE + .1, cy=20.55)                                     # recoil spring plug
    r.box(s, 'dark', -.22, .22, SLIDE_TOP, SIGHT_Y, -19.0, -18.3)                             # front sight: a small blade
    z0, z1 = REAR_SIGHT
    r.box(s, 'dark', -.95, .95, SLIDE_TOP - .05, 24.75, z0, z1)                               # rear sight with a small notch
    for side in (-1, 1):
        r.box(s, 'dark', min(side * .25, side * .95), max(side * .25, side * .95), 24.75, SIGHT_Y, z0, z1)
    r.box(s, 'dark', -SH - .05, -SH + .02, 23.1, 23.5, PORT_END, 1.8)                        # extractor
    # ---- barrel: the muzzle in the bushing, its hood in the port
    r.rod(g, 'barrel', BARREL_R, MUZZLE, PORT, cy=BORE)
    r.box(g, 'hole', -.4, .4, BORE - .4, BORE + .4, MUZZLE - .03, MUZZLE + .5)                # the bore
    r.box(g, 'barrel', -1.1, 1.1, 21.6, 24.0, PORT - .1, PORT_END + .2)                       # hood and chamber
    # ---- frame: dust cover, above the guard, the guard, trigger, slide stop, thumb safety, magazine catch
    r.beam(g, 'frame', FH, 18.85, SLIDE_LOW, NOSE_END, -9.4, cut=.35, top=False)
    r.box(g, 'frame', -FH, FH, 18.4, SLIDE_LOW, -9.4, SLIDE_BACK, edges=('bottom',))
    r.bars(g, 'frame', .5, [(-9.6, 19.1), (-8.7, 17.4), (-8.0, 15.4), (-7.3, 14.45), (-6.3, 14.1), (-1.6, 14.0), (-.9, 14.6)], .9)
    r.bars(g, 'dark', .38, [(-2.5, 18.4), (-2.6, 16.6), (-2.85, 15.6)], .6)                   # trigger: a short straight bow
    r.box(g, 'dark', FH, FH + .25, 19.5, 20.3, -7.2, -1.2)                                   # slide stop (left): its body,
    r.knob(g, 'dark', .5, FH, FH + .35, 19.9, -6.8)                                          # pin
    r.box(g, 'dark', FH, FH + .3, 18.9, 19.7, -2.6, -1.2)                                    # and thumb piece
    r.box(g, 'dark', FH, FH + .3, 19.4, 20.1, 3.2, 5.6)                                      # thumb safety (left)
    r.box(g, 'dark', FH, FH + .6, 19.4, 19.7, 3.2, 4.4)                                      # its shelf
    r.knob(g, 'dark', .5, FH, FH + .3, 16.4, -1.1)                                           # magazine catch (left)
    # ---- grip, leaning back; its bottom; the grip safety's beavertail over the hand
    grip_box(r, g, 'frame', FH, GRIP_UP, GRIP_DOWN, GRIP_HALF_DEPTH, edges=())
    r.slab(g, 'frame', FH - .02, (.55, 6.35), (8.9, 7.55), 1.6, up=True, cut=.3)
    r.box(g, 'frame', -FH + .02, FH - .02, 17.3, 18.5, -2.0, 5.6, edges=())           # the wedge between the grip's top and the frame
    r.slab(g, 'frame', FH - .04, (5.4, 20.15), (9.4, 19.45), 1.1, cut=.3)
    r.box(g, 'frame', -FH + .03, FH - .03, 17.6, 19.6, 5.2, 7.2, edges=())
    y, z = GRIP_AT
    for side in (-1, 1):
        x0, x1 = (FH, PANEL) if side > 0 else (-PANEL, -FH)
        r.box(g, 'grip', x0, x1, y - 2.2, y + 6.6, z - 3.4, z + 2.6, turn=(TILT, 0, 0), about=(0, y, z), edges=())
        sx0, sx1 = (PANEL, PANEL + .05) if side > 0 else (-PANEL - .05, -PANEL)
        for v in (-1.4, 5.8):                                                                # the panel's two screws
            r.box(g, 'dark', sx0, sx1, y + v - .3, y + v + .3, z - .7, z - .1, turn=(TILT, 0, 0), about=(0, y, z), edges=())
    # ---- hammer, cocked: its hub on the pin, the spur leaning back over the beavertail
    h = 'hammer'
    r.box(h, 'dark', -.5, .5, 20.3, 21.7, 5.8, 6.9)
    r.slab(h, 'dark', .5, (6.0, 22.1), (7.9, 23.7), 1.1, cut=.2)
    # ---- magazines (upright here; their bones lean them back): body, feed lips, base plate, the top round
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        front, back = MAG_Z - MAG_HALF_DEPTH, MAG_Z + MAG_HALF_DEPTH
        r.beam(bone, 'mag', MAG_HALF, MAG_TOP - MAG_LENGTH, MAG_TOP, front, back, cut=.3)
        for side in (-1, 1):
            r.box(bone, 'mag', min(side * (MAG_HALF - .3), side * MAG_HALF), max(side * (MAG_HALF - .3), side * MAG_HALF), MAG_TOP,
                  MAG_TOP + .3, MAG_Z - .6, back)
        y = MAG_TOP - PLATE_DOWN
        r.box(bone, 'mag', -MAG_HALF - .1, MAG_HALF + .1, y - .25, y + .25, front - .1, back + .15)
        width, length = ROUND
        cy, nose = MAG_TOP + .2, MAG_Z - length / 2 - .1
        r.rod(round_bone, 'brass', width / 2, nose + 1.5, nose + length, cy=cy)
        r.rod(round_bone, 'copper', width / 2 - .1, nose + .5, nose + 1.5, cy=cy)
        r.rod(round_bone, 'copper', width / 2 - .35, nose, nose + .5, cy=cy)
    return r.cubes


def decor():
    """Patterns on the sides: the slide's grooves, the grips' checkering, the magazine's witness holes (in its upright numbers)."""
    groove, cut, hole = PARKER[1], (68, 41, 27), (14, 15, 18)

    def witness(z, y):
        if MAG_Z + .8 < z < MAG_Z + 1.8 and 8.5 < y < MAG_TOP - 1.5 and math.floor(y) % 2 == 0:
            return hole
        return None
    return {'slide': pistol.serrations(1.4, SLIDE_BACK - .3, SLIDE_LOW + .4, SLIDE_TOP - .4, groove),
            'grip': pistol.checkering(GRIP_AT, TILT, (-2.9, 2.1), (-1.8, 6.1), cut), 'mag': witness}


def geometry(skeleton=None):
    """The model on our own pistol layout (the argument is ignored: build_own.py hands every kind the rifles' layout)."""
    return pistol.geometry(M1911, pistol_cubes())


def skeleton():
    return pistol.skeleton(M1911)


def texture():
    return m1a.texture(pistol_cubes(), MATERIALS, decor())


FEED_FROM = (-.7, .45, -.55)            # the left forearm while it feeds a round into the held magazine: in from the left


def magazine_spec(png):
    """The held magazine (see m9.magazine_spec): .45 ACP, single stack."""
    import build
    left = next(b for b in pistol.bones(M1911) if b['name'] == 'leftarm')
    return {'type': 'Base.45Clip', 'gun': 'm1911a1',
            'mouth': (MAG_TOP, round(MAG_Z - MAG_HALF_DEPTH - .02, 4), round(MAG_Z + MAG_HALF_DEPTH - .45, 4),
                      round(MAG_Z + MAG_HALF_DEPTH + .02, 4), MAG_HALF + .02),
            'round': (ROUND[0], ROUND[1], False), 'out': (0, 6.5, round(-6.5 * math.tan(math.radians(TILT)), 4)),
            'patch': pistol.free_patch(png), 'left': pistol.feed_arm(left['pivot'], FEED_FROM, build.FEED_ARM)}


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = geometry()
    (out / 'gun_m1911a1.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_m1911a1.png').write_bytes(texture())
    (out / 'gun_m1911a1_base.json').write_text(json.dumps({'display': skeleton()['display']}))
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
