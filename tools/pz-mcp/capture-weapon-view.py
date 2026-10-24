"""Daylight, in-place swing pictures plus read-only native geometry diagnostics.

No teleport or desktop input. Borrows an empty hotbar slot for a PZ weapon,
restores inventory, selection, camera, look, time and pause-on-focus-loss.
Usage: python capture-weapon-view.py [Base.Katana|minecraft:iron_sword] [0|1|2] [draw|fire|reload]
"""
import json
import mmap
import os
import struct
import sys
import time
from pathlib import Path
import pzcore as p

def native_frame(all_batches=False):
    candidates = [Path(os.environ['TEMP']) / f'PzCraft_entities_v{v}.shm' for v in (3,4,5,6,7)]
    path = max((c for c in candidates if c.exists()), key=lambda c: c.stat().st_mtime)
    with path.open('rb') as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as m:
        version, = struct.unpack_from('<i', m, 4)
        frame = 4096 + ({3:24, 4:64, 5:128, 6:256, 7:512}[version]) * 1024 * 1024
        vertices = frame + 192 + 256 * 16
        for _ in range(20):
            seq = struct.unpack_from('<i', m, frame)[0]
            if not seq or seq & 1:
                continue
            rev, = struct.unpack_from('<q', m, frame+8)
            origin = struct.unpack_from('<3i', m, frame+16)
            nb, nv = struct.unpack_from('<2i', m, frame+28)
            if not 0 <= nb <= 256 or not 0 <= nv <= 196608:
                continue
            header = bytes(m[frame+192:frame+192+nb*16])
            data = bytes(m[vertices:vertices+nv*52])
            if struct.unpack_from('<i', m, frame)[0] != seq:
                continue
            out = {'version': version, 'revision': rev, 'origin': origin, 'vertices': nv, 'batches': []}
            for i in range(nb):
                tex, first, count, flags = struct.unpack_from('<4i', header, i*16)
                if not all_batches and not flags & (8 | 64):
                    continue
                rows = [struct.unpack_from('<13f', data, v*52) for v in range(first, first+count)]
                kind, = struct.unpack_from('<i', m, 64+tex*64+28)
                texture_sequence, = struct.unpack_from('<i', m, 64+tex*64)
                texture_revision, = struct.unpack_from('<q', m, 64+tex*64+8)
                width, height = struct.unpack_from('<2i', m, 64+tex*64+16)
                out['batches'].append({'texture': tex, 'kind': kind, 'textureReady': texture_sequence > 0 and not texture_sequence & 1,
                    'textureRevision': texture_revision, 'width': width, 'height': height, 'flags': flags, 'triangles': count//3,
                    'min': [min(r[a] for r in rows) for a in range(3)],
                    'max': [max(r[a] for r in rows) for a in range(3)],
                    'uvMin': [min(r[a] for r in rows) for a in (6,7)],
                    'uvMax': [max(r[a] for r in rows) for a in (6,7)],
                    'alpha': [min(r[11] for r in rows), max(r[11] for r in rows)],
                    'emission': [min(r[12] for r in rows), max(r[12] for r in rows)]})
            return out
    return None


def run(weapon, camera, clip=None):
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = Path(__file__).parent / 'held-item-shots' / f'codex-{weapon.replace(":", "-")}-cam{camera}{"-"+clip if clip else ""}-{stamp}'
    out.mkdir(parents=True)
    s0 = p.state()
    assert s0['paused'] and not s0['input']['live'] and not s0['overrideActive'], 'manual session active'
    assert s0['pzMirror']['status'] == 'in step'
    g0 = s0['minecraftGui']
    inv0 = g0['inventory']
    option = str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    ticks0 = g0['server']['timeTicks']
    given_at = None
    result = {'weapon': weapon, 'camera': camera, 'clip': clip, 'baseline': s0, 'frames': []}
    try:
        # Visual-only clips never shoot; freeze native actors briefly and restore their exact flags.
        # Real melee captures still require an empty vicinity to protect the owner's actors.
        if not clip:
            assert s0['zombies'] == 0, 'native actor near a melee capture'
        result['frozen'] = p.lua('_pzVisualFrozen={};local a=getCell():getZombieList();'
                               'for i=0,a:size()-1 do local z=a:get(i);'
                               '_pzVisualFrozen[z]=z:isUseless();z:setUseless(true);end;return a:size()')['results'][0]
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd(f'mc time set {(ticks0//24000)*24000+6000}')
        p.cmd(f'camera {camera}')
        if weapon.startswith('minecraft:'):
            slot = next(i for i in range(9) if inv0[i].endswith(' '+weapon))
        else:
            slot = next(i for i in range(9) if inv0[i].startswith('0 '))
            p.cmd(f'mc pzgive {weapon} 1')
            p.wait_for(lambda: p.state()['minecraftGui']['inventory'] != inv0 or None, 8, .3, weapon)
            inv = p.state()['minecraftGui']['inventory']
            at = next(i for i in range(36) if inv[i] != inv0[i])
            assert inv0[at].startswith('0 ')
            given_at = at
            if at != slot:
                p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}')
                p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
                given_at = slot
        p._select_slot(slot)
        time.sleep(3)
        log = Path(p.LOGS['minecraft'])
        mark = log.stat().st_size
        def miss():
            with log.open('rb') as f:
                f.seek(mark)
                lines=f.read().decode(errors='replace').splitlines()
            cross=[l for l in lines if 'crosshair:' in l]
            return bool(cross and 'crosshair: MISS ' in cross[-1])
        p.wait_for(lambda: miss() or None, 8, .3, 'air crosshair (protect blocks)')
        (out / 'idle.png').write_bytes(p.screenshot(.75))
        result['idleNative'] = native_frame()
        result['held'] = p.state()['minecraftGui']['held']
        t0 = time.monotonic()
        if clip:
            assert weapon == 'Base.Pistol' and clip in ('draw','fire','reload')
            p.cmd(f'mc pzgunanim {clip}')
        else:
            p.send_input(['ATTACK'], 150, wait=False)
        while time.monotonic()-t0 < (2.7 if clip=='reload' else 1.8):
            dt = time.monotonic()-t0
            geom = native_frame()
            name = f'{len(result["frames"]):02d}-{int(dt*1000):04d}ms.png'
            (out / name).write_bytes(p.screenshot(.75))
            result['frames'].append({'t': dt, 'file': name, 'native': geom})
        time.sleep(.5)
        (out / 'recovered.png').write_bytes(p.screenshot(.75))
    except Exception as e:
        result['error'] = repr(e)
    finally:
        errors = []
        for cleanup in [lambda: p.send_input(release=True),
                        lambda: p.cmd(f'mc item replace entity @s container.{given_at} with minecraft:air') if given_at is not None else None,
                        lambda: p._select_slot(g0['selectedSlot']),
                        lambda: p.cmd(f'camera {g0["cameraMode"]}'),
                        lambda: p.cmd(f'look {s0["steve"]["yaw"]} {s0["steve"]["pitch"]}'),
                        lambda: p.send_input([],100,wait=True),
                        lambda: p.cmd('inputrelease'),
                        lambda: p.cmd(f'mc time set {ticks0}'),
                        lambda: p.wait_for(lambda: (p.state()['minecraftGui']['inventory'] == inv0 and
                                                    p.state()['pzMirror']['status'] == 'in step') or None, 20, .4, 'inventory restored'),
                        lambda: p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss('+option+')')]:
            try:
                cleanup()
            except Exception as e:
                errors.append(repr(e))
        try:
            p.lua('if _pzVisualFrozen then for z,u in pairs(_pzVisualFrozen) do z:setUseless(u) end;_pzVisualFrozen=nil end')
        except Exception as e:
            errors.append(repr(e))
        result['cleanupErrors'] = errors
        result['after'] = p.state()
        (out / 'result.json').write_text(json.dumps(result, indent=2))
        print(json.dumps({'folder': str(out), 'error': result.get('error'), 'cleanupErrors': errors,
                          'frames': len(result['frames']), 'inventoryRestored': result['after']['minecraftGui']['inventory'] == inv0,
                          'paused': result['after']['paused']}), flush=True)
    raise SystemExit(1 if result.get('error') or errors else 0)


if __name__ == '__main__':
    run(sys.argv[1] if len(sys.argv)>1 else 'Base.Katana', int(sys.argv[2]) if len(sys.argv)>2 else 0,
        sys.argv[3] if len(sys.argv)>3 else None)
