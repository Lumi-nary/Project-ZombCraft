"""Our own JS-14 Rifle (PZ's Base.JS14_Rifle): the model and its texture, made here from nothing but numbers, the way m1a.py
makes the M1A (its cube helpers, packing, painting and bone handling are used from there).

    python js14.py [--out DIR]     writes gun_js14.geo.json and gun_js14.png there, for a look (build.py calls geometry() and texture())

PZ's JS-14 is a Mini-14: a blonde hardwood stock in one piece from a black front cap to the butt, a black top cover over the
forend with four vent slots near its front, a short black receiver with the rear sight at its back and the operating handle
on its right, a short barrel, a curved 20-round 5.56 magazine slanting forward, a black trigger guard and butt plate. The side
view follows PZ's own mesh (measured from its picture at 9.73 picture pixels a unit, the rear sight's aperture where the
M1A's is). The stock is longer behind the sight than the M1A's, as the Mini-14's is.

The skeleton is the MK14's, as for the M1A; the bones this rifle has elsewhere are moved (`PIVOTS`): the magazine and its
round, the shell ejection, the right fist (`GRIP`), the muzzle flash.
"""
import argparse
import json
import math
from pathlib import Path

import m1a
from m1a import BORE, SIGHT, STEEL, Rifle, on_line

MUZZLE = -63.0
TOE = (45.8, 7.0)
HEEL = (45.0, 22.0)
PLATE = 1.4
GRIP = (0.0, 4.5, 12.1)         # the fist round the wrist, which is further back than the M1A's (its guard is under the sight)
WRIST = [(7.8, 19.5), (11.7, 16.8), (15.3, 14.2)]       # the underside behind the guard, down to the hump
DIP, NOSE, COMB_END = (14.5, 22.1), (19.9, 23.2), (HEEL[0], 21.9)     # the top: the wrist's dip, the comb's nose, the heel
MAG = dict(top=22.5, bottom=10.6, front=-10.6, back=-3.7, half=1.3)   # the magazine in its bone's numbers (before its 8Â° tilt)
PIVOTS = {b: (-0.75, 19.5, MAG['back']) for b in ('magazine', 'magazine2', 'bullet', 'bullet2')}
PIVOTS['_shelleject_'] = (-1.0, 26.0, -5.0)
BLONDE = ((96, 54, 28), (124, 74, 40), (152, 94, 52), (176, 114, 64), (198, 136, 80))  # PZ's golden hardwood, in five tones
MATERIALS = dict(m1a.MATERIALS, wood=(BLONDE, 3, True), hole=(STEEL, 0, False))


def rifle():
    r = Rifle()
    g = 'gun'
    # ---- muzzle, front sight, barrel
    r.rod(g, 'dark', 1.05, MUZZLE, -61.0)                                    # the front sight's housing round the muzzle
    r.beam(g, 'dark', .7, BORE + .75, BORE + 1.5, -62.6, -60.6, cut=.3, bottom=False)
    r.box(g, 'dark', -.22, .22, BORE + 1.4, SIGHT[0] + .1, -61.9, -61.2)     # the blade, its tip on the line of sight
    for side in (-1, 1):                                                     # and the two ears leaning out to guard it
        r.box(g, 'dark', side * .8 - .15, side * .8 + .15, BORE + 1.0, BORE + 2.75, -62.4, -60.8, turn=(0, 0, -side * 12),
              about=(side * .8, BORE + 1.0, -61.6))
    r.rod(g, 'steel', .82, -61.0, -45.4)                                     # barrel
    r.rod(g, 'steel', 1.0, -45.4, -40.6)                                     # its thicker part at the gas block
    # ---- the black front cap, the top cover with its vents, the step down to the receiver
    r.beam(g, 'dark', 2.15, 21.25, 27.6, -40.6, -37.3, cut=.5)
    r.box(g, 'dark', -.9, .9, 20.0, 20.4, -39.6, -38.9)                      # front sling swivel
    for side in (-1, 1):
        r.box(g, 'dark', side * .75 - .15, side * .75 + .15, 20.4, 21.4, -39.6, -38.9)
    r.beam(g, 'dark', 1.75, 25.6, 27.95, -37.3, -28.5, cut=.6)
    r.beam(g, 'dark', 1.75, 24.55, 27.95, -28.5, -9.6, cut=.6)
    r.slab(g, 'dark', 1.72, (-10.4, 27.95), (-7.6, 26.9), 2.0, cut=.4)
    for z in (-36.3, -33.9, -31.6, -29.3):                                   # the vent slots, across the top and down the sides
        r.box(g, 'hole', -1.15, 1.15, 27.88, 28.0, z - .45, z + .45)
        for side in (-1, 1):
            r.box(g, 'hole', side * 1.73 - .06, side * 1.73 + .06, 26.0, 27.4, z - .45, z + .45)
    # ---- receiver, rear sight
    r.beam(g, 'dark', 1.5, 24.3, 26.9, -9.6, 6.5, cut=.4)
    r.beam(g, 'dark', 1.45, 26.7, 27.6, -2.3, 2.9, cut=.25, bottom=False)    # the rear sight's base, set into the receiver
    for side in (-1, 1):                                                     # its ears: thin plates, tall by the aperture, lower
        x = side * 1.15                                                      # in front, their top corners taken off
        for z0, z1, top in ((1.0, 2.5, 29.85), (-.6, 1.0, 29.05)):
            r.box(g, 'dark', x - .15, x + .15, 27.5, top - .3, z0, z1)
            r.box(g, 'dark', x - .15, x + .15, 27.5, top, z0 + .3, z1 - (.3 if z1 > 2 else 0), inflate=-.01)
        r.knob(g, 'dark', .4, min(side * 1.3, side * 1.75), max(side * 1.3, side * 1.75), 28.4, 1.6)   # an adjusting knob on it
    y, z = SIGHT
    r.box(g, 'dark', -.5, .5, 27.5, y - .36, z - .2, z + .2)                 # the aperture: a hole between four thin bars
    r.box(g, 'dark', -.5, .5, y + .36, y + .58, z - .2, z + .2)
    r.box(g, 'dark', -.5, -.36, y - .36, y + .36, z - .2, z + .2)
    r.box(g, 'dark', .36, .5, y - .36, y + .36, z - .2, z + .2)
    # ---- bolt and operating slide with its handle on the right (they go back 8.5 with a shot)
    r.box('action', 'bright', -1.55, .4, 25.5, 26.75, -8.0, -2.0)            # bolt, seen in the port on the right
    r.box('action2', 'steel', -2.0, -1.5, 24.7, 25.5, -30.0, -3.8)           # operating slide, along the right under the cover
    r.box('action2', 'steel', -2.4, -1.5, 24.8, 25.8, -4.6, -3.6)            # the handle's stem
    r.knob('action2', 'steel', .55, -3.1, -2.4, 25.3, -4.1)                  # and knob
    # ---- forend, the stock along the receiver
    r.beam(g, 'wood', 2.0, 23.0, 25.7, -37.3, -28.5, cut=.5, bottom=False)   # forend: level under the cover on top,
    r.beam(g, 'wood', 2.0, 23.0, 24.55, -28.5, -10.6, cut=.5, bottom=False)
    r.slab(g, 'wood', 1.98, (-29.3, 25.7), (-27.7, 24.55), 1.6, cut=.5)      # stepping down where the cover does,
    r.slab(g, 'wood', 1.96, (-37.3, 21.6), (-10.6, 19.8), 3.6, up=True, cut=.7)   # deepening towards the magazine underneath
    r.beam(g, 'wood', 2.1, 19.8, 24.3, -10.6, 6.5, cut=.5)
    for side in (-1, 1):                                                     # the screw through the stock behind the magazine
        x = side * 2.1
        r.knob(g, 'dark', .45, min(x, x + side * .2), max(x, x + side * .2), 22.4, -1.75)
    # ---- trigger group, black
    r.box(g, 'dark', -.75, .75, 19.5, 19.85, -3.4, 7.6)                      # housing plate
    r.bars(g, 'dark', .25, [(1.6, 19.7), (1.8, 18.1), (2.4, 17.1), (3.5, 16.6), (5.6, 16.6), (6.6, 17.0), (7.1, 18.0),   # guard
                             (7.2, 19.7)], .45)
    r.bars(g, 'steel', .2, [(4.6, 19.6), (4.4, 18.4), (3.9, 17.6)], .4)     # trigger: its tip curves forward
    r.box(g, 'dark', -.2, .2, 18.6, 19.6, 2.1, 2.6)                          # safety, at the front of the guard
    r.box(g, 'dark', -.35, .35, 18.4, 19.8, -3.2, -2.5)                      # magazine latch
    # ---- wrist and butt (as the M1A's: each slope one turned beam, overlapping pieces a little apart in width)
    (a, b, c), (top_a, top_b) = WRIST, ((6.5, 24.3), DIP)

    def into(p, q, depth, up):
        return m1a.into_plate(p, q, depth, up, TOE, HEEL, PLATE)
    r.slab(g, 'wood', 2.1, a, on_line(a, b, b[0] + .4), 4.0, up=True)
    r.slab(g, 'wood', 2.08, b, c, 4.6, up=True)
    r.slab(g, 'wood', 2.1, c, into(c, TOE, 3.5, True), 3.5, up=True)        # the long line to the toe
    r.slab(g, 'wood', 2.05, top_a, on_line(top_a, top_b, top_b[0] + .5), 4.2)                 # the top of the wrist, dipping
    r.slab(g, 'wood', 2.04, on_line(DIP, NOSE, DIP[0] - .3), on_line(DIP, NOSE, NOSE[0] + .3), 4.0)   # up to the comb's nose
    start = on_line(NOSE, COMB_END, NOSE[0] - .4)
    r.slab(g, 'wood', 2.03, start, into(start, COMB_END, 7.0, False), 7.0)                    # the comb
    low = on_line(c, TOE, 16.5)
    low = (low[0], low[1] + 3.1)
    r.slab(g, 'wood', 1.98, low, into(low, (TOE[0], TOE[1] + 3.1), 4.5, True), 4.5, up=True, inside=True)
    r.box(g, 'wood', -1.95, 1.95, 19.6, 22.0, 6.5, 15.3, edges=())
    r.box(g, 'wood', -1.93, 1.93, 17.0, 20.0, 11.8, 17.0, edges=())
    r.box(g, 'dark', -2.25, 2.25, TOE[1] - .1, HEEL[1] + .1, TOE[0] - PLATE, TOE[0],                                  # butt plate
          turn=(math.degrees(math.atan2(TOE[0] - HEEL[0], HEEL[1] - TOE[1])), 0, 0), about=(0, TOE[1], TOE[0]))
    swivel = on_line(c, TOE, 37.0)                                           # rear sling swivel, under the line to the toe
    slope = math.degrees(math.atan2(TOE[1] - c[1], TOE[0] - c[0]))
    r.box(g, 'dark', -.9, .9, swivel[1] - 1.7, swivel[1] - 1.3, 35.8, 38.2, turn=(slope, 0, 0), about=(0, *swivel[::-1]))
    for side in (-1, 1):
        r.box(g, 'dark', side * .75 - .15, side * .75 + .15, swivel[1] - 1.4, swivel[1] + .2, 36.6, 37.2, turn=(slope, 0, 0),
              about=(0, *swivel[::-1]))
    # ---- magazine (its bone turned 8Â°, the bottom forward; the same once more for the one a reload brings): curved, 20 rounds
    t, bo, f, bk, h = MAG['top'], MAG['bottom'], MAG['front'], MAG['back'], MAG['half']
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        r.beam(bone, 'dark', h, bo + 3.5, t, f, bk, cut=.3)                  # straight above,
        r.beam(bone, 'dark', h - .02, bo, bo + 4.0, f, bk, cut=.3, turn=(-6, 0, 0), about=(0, bo + 4.0, bk))   # bent forward below
        r.beam(bone, 'steel', h + .15, bo - .5, bo + .1, f - .3, bk + .3, cut=.2, turn=(-6, 0, 0), about=(0, bo + 4.0, bk))  # floor plate
        for side in (-1, 1):                                                 # the ribs pressed into its sides
            r.box(bone, 'steel', side * (h - .04) - .06, side * (h - .04) + .06, bo + 4.5, t - 3.0, f + 2.2, f + 2.8)
        r.rod(round_bone, 'brass', .5, f + 1.1, bk - .7, cy=t - .3)          # the top round
        r.rod(round_bone, 'copper', .38, f + .1, f + 1.1, cy=t - .3)
    return r.cubes


def geometry(skeleton):
    """The model, on the bones of the MK14's .geo.json given."""
    return m1a.geometry(skeleton, rifle(), GRIP, MUZZLE, PIVOTS)


def texture():
    return m1a.texture(rifle(), MATERIALS)


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
    (out / 'gun_js14.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_js14.png').write_bytes(texture())
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
