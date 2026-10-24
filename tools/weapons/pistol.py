"""What our own pistols (m9.py; later the M1911 and the B-F) share: their joint layout, solved here from each pistol's own
numbers, and the assembly of a pistol's cubes into a GeckoLib model on it. No number here is taken from the reference set.

A pistol (a `Pistol` subclass in its own module) says where things are on it, in the .geo.json's numbers (x to the gun's left,
y up, the muzzle towards -z, 1/16 block): the slide's and the hammer's pivots, the magazine's (and how far the grip leans back),
the line of sight and the rear sight, the bore and the muzzle, the ejection port, and where the middle of each hand is with the
shoulder it reaches from. The bones:

  main         the whole view (gun and both arms): what the clips turn and move
  gun          the frame, barrel and small parts; slide, hammer under it
  magazine     its round `bullet`; `magazine2` with `bullet2` is the one a reload brings. Both lean back with the grip
  rightarm     the fist round the grip; leftarm, the support hand round it. Placeholders: the game draws Steve's arms there
  scope        on the line of sight, EYE behind the rear sight: aiming puts it on the centre of the view
  muzzleflash  just in front of the muzzle; _shelleject_ at the ejection port

An arm is a 8 x 24 x 8 box hanging from its pivot (the shoulder) along its own -y, as the game's arm model is drawn on the bone:
its turn is the least that points it from the shoulder at the hand, and its pivot is where the hand then ends PALM past the
middle of the hand.
"""
import math

import numpy as np

import m1a

ARM = 24.0          # an arm placeholder's length; the game's arm at the scale it is drawn
ARM_HALF = 4.0      # and half its thickness
PALM = 2.5          # from the end of an arm back to the middle of its hand (as m1a_anim)
EYE = 19.0          # the scope bone this far behind the rear sight: where the eye is when the pistol is aimed at arm's length
# where the item sits: in first person (found by rendering: the gun low on the right, its sights pointing at the middle of
# the view, both hands in sight) and in third person (the size and offset every gun of the mod uses)
THIRD_PERSON = {'translation': [0.25, -4.75, 1], 'scale': [0.25, 0.25, 0.25]}


class Pistol:
    """One pistol's numbers. Subclasses set them; these are only the names."""
    name = 'pistol'
    MAIN = (0.0, 17.0, 4.0)             # what the clips turn the whole view about: the fist round the grip
    SLIDE = (0.0, 22.5, -6.0)
    HAMMER = (0.0, 20.4, 7.2)
    MAG = (0.0, 19.0, 2.0)              # the magazine's pivot: the middle of its top
    TILT = 13.4                         # how far the grip and the magazine lean back (degrees, the bottom towards +z)
    SIGHT = (25.3, 3.5)                 # (y, z): the line of sight, and the back of the rear sight
    BORE, MUZZLE = 23.1, -20.0
    EJECT = (-1.9, 23.6, -1.0)
    RIGHT = ((0.0, 12.5, 3.9), (-7.0, -4.0, 26.0))      # (the middle of the hand, the shoulder it reaches from)
    LEFT = ((3.6, 11.0, 2.5), (9.0, -6.0, 26.0))
    FIRST_PERSON = {'rotation': [0, 0, 6], 'translation': [-1.5, -21.5, -20.0]}


def leaning_box(r, bone, material, half_x, up, down, half_depth, at, tilt, **more):
    """A box of a grip (m1a.Rifle `r`): upright about `at` (y, z), `up` above it and `down` below, then leant back `tilt`
    degrees with the grip."""
    y, z = at
    return r.box(bone, material, -half_x, half_x, y - down, y + up, z - half_depth, z + half_depth, turn=(tilt, 0, 0), about=(0, y, z),
                 **more)


def serrations(z0, z1, y0, y1, groove):
    """A side pattern (m1a.paint decor): vertical grooves every other texel between z0 and z1, y0 and y1."""
    def pattern(z, y):
        return groove if z0 < z < z1 and y0 < y < y1 and math.floor(z) % 2 == 0 else None
    return pattern


def checkering(at, tilt, u, v, cut):
    """A side pattern: a checkerboard of `cut` squares on a grip panel leant back `tilt` about `at` (y, z), within u (along the
    panel's depth) and v (up it), both in the panel's own upright numbers from `at`."""
    y0, z0 = at
    a = math.radians(tilt)

    def pattern(z, y):
        dz, dy = z - z0, y - y0                     # back to the upright panel
        pu, pv = dz * math.cos(a) + dy * math.sin(a), -dz * math.sin(a) + dy * math.cos(a)
        if u[0] < pu < u[1] and v[0] < pv < v[1] and (math.floor(pu) + math.floor(pv)) % 2 == 0:
            return cut
        return None
    return pattern


def g(p):
    """.geo.json numbers to geoview's space (x mirrored; units kept)."""
    return np.array([-p[0], p[1], p[2]], float)


def euler(r):
    """Angles (x, y, z) in radians with r = Rz Ry Rx."""
    b = math.asin(max(-1.0, min(1.0, -r[2, 0])))
    return math.atan2(r[2, 1], r[2, 2]), b, math.atan2(r[1, 0], r[0, 0])


def align(a, b):
    """The least rotation taking unit vector a to unit vector b."""
    v, c = np.cross(a, b), float(np.dot(a, b))
    k = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + k + k @ k / (1 + c)


def arm_bone(name, hand, shoulder):
    """An arm placeholder bone whose hand is round `hand`, pointing from `shoulder` (both .geo.json numbers)."""
    d = g(hand) - g(shoulder)
    d /= np.linalg.norm(d)
    rot = align(np.array([0.0, -1.0, 0.0]), d)                  # geoview: the bone's rotation is rot_zyx(-rx, -ry, rz)
    a, b, c = euler(rot)
    rotation = [round(-math.degrees(a), 5), round(-math.degrees(b), 5), round(math.degrees(c), 5)]
    end = g(hand) + d * PALM
    pivot = end - d * ARM
    pivot = [round(-pivot[0], 4), round(pivot[1], 4), round(pivot[2], 4)]
    box = {'origin': [round(pivot[0] - ARM_HALF, 4), round(pivot[1] - ARM, 4), round(pivot[2] - ARM_HALF, 4)],
           'size': [2 * ARM_HALF, ARM, 2 * ARM_HALF]}
    return {'name': name, 'parent': 'main', 'pivot': pivot, 'rotation': rotation, 'cubes': [box]}


def feed_arm(pivot, direction, turn):
    """The left arm bone of a magazine held by itself (build.py's magazine_item_geo `left`), at `pivot`, turned so that while
    a round is fed (the clip adds `turn`, build.FEED_ARM, to the bone's angles) the arm points along `direction` (.geo.json
    numbers): in from the left, not up across the view from under it."""
    d = g(direction)
    a, b, c = euler(align(np.array([0.0, -1.0, 0.0]), d / np.linalg.norm(d)))
    final = (-math.degrees(a), -math.degrees(b), math.degrees(c))
    return list(pivot), [round(f - t, 5) for f, t in zip(final, turn)]


def bones(p):
    """The joint layout of pistol `p` (a Pistol class), without its own cubes."""
    sight_y, sight_z = p.SIGHT
    mag = {'pivot': list(p.MAG), 'rotation': [p.TILT, 0, 0]}
    return [
        {'name': 'bb_main', 'pivot': [0, 0, 0]},
        {'name': 'main', 'pivot': list(p.MAIN)},
        {'name': 'gun', 'parent': 'main', 'pivot': list(p.MAIN)},
        {'name': 'slide', 'parent': 'gun', 'pivot': list(p.SLIDE)},
        {'name': 'hammer', 'parent': 'gun', 'pivot': list(p.HAMMER)},
        dict(name='magazine', parent='main', **mag),
        {'name': 'bullet', 'parent': 'magazine', 'pivot': list(p.MAG)},
        dict(name='magazine2', parent='main', **mag),
        {'name': 'bullet2', 'parent': 'magazine2', 'pivot': list(p.MAG)},
        arm_bone('rightarm', *p.RIGHT),
        arm_bone('leftarm', *p.LEFT),
        {'name': 'scope', 'parent': 'main', 'pivot': [0, sight_y, sight_z + EYE],
         'cubes': [{'origin': [-.5, sight_y - .5, sight_z + EYE - .5], 'size': [1, 1, 1]}]},
        {'name': 'muzzleflash', 'parent': 'main', 'pivot': [0, p.BORE, p.MUZZLE - .6],
         'cubes': [{'origin': [-.5, p.BORE - .5, p.MUZZLE - 1.1], 'size': [1, 1, 1]}]},
        {'name': '_shelleject_', 'parent': 'main', 'pivot': list(p.EJECT)},
    ]


def skeleton(p, layout=None):
    """The layout as a .geo.json (placeholder cubes only) with the item's display transforms: what build_own.py builds on.
    `layout`: the function giving the bones (a rifle's, rifle.py), else this module's pistol bones."""
    display = {'thirdperson_righthand': THIRD_PERSON, 'thirdperson_lefthand': THIRD_PERSON,
               'firstperson_righthand': p.FIRST_PERSON, 'firstperson_lefthand': p.FIRST_PERSON}
    return {'format_version': '1.12.0', 'minecraft:geometry': [{'description': {
        'identifier': 'geometry.pzcraft_' + p.name, 'texture_width': 16, 'texture_height': 16,
        'visible_bounds_width': 4, 'visible_bounds_height': 3, 'visible_bounds_offset': [0, 1, 0]}, 'bones': (layout or bones)(p)}],
        'display': display}


def geometry(p, cubes, layout=None):
    """Pistol `p`'s model: its layout with `cubes` (m1a.Rifle's cubes, each naming its bone) on it, every face with its own
    rectangle of the texture. `layout` as for skeleton()."""
    placed = m1a.pack(cubes)
    out = []
    for bone in (layout or bones)(p):
        bone = dict(bone)
        if 'cubes' in bone:             # placeholders: never drawn by the game; one texel of the texture's corner
            bone['cubes'] = [dict(c, uv={f: {'uv': [m1a.SIZE - 2, m1a.SIZE - 2], 'uv_size': [1, 1]} for f in m1a.FACES})
                             for c in bone['cubes']]
        mine = []
        for i, cube in enumerate(cubes):
            if cube['bone'] != bone['name']:
                continue
            c = {'origin': cube['origin'], 'size': cube['size'],
                 'uv': {f: {'uv': list(placed[(i, f)][:2]), 'uv_size': list(placed[(i, f)][2:])} for f in m1a.FACES}}
            if 'rotation' in cube:
                c['rotation'], c['pivot'] = cube['rotation'], [round(v, 4) for v in cube['pivot']]
            if cube['inflate']:
                c['inflate'] = cube['inflate']
            mine.append(c)
        if mine:
            bone['cubes'] = bone.get('cubes', []) + mine
        out.append(bone)
    description = dict(skeleton(p, layout)['minecraft:geometry'][0]['description'], texture_width=m1a.SIZE, texture_height=m1a.SIZE)
    return {'format_version': '1.12.0', 'minecraft:geometry': [{'description': description, 'bones': out}]}


def free_patch(png, width=25, height=4):
    """A width x height rectangle of the texture no face uses (for the texels build.py paints for a magazine's rounds)."""
    import pngio
    alpha = pngio.read_bytes(png)[:, :, 3]
    size = alpha.shape[0]
    for v in range(size - height - 2, -1, -1):
        for u in range(0, size - width - 3):
            if not alpha[v:v + height, u:u + width].any():
                return (u, v)
    raise SystemExit('no free space in the texture for the magazine rounds')
