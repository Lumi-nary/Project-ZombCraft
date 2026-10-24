"""Our own clips for our own B-F Pistol (deserteagle.py on pistol.py's layout): pistol_anim.py's clips with its numbers.

    python pistol_anim.py deserteagle --out DIR     writes the model, texture and clips there, for a look
"""
import deserteagle as gun
import pistol_anim


class Spec(pistol_anim.Spec):
    name = 'deserteagle'
    TRAVEL, LOCK = gun.SLIDE_TRAVEL, gun.SLIDE_LOCK
    HAMMER_DOWN = 35.0
    TILT = gun.TILT
    LUG = (0.0, gun.MAG_TOP, gun.MAG_Z)
    GRIP_MAG = (2.5, 8.0, 2.0)
    ON_SLIDE = (.8, 26.8, 4.0)              # over the slide's back, which is higher and wider than the M9's
    RELOAD = (-24, -16, -30, -2.0, 8.5, -1.0)
    DATA = dict(pistol_anim.Spec.DATA, viewRecoilAmplitude=7.0)      # the current B-F's kick (.44 Magnum), unchanged


DATA = Spec.DATA


def clips(geo):
    return pistol_anim.clips(geo, Spec)
