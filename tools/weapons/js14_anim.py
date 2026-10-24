"""Our own clips for the JS-14 Rifle (js14.py's model on the MK14's skeleton). The Mini-14 works as the M14 does (a magazine
hooked in by its front and rocked back, an operating handle on the right that the right hand pulls back and lets go), so its
clips are made by m1a_anim.py's code with this rifle's numbers: where its forend, magazine and handle are, how far its bolt
goes, how the view is turned to show them. No key is taken from the reference set.

    python js14_anim.py --out DIR     writes gun_js14.geo.json, .png and .animation.json there, for a look
                                      (build.py calls clips() and DATA for the kind `js14`)
"""
import argparse
import json
from pathlib import Path

import m1a_anim
from m1a_anim import Spec

FORWARD = 11.6          # how much further back than the M1A's this rifle's forend is under the hand


class JS14(Spec):
    name = 'js14'
    # the M1A's hold, moved back with the forend (the hand under it at z -27 instead of -38.6)
    LEFT_REST = dict(Spec.LEFT_REST, position=(5.02289, -2.60283, 4.45835 + FORWARD))
    TRAVEL = 8.5                    # the bolt stays in the short receiver
    HANDLE = (-2.75, 25.3, -4.1)    # the operating handle's knob, bolt home
    GRIP_MAG = (2.8, 14.0, -7.2)    # on the magazine's left face, below the middle (its bone's numbers)
    LUG = (0.0, 22.5, -10.6)        # its front top
    TILT = (-14, -18, -28, -2.0, 9.0, -6.0)    # its magazine is nearer the eye: raised and pushed further out to show it
    RIGHT_SHOULDER = (-24.0, 0.0, 40.0)         # its handle is near the eye and comes back towards it: the right arm comes from well
                                                # behind it, or the hand's end would face the eye
    DRAW_FROM = (4.0, 12.0, -24.4)
    OFF_FOREND = (6.0, 14.5, -17.0)
    DATA = {'phasedReloads': [
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': False}, 'duration': 1950, 'animation': 'animation.model.reload'},
        {'phase': 'RELOADING', 'condition': {'onEmptyReload': True}, 'duration': 2500, 'animation': 'animation.model.reloadempty'}],
        'viewRecoilAmplitude': 2.2}          # how hard a shot kicks the view (.223, lighter); what a shot does stays PZ's


DATA = JS14.DATA


def clips(geo):
    """Every clip of the JS-14, by its logical name, for its model `geo` (js14.geometry's)."""
    return m1a_anim.clips(geo, JS14)


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import build
    import js14
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='.')
    a = ap.parse_args()
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    geo = js14.geometry(json.loads((Path(__file__).resolve().parent / 'rifle_skeleton.json').read_text()))
    made = clips(geo)
    (out / 'gun_js14.geo.json').write_text(json.dumps(geo, separators=(',', ':')))
    (out / 'gun_js14.png').write_bytes(js14.texture())
    (out / 'gun_js14.animation.json').write_text(json.dumps({'format_version': '1.8.0', 'animations': {
        'animation.model.' + k: v for k, v in made.items()}}, separators=(',', ':')))
    for k, v in made.items():
        print(f"{k:12s} {v['animation_length']:5.2f}s  " + ' '.join(v['bones']))
