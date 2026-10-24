"""One swing where Steve stands, captured as frames: python swing-here.py [weapon] [camera 0|1|2]

Gentler than swing-frames.py: Steve is not moved. A PZ weapon is put into a free hotbar slot (the selected one if it is empty),
Minecraft's own crosshair line must be MISS (an attack on a block would mine it), one 150 ms attack tap is sent and the screen
is captured as fast as the control API allows into held-item-shots/<weapon>-cam<N>-<stamp>/. The camera mode is switched with
the dev command `camera N` (no desktop key) and restored. The weapon, the selected slot and the camera are put back.
Needs the world running, Steve driving and the mirror in step. Private pictures: never commit or upload.
"""
import sys
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent / 'held-item-shots'


def gui():
    return p.state()['minecraftGui']


def run(weapon='Base.Katana', camera=0):
    OUT.mkdir(exist_ok=True)
    folder = OUT / f'{weapon.replace("Base.", "")}-cam{camera}-{time.strftime("%H%M%S")}'
    folder.mkdir()
    log = Path(p.LOGS['minecraft'])
    g0 = gui()
    previous_slot, previous_camera, inv0 = g0['selectedSlot'], g0['cameraMode'], list(g0['inventory'])
    slot = previous_slot if inv0[previous_slot].startswith('0 ') else next((i for i in range(9) if inv0[i].startswith('0 ')), None)
    assert slot is not None, 'no free hotbar slot'
    given = False
    try:
        if camera != previous_camera:
            p.cmd(f'camera {camera}')
            p.wait_for(lambda: gui()['cameraMode'] == camera or None, 8, 0.3, 'the camera mode')
        before = list(gui()['inventory'])
        p.cmd(f'mc pzgive {weapon} 1')
        p.wait_for(lambda: (list(gui()['inventory']) != before) or None, 8, 0.3, weapon)
        at = next(i for i in range(36) if gui()['inventory'][i] != before[i])
        given = True
        if at != slot:
            p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}')
            p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
        if slot != previous_slot:
            p._select_slot(slot)
        time.sleep(2.5)         # the cooldown recharges, the pose settles
        t0 = time.time()
        seen = None
        while time.time() - t0 < 7 and seen is None:
            time.sleep(0.5)
            for line in reversed(log.read_text(encoding='utf-8', errors='replace').splitlines()[-40:]):
                if 'crosshair:' in line and line.split(']')[0] > time.strftime('[%H:%M:%S', time.localtime(t0 - 1)):
                    seen = line.split('crosshair: ')[1].split(' at ')[0]
                    break
        print('crosshair', seen)
        if seen != 'MISS':
            print('not swinging: the crosshair is not on air')
            return
        frames = []
        t0 = time.time()
        p.send_input(['ATTACK'], 150, wait=False)
        while time.time() - t0 < 1.5:
            frames.append((time.time() - t0, p.screenshot(0.4)))
        for n, (dt, png) in enumerate(frames):
            (folder / f'{n:02d}-{int(dt * 1000):04d}ms.png').write_bytes(png)
        print(len(frames), 'frames in', folder)
    finally:
        try:
            p.send_input(release=True)
        except Exception:
            pass
        if given:
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
        time.sleep(1.0)
        if gui()['selectedSlot'] != previous_slot:
            p._select_slot(previous_slot)
        if gui()['cameraMode'] != previous_camera:
            p.cmd(f'camera {previous_camera}')
        time.sleep(0.5)
        s = p.state()
        print('slot', s['minecraftGui']['selectedSlot'], '| camera', s['minecraftGui']['cameraMode'], '| inventory as before:',
              s['minecraftGui']['inventory'] == inv0, '| mirror', s['pzMirror']['status'])


if __name__ == '__main__':
    run(sys.argv[1] if len(sys.argv) > 1 else 'Base.Katana', int(sys.argv[2]) if len(sys.argv) > 2 else 0)
