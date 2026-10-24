"""Our own clips for our own M9 Pistol (m9.py on pistol.py's layout): pistol_anim.py's clips with the M9's numbers.

    python pistol_anim.py m9 --out DIR      writes the model, texture and clips there, for a look
"""
import m9
import pistol_anim


class Spec(pistol_anim.Spec):
    name = 'm9'
    TRAVEL, LOCK = m9.SLIDE_TRAVEL, m9.SLIDE_LOCK
    TILT = m9.TILT
    LUG = (0.0, m9.MAG_TOP, m9.MAG_Z)


DATA = Spec.DATA


def clips(geo):
    return pistol_anim.clips(geo, Spec)
