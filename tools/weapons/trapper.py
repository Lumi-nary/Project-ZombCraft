"""Our own Trapper Carbine (PZ's Base.TrapperCarbine): the model and its texture, made here from nothing but numbers, the way
m1a.py makes the M1A (its cube helpers, packing, painting and bone handling are used from there).

    python trapper.py [--out DIR]     writes gun_trapper.geo.json and gun_trapper.png there, for a look (build.py calls them)

PZ's Trapper is a Marlin Camp 45 style carbine: .45, fed by the M1911's 7-round magazine (Base.45Clip), which goes straight up
into the well and leans back like a pistol grip. A long black barrel with a ramp front sight and a leaf rear sight far forward
on it, a black receiver with the bolt seen in its port and the bolt's handle on the right, a blonde stock with a dark band
painted round the forend (a diamond in it) and a dark feather down the wrist (a diamond in it too), a black butt plate whose toe
is forward of its heel. The side view follows PZ's own mesh (measured from its picture at 9.96 picture pixels a unit, the
trigger guard where the JS-14's is, so the hands hold it alike).

Its sights are open ones on the barrel, so the `scope` bone (what aiming puts at the eye's line) is moved to the rear sight's
notch and the front blade's tip is on that line. The magazine bones lean back 16Â° (the MK14's lean 8Â° forward).
"""
import argparse
import json
import math
from pathlib import Path

import m1a
from m1a import BORE, STEEL, Rifle, on_line

MUZZLE = -57.1
NOTCH = (28.55, -17.6)              # the rear sight's notch (y, z): the line of sight runs from it to the front blade's tip
TOE = (43.6, 7.4)
HEEL = (46.6, 21.6)
PLATE = 1.2
GRIP = (0.0, 7.1, 13.6)             # the fist round the wrist (higher than the JS-14's: this stock's wrist is)
WRIST = [(10.4, 21.8), (15.2, 18.4), (17.1, 15.2)]      # the underside behind the guard, down to the hump
TOP = [(12.3, 25.9), (17.7, 23.2), (21.4, 24.2)]        # the top behind the receiver: its dip, the comb's nose
LEAN = 16.0                         # the magazine's lean, the bottom back
MAG = dict(top=24.5, bottom=12.0, front=-5.6, back=-.6, half=1.2)   # the magazine in its bone's numbers (before its lean)
PIVOTS = {b: (-0.75, 22.0, -3.0) for b in ('magazine', 'magazine2', 'bullet', 'bullet2')}
PIVOTS['_shelleject_'] = (-1.6, 27.0, -5.0)
PIVOTS['scope'] = (0.0, NOTCH[0], NOTCH[1])
ROTATIONS = {'magazine': (LEAN, 0, 0), 'magazine2': (LEAN, 0, 0)}
BLONDE = ((100, 58, 30), (128, 78, 42), (156, 98, 54), (180, 118, 66), (202, 140, 82))
MATERIALS = dict(m1a.MATERIALS, wood=(BLONDE, 3, True), hole=(STEEL, 0, False))
DARK, LIGHT = (44, 24, 13), BLONDE[3]       # the painted pattern: a dark brown, and the wood's own tone for its diamonds


def decor(z, y):
    """The pattern painted on the stock's sides, from where a texel is on the rifle: None where the plain wood shows."""
    def diamond(cz, cy):                    # a diamond as one texel and its four neighbours: at a texel a unit, its outline
        return abs(z - cz) + abs(y - cy) <= 1.2     # would only be a checkerboard
    # round the forend: a band whose ends come to a point fore and aft, a diamond in the middle
    if 22.4 <= y <= 26.0:
        off = abs(y - 24.25)
        if -29.7 + 1.4 * off <= z <= -10.4 - 1.4 * off:
            return LIGHT if diamond(-19.5, 24.25) else DARK
    # down the wrist: a feather along the line from behind the receiver to the hump, pointed at its lower end, a diamond on it
    ax, ay, bx, by = 11.2, 25.6, 19.0, 17.6
    lz, ly = bx - ax, by - ay
    t = ((z - ax) * lz + (y - ay) * ly) / (lz * lz + ly * ly)
    across = abs((z - ax) * ly - (y - ay) * lz) / math.hypot(lz, ly)
    if .05 <= t <= 1 and across <= 2.1 * min(1.0, (1 - t) * 3.0):
        return LIGHT if diamond(15.4, 21.4) else DARK
    return None


def rifle():
    r = Rifle()
    g = 'gun'
    sight_y, notch_z = NOTCH
    # ---- barrel, front sight, rear sight (black)
    r.rod(g, 'dark', .9, MUZZLE, -11.0)                                      # barrel
    r.box(g, 'dark', -.55, .55, BORE + .75, BORE + 1.75, -56.0, -53.2)       # front sight: its block,
    r.box(g, 'dark', -.5, .5, BORE + .75, BORE + 1.2, -53.2, -51.2)          # a step behind it,
    r.box(g, 'dark', -.22, .22, BORE + 1.6, sight_y + .05, -55.4, -54.0)     # the blade, its tip on the line of sight
    r.beam(g, 'dark', .7, BORE + .75, BORE + 1.2, -24.9, -16.2, cut=.2, bottom=False)   # rear sight: its spring on the barrel,
    r.slab(g, 'dark', .45, (-21.5, BORE + 1.2), (-18.2, BORE + 1.7), .6, cut=.15)     # the elevator,
    for side in (-1, 1):                                                     # the leaf, a notch between two posts
        r.box(g, 'dark', min(side * .25, side * .8), max(side * .25, side * .8), BORE + 1.1, sight_y + .35, notch_z - .2, notch_z + .2)
    r.box(g, 'dark', -.8, .8, BORE + 1.1, sight_y - .2, notch_z - .2, notch_z + .2)
    # ---- receiver: its front stepping up from the barrel, its back sloping down to the stock; the port on the right
    r.slab(g, 'dark', 1.58, (-12.4, 27.2), (-10.6, 28.3), 1.8, cut=.35)
    r.beam(g, 'dark', 1.6, 25.6, 28.3, -11.0, 8.6, cut=.5)
    r.slab(g, 'dark', 1.58, (8.4, 28.3), (10.9, 26.3), 2.4, cut=.35)
    r.box(g, 'hole', -1.68, -1.56, 26.1, 27.9, -9.3, -.5)                    # the ejection port
    # ---- bolt with its handle (they go back 7 with a shot)
    r.rod('action', 'bright', .7, -9.0, -1.0, cy=27.0, cx=-1.0)
    r.box('action', 'steel', -2.0, -1.4, 26.7, 27.3, -8.9, -8.1)             # the handle's stem
    r.knob('action', 'steel', .5, -3.0, -2.0, 27.0, -8.5)                    # and knob
    # ---- stock: the forend under the barrel, the wood along the receiver
    r.beam(g, 'wood', 1.8, 23.9, 25.95, -36.4, -8.3, cut=.45, bottom=False)
    r.slab(g, 'wood', 1.78, (-36.4, 23.2), (-8.3, 22.3), 2.0, up=True, cut=.5)
    r.box(g, 'wood', -1.76, 1.76, 23.0, 24.2, -37.0, -36.2, edges=('bottom',))    # its front end, a little rounded
    r.beam(g, 'wood', 1.95, 22.0, 25.8, -8.3, 10.9, cut=.5)
    # ---- trigger group, black: the housing under the stock, the block behind the magazine, a square guard
    r.box(g, 'dark', -.9, .9, 21.6, 22.1, -8.4, 8.0)
    r.beam(g, 'dark', .8, 20.0, 22.0, -1.0, 1.8, cut=.25)
    r.bars(g, 'dark', .25, [(1.7, 22.0), (1.9, 19.8), (2.6, 18.9), (6.4, 18.9), (7.1, 19.6), (7.2, 22.0)], .45)
    r.bars(g, 'steel', .2, [(5.2, 21.9), (5.0, 20.6), (4.4, 19.8)], .4)      # trigger: its tip curves forward
    r.box(g, 'dark', -.3, .3, 20.6, 21.7, -1.5, -1.0)                       # magazine catch
    # ---- wrist and butt (as the M1A's: each slope one turned beam, overlapping pieces a little apart in width)
    (a, b, c), (top_a, dip, nose) = WRIST, TOP
    comb_end = (HEEL[0], 21.7)

    def into(p, q, depth, up):
        return m1a.into_plate(p, q, depth, up, TOE, HEEL, PLATE)
    r.slab(g, 'wood', 1.95, a, on_line(a, b, b[0] + .4), 3.6, up=True)
    r.slab(g, 'wood', 1.93, b, c, 4.4, up=True)
    r.slab(g, 'wood', 1.95, c, into(c, TOE, 3.5, True), 3.5, up=True)       # the long line to the toe
    r.slab(g, 'wood', 1.9, (10.6, 26.0), on_line(top_a, dip, dip[0] + .5), 3.6)              # the top of the wrist, dipping
    r.slab(g, 'wood', 1.89, on_line(dip, nose, dip[0] - .3), on_line(dip, nose, nose[0] + .3), 3.8)   # up to the comb's nose
    start = on_line(nose, comb_end, nose[0] - .4)
    r.slab(g, 'wood', 1.88, start, into(start, comb_end, 7.0, False), 7.0)                   # the comb
    low = on_line(c, TOE, 19.0)
    low = (low[0], low[1] + 3.1)
    r.slab(g, 'wood', 1.84, low, into(low, (TOE[0], TOE[1] + 3.1), 4.5, True), 4.5, up=True, inside=True)
    r.box(g, 'wood', -1.82, 1.82, 20.6, 23.5, 10.0, 17.5, edges=())
    r.box(g, 'wood', -1.8, 1.8, 17.5, 21.0, 14.0, 19.5, edges=())
    r.box(g, 'dark', -2.0, 2.0, TOE[1] - .1, HEEL[1] + .1, TOE[0] - PLATE, TOE[0],                                    # butt plate
          turn=(math.degrees(math.atan2(TOE[0] - HEEL[0], HEEL[1] - TOE[1])), 0, 0), about=(0, TOE[1], TOE[0]))
    # ---- magazine: the M1911's, straight, leaning back with its bone; the same once more for the one a reload brings
    t, bo, f, bk, h = MAG['top'], MAG['bottom'], MAG['front'], MAG['back'], MAG['half']
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        r.beam(bone, 'dark', h, bo, t, f, bk, cut=.25)
        r.beam(bone, 'steel', h + .1, bo - .6, bo + .05, f - .3, bk + .2, cut=.2)          # its base
        for side in (-1, 1):                                                 # a rib pressed into each side
            r.box(bone, 'steel', side * (h - .03) - .06, side * (h - .03) + .06, bo + 1.5, t - 3.5, f + 1.0, f + 1.5)
        r.rod(round_bone, 'brass', .6, f + 1.2, bk - .4, cy=t - .35)        # the top round, a fat .45
        r.rod(round_bone, 'copper', .5, f + .3, f + 1.2, cy=t - .35)
    return r.cubes


def geometry(skeleton):
    """The model, on the bones of the MK14's .geo.json given."""
    return m1a.geometry(skeleton, rifle(), GRIP, MUZZLE, PIVOTS, ROTATIONS)


def texture():
    return m1a.texture(rifle(), MATERIALS, decor)


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = geometry(json.loads((Path(__file__).resolve().parent / 'rifle_skeleton.json').read_text()))
    (out / 'gun_trapper.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_trapper.png').write_bytes(texture())
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
