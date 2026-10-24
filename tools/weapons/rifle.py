"""The joint layout of our own AR-type rifles (m16a1.py), solved from the rifle's own numbers the way pistol.py solves a
pistol's; pistol.geometry and pistol.skeleton assemble the model on it (layout=rifle.bones). No number here is taken from Point
Blank, and none from the MK14 layout the M1A family uses (rifle_skeleton.json).

  main         the whole view (rifle and both arms): what the clips turn and move
  gun          receivers, barrel, furniture and small parts
  bolt         the bolt carrier, seen through the ejection port: it goes back with a shot and stays back on an empty rifle
  charging     the charging handle at the back of the upper receiver
  magazine     its top round `bullet`; `magazine2` with `bullet2` is the one a reload brings. Both lean with the well
  rightarm     the fist round the pistol grip; leftarm, the support hand under the handguard (placeholders, as pistol.py)
  scope        at the rear sight's aperture: aiming puts it on the centre of the view (as the M1A's)
  muzzleflash  just in front of the flash hider; _shelleject_ at the ejection port
"""
import pistol


class Rifle:
    """One rifle's numbers. Subclasses set them; these are only the names."""
    name = 'rifle'
    MAIN = (0.0, 18.0, 0.0)
    BOLT = (0.0, 24.0, -6.0)
    CHARGING = (0.0, 25.2, 5.5)
    MAG = (0.0, 19.5, -8.3)
    MAG_TILT = -11.0                    # the magazine's lean (degrees; negative: its bottom forward)
    SIGHT = (29.3, 3.4)                 # (y, z) of the rear sight's aperture
    BORE, MUZZLE = 22.85, -74.0
    EJECT = (-2.2, 24.1, -7.0)
    RIGHT = ((0.0, 11.5, 5.2), (-7.0, 2.0, 32.0))
    LEFT = ((1.5, 18.0, -36.0), (14.0, 2.0, 14.0))
    FIRST_PERSON = {'rotation': [0, 0, 3], 'translation': [-2.0, -23.5, 0.0]}


def bones(p):
    """The joint layout of rifle `p` (a Rifle class), without its own cubes."""
    sight_y, sight_z = p.SIGHT
    mag = {'pivot': list(p.MAG), 'rotation': [p.MAG_TILT, 0, 0]}
    return [
        {'name': 'bb_main', 'pivot': [0, 0, 0]},
        {'name': 'main', 'pivot': list(p.MAIN)},
        {'name': 'gun', 'parent': 'main', 'pivot': list(p.MAIN)},
        {'name': 'bolt', 'parent': 'gun', 'pivot': list(p.BOLT)},
        {'name': 'charging', 'parent': 'gun', 'pivot': list(p.CHARGING)},
        dict(name='magazine', parent='main', **mag),
        {'name': 'bullet', 'parent': 'magazine', 'pivot': list(p.MAG)},
        dict(name='magazine2', parent='main', **mag),
        {'name': 'bullet2', 'parent': 'magazine2', 'pivot': list(p.MAG)},
        pistol.arm_bone('rightarm', *p.RIGHT),
        pistol.arm_bone('leftarm', *p.LEFT),
        {'name': 'scope', 'parent': 'main', 'pivot': [0, sight_y, sight_z],
         'cubes': [{'origin': [-.5, sight_y - .5, sight_z - .5], 'size': [1, 1, 1]}]},
        {'name': 'muzzleflash', 'parent': 'main', 'pivot': [0, p.BORE, p.MUZZLE - .6],
         'cubes': [{'origin': [-.5, p.BORE - .5, p.MUZZLE - 1.1], 'size': [1, 1, 1]}]},
        {'name': '_shelleject_', 'parent': 'main', 'pivot': list(p.EJECT)},
    ]
