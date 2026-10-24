"""B20: in creative, does a gun keep its rounds? Fires the held gun N times (default 5) and reads PZ's count after each shot.

Needs: PZ in game with Steve in 3D, Steve in creative, a loaded gun in the selected hotbar slot. Prints per shot the gun's
PZ id, rounds in the magazine and whether a round is chambered, plus PZ's shot counter. Nothing else is changed.
"""
import json
import sys
import time
import pzcore as pz

GUN = """
local item = getPlayer():getInventory():getItemWithID(%d)
if not item then return "gone" end
return string.format("%%d+%%s", item:getCurrentAmmoCount(), tostring(item:isRoundChambered()))
"""


def lua(code):
    r = pz.lua(code)
    if not r.get('ok'):
        raise RuntimeError(r)
    return r['results'][0] if r.get('results') else ''


def main():
    shots = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    s = pz.state()
    held = s['minecraftGui']['held']
    gun = held.get('pzId', -1)
    assert gun and gun > 0, f'no PZ item in hand: {held}'
    print('creative', s['minecraftGui']['server'].get('creative'), 'flags', s['steve']['flags'], 'gun', held['item'], gun, flush=True)
    print('before', lua(GUN % gun), 'shots', s['pzGuns']['shots'], flush=True)
    for i in range(shots):
        pz.send_input(hold=["ATTACK"], ms=60, wait=True)
        time.sleep(0.6)
        s = pz.state()
        print(f'shot {i + 1}:', lua(GUN % gun), 'shots', s['pzGuns']['shots'], 'dry', s['pzGuns']['dry'],
              json.dumps(s['pzGuns'].get('last', {}))[:160], flush=True)


if __name__ == '__main__':
    main()
