"""Our own clips for our own M1911 Pistol (m1911a1.py on pistol.py's layout): pistol_anim.py's clips with the M1911's numbers.

    python pistol_anim.py m1911a1 --out DIR     writes the model, texture and clips there, for a look
"""
import m1911a1 as gun
import pistol_anim


class Spec(pistol_anim.Spec):
    name = 'm1911'
    TRAVEL, LOCK = gun.SLIDE_TRAVEL, gun.SLIDE_LOCK
    HAMMER_DOWN = 35.0                      # the spur leans back less far than the M9's
    TILT = gun.TILT
    LUG = (0.0, gun.MAG_TOP, gun.MAG_Z)
    GRIP_MAG = (2.4, 8.5, 2.2)
    ON_SLIDE = (.8, 26.2, 3.6)              # its slide is lower and shorter at the back than the M9's
    DATA = dict(pistol_anim.Spec.DATA, viewRecoilAmplitude=3.0)      # the current M1911's kick, unchanged


DATA = Spec.DATA


def clips(geo):
    return pistol_anim.clips(geo, Spec)
