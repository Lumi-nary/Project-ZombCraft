"""B25/B26: how soon is a freshly dug hole lit in PZ's picture?

Steve looks straight down where he stands, breaks the two blocks under the crosshair (creative: one click each), and
screenshots right after the second one and again after 0.3 s and 1.5 s, with the block-scene revision PZ reports. Run it
on open ground in daylight. The hole stays (it is two blocks of the save's Minecraft world). Screenshots go to
dig-light-<time>/.
"""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent / f'dig-light-{time.strftime("%Y%m%d-%H%M%S")}'
OUT.mkdir(exist_ok=True)


def shot(name):
    data = pz.api('GET', '/screenshot?scale=0.5', None, raw=True)
    (OUT / f'{name}.png').write_bytes(data)
    s = pz.state()
    return {'name': name, 'meshRevision': s['nativeBlocks']['meshRevision'], 'blocks': s['blocks']}


def main():
    s = pz.state()
    assert s['inGame'] and s['link']['ready'], 'not in game with Minecraft ready'
    pz.send_input(hold=['SNEAK'], ms=150, hotbar=9, look={'relative': False, 'yaw': s['steve']['yaw'], 'pitch': 90}, wait=True)
    time.sleep(0.5)
    out = [shot('before')]
    for _ in range(2):
        pz.send_input(hold=['ATTACK'], ms=60, wait=True)
        time.sleep(0.35)
    t0 = time.time()
    out.append(shot('0.0s'))
    time.sleep(max(0, 0.3 - (time.time() - t0)))
    out.append(shot('0.3s'))
    time.sleep(max(0, 1.5 - (time.time() - t0)))
    out.append(shot('1.5s'))
    (OUT / 'result.json').write_text(json.dumps(out, indent=2))
    print(json.dumps(out, indent=2))
    print('written', OUT)


if __name__ == '__main__':
    main()
