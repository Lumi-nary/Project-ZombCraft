"""Our own clips for the Trapper Carbine (trapper.py's model on the MK14's skeleton), made by m1a_anim.py's code with this
carbine's numbers. What differs from the M1A and the JS-14: its magazine is a pistol's and goes straight up into the well along
its lean (no rocking), and its handle is on the bolt itself (a blowback carbine: the handle goes back with every shot, and is
pulled back and let go to load). No key is taken from the reference set.

    python trapper_anim.py --out DIR    writes gun_trapper.geo.json, .png and .animation.json there, for a look
                                        (build.py calls clips() and DATA for the kind `trapper`)
"""
import argparse
import json
import math
from pathlib import Path

import m1a_anim
from m1a_anim import Keys, Spec

LEAN = math.radians(16.0)
DOWN = (0.0, -math.cos(LEAN), math.sin(LEAN))      # out of the well along the magazine (model numbers)


def along(d, extra=(0, 0, 0)):
    return tuple(d * a + b for a, b in zip(DOWN, extra))


class Trapper(Spec):
    name = 'trapper'
    # the JS-14's hold, the hand under this thinner, higher forend a little further forward (at z -24)
    LEFT_REST = dict(Spec.LEFT_REST, position=(5.02289, -0.50283, 19.05835))
    TRAVEL = 7.0
    HANDLE = (-2.5, 27.0, -8.5)     # the bolt's handle, bolt home
    GRIP_MAG = (2.2, 17.0, -3.1)    # on the magazine's left face, below the middle (its bone's numbers)
    LUG = (0.0, 24.5, -5.6)
    TILT = (-14, -18, -28, -2.0, 9.5, -9.0)    # its magazine is nearer the eye still than the JS-14's
    RIGHT_SHOULDER = (-24.0, 0.0, 40.0)         # as the JS-14's: its handle is near the eye too
    DRAW_FROM = (4.0, 14.1, -21.4)
    OFF_FOREND = (6.0, 16.5, -12.0)
    DATA = {'phasedReloads': [
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': False}, 'duration': 1950, 'animation': 'animation.model.reload'},
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': True}, 'duration': 2500, 'animation': 'animation.model.reloadempty'}],
        'viewRecoilAmplitude': 1.5}          # how hard a shot kicks the view (9 mm carbine); what a shot does stays PZ's

    @classmethod
    def mag_paths(cls, rig):
        """Straight out along the magazine's lean (the catch pressed at .45, it drops a little), then down and away; the new one
        brought up under the well in line with it and pushed straight home."""
        out = Keys((0, (0, 0, 0, 0)), (.45, (0, 0, 0, 0)), (.55, (0, *along(1.2)), 'io'), (.78, (0, *along(12.5, (.3, 0, 0))), 'in'),
                   (1.12, (25, *along(12.5, (6.0, -36.0, 10.0))), 'in'))
        back = Keys((1.12, (30, *along(13.0, (5.0, -34.0, 12.0)))), (1.38, (0, *along(9.0, (.3, 0, -.4))), 'out'),
                    (1.5, (0, *along(3.5)), 'io'), (1.62, (0, 0, 0, 0), 'in'))
        return out, back


DATA = Trapper.DATA


def clips(geo):
    """Every clip of the Trapper, by its logical name, for its model `geo` (trapper.geometry's)."""
    return m1a_anim.clips(geo, Trapper)


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build
    import trapper
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = trapper.geometry(json.loads((Path(__file__).resolve().parent / 'rifle_skeleton.json').read_text()))
    made = clips(geo)
    (out / 'gun_trapper.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_trapper.png').write_bytes(trapper.texture())
    (out / 'gun_trapper.animation.json').write_text(json.dumps({'format_version': '1.8.0', 'animations': {
        'animation.model.' + k: v for k, v in made.items()}}, separators=(',', ':')))
    for k, v in made.items():
        print(f"{k:12s} {v['animation_length']:5.2f}s  " + ' '.join(v['bones']))
