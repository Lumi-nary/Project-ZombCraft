"""Our own M1A Rifle (PZ's Base.AssaultRifle2): the model and its texture, made here from nothing but numbers.

    python m1a.py [--out DIR]      writes gun_m1a.geo.json and gun_m1a.png there, for a look (build.py calls geometry() and texture())

The rifle is PZ's M1A: a walnut stock, a brown upper handguard, dark parkerized steel, a black 20-round magazine. No part of it
is taken from the reference set. Only the skeleton is the the reference set MK14's (bone names and pivots, and the two placeholder arm
boxes), because for now that gun's clips move this one: the magazine, the bolt (`action`), the operating rod with its handle
(`action2`), the muzzle and the rear sight's aperture are where those clips and the sight alignment expect them. The MK14 is
the same action in a chassis, so this costs the rifle nothing.

It is drawn the way the the reference set guns are: slopes and curves are cubes turned to follow the outline (the forend deepening
towards the receiver, the wrist falling behind the trigger guard, the long line from there to the toe), not stacks of boxes;
round parts have eight sides; small parts (the sights, the knobs) are thin. The side view follows PZ's own M1A mesh.

Numbers are the .geo.json's: x to the rifle's left, y up, the muzzle towards -z, 1/16 block. One texel a unit, as Minecraft and
the the reference set guns have it. Every face has its own rectangle of the texture, painted from where the face is on the rifle, so
the grain of the wood runs on from one cube to the next. Each material has five tones, pixel-art fashion: a face is its base
tone, lit along its upper edge and darker along its lower one; the wood has grain lines that wander slowly along it.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path

BORE = 26.5             # the barrel's axis
MUZZLE, BUTT = -94.0, 35.4
SIGHT = (29.375, 1.95)  # the rear sight's aperture (the MK14's `scope` pivot: the sight alignment looks through it)
SIZE = 256              # the texture
# The MK14 has a pistol grip under its receiver and its right arm ends there. This stock is held by its wrist: the arm bone
# (pivot and placeholder box) is this much higher and further back, so the fist closes round the wood behind the trigger guard.
GRIP = (0.0, 4.5, 4.0)
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
# the side view of the stock behind the receiver, (z, y): the underside leaves the receiver's line, falls steeply behind the
# trigger guard, then runs in one line to the toe; the butt plate leans back a little from heel to toe
WRIST = [(-1.0, 19.6), (2.2, 17.4), (5.2, 15.1)]
TOE = (BUTT, 8.6)
HEEL = (BUTT - .6, 23.0)
PLATE = 1.2             # the butt plate's thickness
# what each material is painted with: five tones from dark to light (every texel is one of them), the tone a face is painted
# in, and whether it has a grain
WALNUT = ((66, 28, 16), (88, 42, 27), (110, 55, 37), (136, 75, 53), (154, 95, 69))      # the the reference set guns' reddish walnut
STEEL = ((27, 28, 30), (39, 40, 43), (51, 52, 56), (65, 66, 71), (84, 86, 92))
MATERIALS = {
    'wood':   (WALNUT, 3, True),                # the stock: walnut
    'guard':  (((50, 30, 22), (68, 42, 32), (86, 56, 44), (106, 72, 58), (124, 88, 72)), 3, True),   # the upper handguard: duller
    'steel':  (STEEL, 2, False),
    'dark':   (STEEL, 1, False),                # the magazine, the sights' blade and aperture, the butt plate
    'bright': (STEEL[2:] + ((104, 106, 112), (126, 128, 134)), 2, False),   # the bolt, worn by its travel
    'brass':  (((122, 92, 42), (152, 117, 56), (180, 142, 72), (202, 164, 90), (224, 192, 122)), 2, False),
    'copper': (((92, 54, 35), (120, 72, 47), (148, 92, 61), (170, 110, 75), (192, 132, 93)), 2, False),
    'arm':    (((120, 200, 160),) * 5, 0, False),   # the placeholder arm boxes (never drawn in the game)
}
LIGHT = {'up': 1.06, 'down': .84, 'north': .93, 'south': .93, 'east': 1.0, 'west': 1.0}


class Rifle:
    """The cubes, bone by bone."""
    def __init__(self):
        self.cubes = []

    def box(self, bone, material, x0, x1, y0, y1, z0, z1, turn=None, about=None, inflate=0.0, edges=('top', 'bottom')):
        """One cube. `edges`: which of its sides' upper and lower edges are on the rifle's outline (only those are shaded)."""
        cube = {'bone': bone, 'material': material, 'origin': [round(v, 4) for v in (x0, y0, z0)],
                'size': [round(v, 4) for v in (x1 - x0, y1 - y0, z1 - z0)], 'inflate': inflate, 'edges': edges}
        if turn is not None:
            cube['rotation'] = [round(a, 3) for a in turn]
            cube['pivot'] = [round(v, 4) for v in (about if about is not None else ((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2))]
        self.cubes.append(cube)
        return cube

    def beam(self, bone, material, half, y0, y1, z0, z1, cut=.5, cx=0.0, top=True, bottom=True, **more):
        """A bar along the rifle with its long edges taken off (the upper two, the lower two, or all four): one cube as wide as
        it is, one as tall. An edge that lies inside another part keeps its corner, so no groove shows where they meet."""
        self.box(bone, material, cx - half, cx + half, y0 + (cut if bottom else 0), y1 - (cut if top else 0), z0, z1,
                 edges=tuple(e for e, on in (('top', top), ('bottom', bottom)) if on), **more)
        if cut:
            self.box(bone, material, cx - half + cut, cx + half - cut, y0, y1, z0, z1, inflate=-.01, edges=(), **more)

    def slab(self, bone, material, half, p, q, depth, up=False, cut=.6, inside=False, **more):
        """A beam laid along a line of the side view, from p to q (two (z, y) points), `depth` thick below the line (above it
        with up=True: the line is then its underside). Only the edges on the line are taken off and shaded; one inside the
        stock (filling between the others) has neither."""
        (z0, y0), (z1, y1) = p, q
        length = math.hypot(z1 - z0, y1 - y0)
        turn = (math.degrees(math.atan2(y1 - y0, z1 - z0)), 0, 0)
        lo, hi = (y0, y0 + depth) if up else (y0 - depth, y0)
        self.beam(bone, material, half, lo, hi, z0, z0 + length, cut=0 if inside else cut, top=not (up or inside), bottom=up and not inside,
                  turn=turn, about=(0, y0, z0), **more)

    def bars(self, bone, material, half, points, thick):
        """A thin bent bar through (z, y) points of the side view: one turned cube a stretch, each running on past its ends by
        half its thickness, so the bends close."""
        for (z0, y0), (z1, y1) in zip(points, points[1:]):
            length, e = math.hypot(z1 - z0, y1 - y0), thick / 2
            self.box(bone, material, -half, half, y0 - e, y0 + e, z0 - e, z0 + length + e,
                     turn=(math.degrees(math.atan2(y1 - y0, z1 - z0)), 0, 0), about=(0, y0, z0))

    def rod(self, bone, material, radius, z0, z1, cy=BORE, cx=0.0, round_it=True):
        """A round bar along the rifle: a square one, and the same turned an eighth so that it has eight sides."""
        self.box(bone, material, cx - radius, cx + radius, cy - radius, cy + radius, z0, z1)
        if round_it:
            self.box(bone, material, cx - radius, cx + radius, cy - radius, cy + radius, z0, z1, turn=(0, 0, 45), inflate=-.01)

    def knob(self, bone, material, radius, x0, x1, cy, cz):
        """A round knob on the side of the rifle (its axis across it): eight-sided, like the rods."""
        self.box(bone, material, x0, x1, cy - radius, cy + radius, cz - radius, cz + radius)
        self.box(bone, material, x0, x1, cy - radius, cy + radius, cz - radius, cz + radius, turn=(45, 0, 0), inflate=-.01)


def on_line(p, q, z):
    """The point of the line through p and q (side view) at z."""
    return (z, p[1] + (q[1] - p[1]) * (z - p[0]) / (q[0] - p[0]))


def plate_back(y, toe=TOE, heel=HEEL):
    """Where the butt plate's back is at height y (it leans back from the heel to the toe)."""
    return toe[0] - (y - toe[1]) * (toe[0] - heel[0]) / (heel[1] - toe[1])


def into_plate(p, q, depth, up, toe=TOE, heel=HEEL, plate=PLATE):
    """Where on the line from p through q a slab `depth` thick (above the line with up) ends so that both corners of its end
    are inside the butt plate: a cube's end is square to it, the plate leans, so the slab stops where its corner furthest
    back is just short of the plate's back. Says so if its other corner then stops short of the plate's front (a gap)."""
    a = math.atan2(q[1] - p[1], q[0] - p[0])
    d, n = (math.cos(a), math.sin(a)), (-math.sin(a) * (1 if up else -1), math.cos(a) * (1 if up else -1))
    lean = (toe[0] - heel[0]) / (heel[1] - toe[1])
    corners = [p, (p[0] + depth * n[0], p[1] + depth * n[1])]
    length = min((toe[0] - .08 - (cy - toe[1]) * lean - cz) / (d[0] + d[1] * lean) for cz, cy in corners)
    for cz, cy in corners:
        z, y = cz + length * d[0], cy + length * d[1]
        if z < plate_back(y, toe, heel) - plate - .02:
            print(f'a slab ends {plate_back(y, toe, heel) - plate - z:.2f} short of the butt plate at y={y:.1f}')
    return (p[0] + length * d[0], p[1] + length * d[1])


def rifle():
    r = Rifle()
    g = 'gun'
    # ---- the barrel and what is on it
    r.rod(g, 'steel', 1.0, MUZZLE, -86.0)                                    # flash suppressor
    r.box(g, 'dark', -.3, .3, BORE - 1.05, BORE + 1.05, MUZZLE + .8, -87.2, inflate=.02)      # its slots, seen from the sides
    r.box(g, 'dark', -1.05, 1.05, BORE - .3, BORE + .3, MUZZLE + .8, -87.2, inflate=.02)
    r.beam(g, 'steel', .75, BORE + .7, BORE + 1.5, -90.7, -88.1, cut=.3, bottom=False)       # front sight: a low base,
    r.box(g, 'dark', -.22, .22, BORE + 1.4, SIGHT[0] + .1, -89.7, -88.9)     # a thin blade, its tip on the line of sight,
    for side in (-1, 1):                                                     # and two thin ears leaning out to guard it
        r.box(g, 'steel', side * .85 - .15, side * .85 + .15, BORE + 1.0, BORE + 2.9, -90.3, -88.3, turn=(0, 0, -side * 12),
              about=(side * .85, BORE + 1.0, -89.3))
    r.rod(g, 'steel', .85, -86.0, -62.0)                                     # barrel
    r.rod(g, 'steel', .95, -84.5, -67.0, cy=BORE - 2.2)                      # gas cylinder under it
    r.rod(g, 'dark', .65, -85.5, -84.5, cy=BORE - 2.2)                       # gas plug
    r.beam(g, 'steel', 1.1, BORE - 3.3, BORE + 1.1, -86.0, -84.6, cut=.35)   # gas cylinder lock, round the barrel and the cylinder
    r.box(g, 'steel', -.45, .45, BORE - 4.2, BORE - 3.05, -81.0, -77.5)      # bayonet lug
    r.knob(g, 'dark', .45, -1.5, -.9, BORE - 2.2, -70.7)                     # spindle valve
    r.beam(g, 'steel', 1.15, BORE - 3.3, BORE + 1.15, -68.2, -66.8, cut=.35)  # the cylinder's rear ring
    # ---- front band, handguard, forend
    r.beam(g, 'steel', 2.5, 21.5, 28.45, -66.6, -65.0, cut=.7)               # front band and stock ferrule
    r.box(g, 'steel', -1.0, 1.0, 19.8, 20.2, -66.1, -65.5)                   # front sling swivel
    r.box(g, 'steel', -1.0, -.75, 20.2, 21.6, -66.1, -65.5)
    r.box(g, 'steel', .75, 1.0, 20.2, 21.6, -66.1, -65.5)
    r.beam(g, 'guard', 1.95, 25.5, 28.25, -65.0, -27.4, cut=.75)             # upper handguard
    for z in range(-60, -30, 6):                                             # its vent slots
        r.box(g, 'dark', -2.0, 2.0, 26.6, 27.0, z, z + 2.2)
    r.beam(g, 'wood', 2.35, 23.4, 25.6, -65.0, -27.5, cut=.5, bottom=False)  # forend: level under the handguard on top,
    front, back = (-65.0, 21.9), (-27.5, 20.0)
    r.slab(g, 'wood', 2.3, front, back, 3.6, up=True, cut=.7)                # deepening towards the receiver underneath
    # ---- receiver
    r.box(g, 'steel', -2.1, 2.1, 24.7, 25.6, -27.0, 2.6)                     # receiver: its floor,
    r.beam(g, 'steel', 2.1, 25.6, 27.5, -9.5, 2.6, cut=.4, bottom=False)     # the rear, which the bolt goes back under,
    r.beam(g, 'steel', 2.0, 24.7, 28.5, -27.4, -23.6, cut=.7)                # the ring, where the barrel screws in,
    r.box(g, 'steel', .95, 2.1, 25.6, 27.9, -23.6, -9.5)                     # the left wall of the bolt's way
    r.box(g, 'steel', .95, 2.1, 27.8, 28.1, -23.6, -9.5, inflate=-.05)
    r.box(g, 'steel', -2.1, -1.55, 25.6, 26.2, -23.6, -9.5)                  # and the low right rail: the bolt shows over it
    r.beam(g, 'steel', 1.9, 27.3, 28.5, -9.5, -5.2, cut=.4, bottom=False)    # bridge with the clip guide
    r.box(g, 'dark', -.6, .6, 28.4, 28.75, -8.9, -5.8)
    # ---- rear sight: kept small and thin, it is what the eye looks past
    r.beam(g, 'steel', 1.75, 27.3, 28.2, -5.2, 2.6, cut=.35, bottom=False)   # its base
    for side in (-1, 1):                                                     # the ears that guard the aperture: thin plates, tall
        x = side * 1.25                                                      # by it, lower in front, their top corners taken off
        for z0, z1, top in ((1.0, 2.5, 29.85), (-.6, 1.0, 29.05)):           # (one plate a little lower, one a little shorter)
            r.box(g, 'steel', x - .15, x + .15, 28.1, top - .3, z0, z1)
            r.box(g, 'steel', x - .15, x + .15, 28.1, top, z0 + .3, z1 - (.3 if z1 > 2 else 0), inflate=-.01)
    y, z = SIGHT
    r.box(g, 'dark', -.52, .52, 28.1, y - .36, z - .2, z + .2)               # the aperture: a hole between four thin bars
    r.box(g, 'dark', -.52, .52, y + .36, y + .58, z - .2, z + .2)
    r.box(g, 'dark', -.52, -.36, y - .36, y + .36, z - .2, z + .2)
    r.box(g, 'dark', .36, .52, y - .36, y + .36, z - .2, z + .2)
    r.knob(g, 'steel', .55, -1.95, -1.4, 28.7, 1.3)                          # windage knob (right), on the ear
    r.box(g, 'dark', -2.1, -1.95, 28.45, 28.95, 1.05, 1.55)
    r.knob(g, 'steel', .6, 1.4, 2.0, 28.75, 1.3)                             # elevation knob (left), on the ear
    r.box(g, 'dark', 2.0, 2.15, 28.5, 29.0, 1.05, 1.55)
    r.box(g, 'steel', 2.1, 2.45, 25.0, 26.3, -22.5, -19.0)                   # bolt stop
    r.box(g, 'steel', -2.45, -2.1, 25.0, 26.2, -4.5, -.5)                    # connector lock
    r.box(g, 'steel', -2.6, -2.1, 24.3, 24.75, -27.0, -9.0)                  # the track the operating rod runs in
    # ---- bolt and operating rod (they go back 10 with a shot)
    r.box('action', 'bright', -1.5, .9, 25.6, 27.3, -24.0, -12.4)            # bolt
    r.rod('action', 'steel', .5, -23.4, -21.4, cy=26.2, cx=-1.8)             # its roller
    r.box('action2', 'steel', -2.75, -2.15, 24.75, 25.85, -60.0, -21.4)      # operating rod
    r.box('action2', 'steel', -3.9, -2.15, 25.2, 26.5, -25.6, -21.6)         # its arm
    r.rod('action2', 'dark', .8, -26.0, -23.8, cy=25.85, cx=-4.3)            # and handle
    # ---- the stock along the receiver, and the trigger group under it
    r.beam(g, 'wood', 2.45, 19.6, 25.2, -27.5, 2.6, cut=.6)
    r.box(g, 'steel', -.8, .8, 19.35, 19.65, -14.8, -2.6)                    # housing plate
    r.bars(g, 'steel', .25, [(-9.4, 19.7), (-9.2, 18.0), (-8.6, 17.0), (-7.4, 16.5), (-5.0, 16.5), (-3.9, 16.95),   # trigger guard:
                              (-3.35, 18.0), (-3.2, 19.7)], .45)              # a rounded loop, 6 long and 3 deep, as PZ's
    r.bars(g, 'dark', .2, [(-6.8, 19.6), (-7.0, 18.4), (-7.5, 17.5)], .4)    # trigger: its tip curves forward, the finger in its hollow
    r.box(g, 'dark', -.2, .2, 18.5, 19.6, -8.9, -8.4)                        # safety, at the front of the guard
    r.box(g, 'dark', -.35, .35, 18.3, 19.6, -14.6, -13.8)                    # magazine catch
    # ---- wrist and butt, each slope one turned beam: the underside falls behind the trigger guard and runs on to the toe;
    # the top dips at the wrist and runs back almost level as the comb. Pieces that overlap differ a little in width, so no two
    # faces lie in one plane (they would flicker), and each overlaps the next past their joint, so no notch shows there.
    (a, b, c), comb_line = WRIST, ((9.5, 23.4), (HEEL[0], 22.7))
    r.slab(g, 'wood', 2.2, a, on_line(a, b, b[0] + .4), 4.0, up=True)
    r.slab(g, 'wood', 2.18, b, c, 4.6, up=True)
    r.slab(g, 'wood', 2.2, c, into_plate(c, TOE, 3.5, True), 3.5, up=True)                    # the long line to the toe
    r.slab(g, 'wood', 2.15, (2.6, 25.2), on_line((2.6, 25.2), comb_line[0], 10.0), 4.2)       # the top of the wrist
    start = on_line(*comb_line, 8.8)
    r.slab(g, 'wood', 2.13, start, into_plate(start, comb_line[1], 7.0, False), 7.0)          # the comb
    low = on_line(c, TOE, 6.0)                                                                # between them
    low = (low[0], low[1] + 3.1)
    r.slab(g, 'wood', 2.08, low, into_plate(low, (TOE[0], TOE[1] + 3.1), 4.5, True), 4.5, up=True, inside=True)
    r.box(g, 'wood', -2.04, 2.04, 18.0, 23.2, 2.6, 9.5, edges=())
    r.box(g, 'dark', -2.35, 2.35, TOE[1] - .1, HEEL[1] + .1, TOE[0] - PLATE, TOE[0],                                  # butt plate
          turn=(math.degrees(math.atan2(TOE[0] - HEEL[0], HEEL[1] - TOE[1])), 0, 0), about=(0, TOE[1], TOE[0]))
    swivel = on_line(WRIST[-1], TOE, 28.4)                                   # rear sling swivel, under the line to the toe
    slope = math.degrees(math.atan2(TOE[1] - WRIST[-1][1], TOE[0] - WRIST[-1][0]))
    r.box(g, 'steel', -.9, .9, swivel[1] - 1.7, swivel[1] - 1.3, 27.2, 29.6, turn=(slope, 0, 0), about=(0, *swivel[::-1]))
    for side in (-1, 1):
        r.box(g, 'steel', side * .75 - .15, side * .75 + .15, swivel[1] - 1.4, swivel[1] + .2, 28.0, 28.6, turn=(slope, 0, 0),
              about=(0, *swivel[::-1]))
    # ---- magazine (the bone is turned 8 degrees; the same once more for the one a reload brings)
    for bone, round_bone in (('magazine', 'bullet'), ('magazine2', 'bullet2')):
        r.beam(bone, 'dark', 1.5, 6.5, 21.0, -24.1, -15.1, cut=.3)
        r.beam(bone, 'steel', 1.65, 6.0, 6.6, -24.4, -14.8, cut=.2)          # floor plate
        r.box(bone, 'dark', -.5, .5, 7.2, 19.5, -24.4, -24.05)               # the rib down its front
        for side in (-1, 1):                                                 # the ribs pressed into its sides
            r.box(bone, 'steel', side * 1.56 - .06, side * 1.56 + .06, 8.0, 19.0, -21.6, -21.0)
        r.rod(round_bone, 'brass', .6, -22.2, -16.2, cy=20.9)                # the top round
        r.rod(round_bone, 'copper', .45, -23.6, -22.2, cy=20.9)
    return r.cubes


# ---------------------------------------------------------------- texture

def faces_of(cube):
    """Each face's size in texels and which of the rifle's axes run along its u and v."""
    sx, sy, sz = cube['size']
    return {'north': (sx, sy, 'x', 'y'), 'south': (sx, sy, 'x', 'y'), 'east': (sz, sy, 'z', 'y'), 'west': (sz, sy, 'z', 'y'),
            'up': (sx, sz, 'x', 'z'), 'down': (sx, sz, 'x', 'z')}


def pack(cubes):
    """A rectangle of the texture for every face (one texel a unit), in rows, the tallest first."""
    rects = []
    for i, cube in enumerate(cubes):
        for face, (w, h, _, _) in faces_of(cube).items():
            rects.append((max(1, round(h)), max(1, round(w)), i, face))
    rects.sort(reverse=True)
    x = y = row = 0
    placed = {}
    for h, w, i, face in rects:
        if x + w > SIZE:
            x, y, row = 0, y + row, 0
        if y + h > SIZE:
            raise SystemExit('the texture is too small for the rifle')
        placed[(i, face)] = (x, y, w, h)
        x, row = x + w, max(row, h)
    return placed


def noise(*key):
    return int(hashlib.md5(repr(key).encode()).hexdigest()[:8], 16) / 0xffffffff


def grain(across, z, material):
    """How many tones a texel of wood is off its face's: thin dark lines that wander slowly across the rows along the rifle and
    break off here and there."""
    drift = round(1.6 * math.sin(z / 13 + 6.3 * noise('p', material)) + z / 37)
    if (across - drift) % 4 == 0 and noise('gap', material, across - drift, (z + int(noise('o', across) * 11)) // 9) > .4:
        return -1
    return 0


def world_zy(cube, z, y):
    """A point of a cube's side (its own numbers) where it is on the rifle: the cube turned about x, as slabs and bars are."""
    if 'rotation' not in cube or not cube['rotation'][0]:
        return z, y
    a = math.radians(cube['rotation'][0])
    pz, py = cube['pivot'][2], cube['pivot'][1]
    dz, dy = z - pz, y - py
    return pz + dz * math.cos(a) - dy * math.sin(a), py + dz * math.sin(a) + dy * math.cos(a)


def paint(cubes, placed, materials=None, decor=None):
    import numpy as np
    img = np.zeros((SIZE, SIZE, 4), np.uint8)
    for (i, face), (x, y, w, h) in placed.items():
        cube = cubes[i]
        tones, base, grained = (materials or MATERIALS)[cube['material']]
        _, _, along_u, along_v = faces_of(cube)[face]
        lo = dict(zip('xyz', cube['origin']))
        for v in range(h):
            for u in range(w):
                at = {along_u: lo[along_u] + u, along_v: lo[along_v] + (h - 1 - v if along_v == 'y' else v)}
                # a pattern on the wood, where it is on the rifle (a dict: a pattern for each material named in it)
                pattern = decor.get(cube['material']) if isinstance(decor, dict) else decor if cube['material'] == 'wood' else None
                if pattern is not None and face in ('east', 'west'):
                    rgb = pattern(*world_zy(cube, at['z'] + .5, at['y'] + .5))
                    if rgb is not None:
                        img[y + v, x + u] = [max(0, min(255, round(c * LIGHT[face]))) for c in rgb] + [255]
                        continue
                z = round(at.get('z', lo['z']))
                tone = base
                if grained and face in ('north', 'south'):                  # end grain
                    tone -= 1
                elif grained:
                    tone += grain(round(at['y'] if 'y' in at else at['x'] * 2), z, cube['material'])
                elif cube['material'] in ('steel', 'bright') and noise('wear', round(at.get('x', lo['x'])), round(at.get('y', lo['y'])), z) > .97:
                    tone += 1                                               # a worn spot
                if face in ('north', 'south', 'east', 'west') and h > 2:   # lit along an upper edge on the outline, darker
                    edges = cube['edges']                                   # towards a lower one (two rows of it on a deep face)
                    if v == 0 and 'top' in edges:
                        tone = base + 1
                    if 'bottom' in edges and v >= h - (2 if h > 5 else 1):
                        tone = base - (2 if v == h - 1 and h > 5 else 1)
                elif face == 'up' and w > 2 and u in (0, w - 1):
                    tone -= 1
                rgb = tones[max(0, min(len(tones) - 1, tone))]
                img[y + v, x + u] = [max(0, min(255, round(c * LIGHT[face]))) for c in rgb] + [255]
    return img


def geometry(skeleton, cubes=None, grip=GRIP, muzzle=MUZZLE, pivots=None, rotations=None):
    """The model, on the bones of the MK14's .geo.json given (their pivots, and its two arm boxes). Another rifle made the same
    way (js14.py) passes its own cubes, where its fist is (`grip`), its muzzle, and bones it moves (`pivots`: name -> pivot)."""
    cubes = rifle() if cubes is None else cubes
    placed = pack(cubes)
    theirs = {b['name']: b for b in skeleton['minecraft:geometry'][0]['bones']}
    keep = ['bb_main', 'main', 'gun', 'action', 'action2', 'magazine', 'bullet', 'magazine2', 'bullet2', 'rightarm', 'leftarm', 'scope',
            'muzzleflash', '_shelleject_', '_camera_']
    bones = []
    for name in keep:
        bone = {k: copy.deepcopy(v) for k, v in theirs[name].items() if k != 'cubes'}
        if name in ('rightarm', 'leftarm', 'scope', 'muzzleflash'):       # placeholders: the game never draws their cubes
            bone['cubes'] = [dict(origin=c['origin'], size=c['size'], uv={f: {'uv': [SIZE - 2, SIZE - 2], 'uv_size': [1, 1]} for f in FACES})
                             for c in theirs[name].get('cubes', [])]
        if name == 'rightarm':
            bone['pivot'] = [round(v + d, 4) for v, d in zip(bone['pivot'], grip)]
            for c in bone['cubes']:
                c['origin'] = [round(v + d, 4) for v, d in zip(c['origin'], grip)]
        if name == 'muzzleflash':
            bone['pivot'] = [0, BORE, muzzle - .6]
            bone['cubes'] = [dict(origin=[-.5, BORE - .5, muzzle - 1.1], size=[1, 1, 1], uv=bone['cubes'][0]['uv'])] if bone.get('cubes') else []
        if pivots and name in pivots:
            moved = [n - o for n, o in zip(pivots[name], bone['pivot'])]
            bone['pivot'] = list(pivots[name])
            if name in ('scope', 'leftarm'):                                 # a placeholder's box goes with its bone
                for c in bone.get('cubes', []):
                    c['origin'] = [round(v + d, 4) for v, d in zip(c['origin'], moved)]
        if rotations and name in rotations:
            bone['rotation'] = list(rotations[name])
        mine = []
        for i, cube in enumerate(cubes):
            if cube['bone'] != name:
                continue
            out = {'origin': cube['origin'], 'size': cube['size'],
                   'uv': {face: {'uv': [placed[(i, face)][0], placed[(i, face)][1]], 'uv_size': [placed[(i, face)][2], placed[(i, face)][3]]} for face in FACES}}
            if 'rotation' in cube:
                out['rotation'], out['pivot'] = cube['rotation'], [round(v, 4) for v in cube['pivot']]
            if cube['inflate']:
                out['inflate'] = cube['inflate']
            mine.append(out)
        if mine:
            bone['cubes'] = bone.get('cubes', []) + mine
        bones.append(bone)
    description = dict(skeleton['minecraft:geometry'][0]['description'], texture_width=SIZE, texture_height=SIZE)
    return {'format_version': skeleton.get('format_version', '1.12.0'), 'minecraft:geometry': [{'description': description, 'bones': bones}]}


def texture(cubes=None, materials=None, decor=None):
    import pngio
    cubes = rifle() if cubes is None else cubes
    img = paint(cubes, pack(cubes), materials, decor)
    img[SIZE - 2:, SIZE - 2:] = (*MATERIALS['arm'][0][0], 255)
    return pngio.to_bytes(img)


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
    (out / 'gun_m1a.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_m1a.png').write_bytes(texture())
    shown = sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones'] if b['name'] not in ('rightarm', 'leftarm', 'scope', 'muzzleflash'))
    print(f'{shown} cubes -> {out}')
