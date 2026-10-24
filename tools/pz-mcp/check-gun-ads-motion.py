"""Aimed walking motion and the off-hand rule, measured on the hand geometry Minecraft publishes (3 ms sampling, not screenshots).

  1  hip, standing / hip, walking / aimed, walking / aimed, standing: peak-to-peak of the hand geometry's centre while the pistol
     is held. Passes when aimed walking moves visibly more than aimed standing and less than (or about as much as) hip walking.
  2  off-hand flash: an item put into the off-hand (command, i.e. the server side of any route into that slot) must never add
     hand geometry; the swap key must leave the pistol in the main hand.
Fixture: temporary pistol (new PZ id, borrowed empty slot 8) and one poppy; Steve is moved to the testing site and back.
"""
import json
import runpy
import sys
import threading
import time
import traceback
from pathlib import Path
import pzcore as p

HERE = Path(__file__).parent
m9 = runpy.run_path(str(HERE / 'm9-session.py'))
native_frame = runpy.run_path(str(HERE / 'capture-weapon-view.py'))['native_frame']
SITE = (2327.5, 13, 6269.5)


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def track(seconds, action=None):
    """Samples the hand batches: (t, centre x, centre y, triangles)."""
    rows, stop = [], threading.Event()
    def sampler():
        t0 = time.monotonic()
        while not stop.is_set():
            f = native_frame()
            if f and f['batches']:
                lo = [min(b['min'][a] for b in f['batches']) for a in range(3)]
                hi = [max(b['max'][a] for b in f['batches']) for a in range(3)]
                rows.append((round(time.monotonic() - t0, 3), (lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, sum(b['triangles'] for b in f['batches'])))
            time.sleep(.003)
    th = threading.Thread(target=sampler); th.start()
    try:
        if action: action()
        time.sleep(seconds)
    finally:
        stop.set(); th.join()
    return rows


def spread(rows, skip=0.4):
    use = [r for r in rows if r[0] >= skip]
    xs, ys = [r[1] for r in use], [r[2] for r in use]
    return {'samples': len(use), 'x': round(max(xs) - min(xs), 4), 'y': round(max(ys) - min(ys), 4),
            'triangles': [min(r[3] for r in use), max(r[3] for r in use)]}


def run():
    out = HERE / ('gun-ads-motion-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and not baseline['input']['live'] and not baseline['overrideActive']
    assert baseline['pzMirror']['status'] == 'in step'
    assert g0['inventory'][8].startswith('0 '), 'fixture hotbar slot8 occupied'
    assert not any('poppy' in s for s in g0['inventory'])
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    result = {'before': baseline, 'steps': [], 'passed': False}
    def step(name, **d):
        result['steps'].append({'name': name, **d}); (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    try:
        # Zombies near the testing site killed Steve in an earlier version of this check: freeze every native zombie, again after the
        # teleport (zombies of newly loaded cells), remembering each zombie's original flag exactly once.
        lua('_pzAmHeld={};_pzAmSeen={};function _pzAmFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzAmSeen[z] then _pzAmSeen[z]=true;table.insert(_pzAmHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzAmFreeze();getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()                     # before the teleport: PZ is slow while it streams a new area
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd(f'mc tp @s {SITE[0]} {SITE[1]} {SITE[2]}')
        p.cmd('look 0 0')
        p.wait_for(lambda: not p.state()['minecraftGui']['terrain']['loading'] or None, 30, .3, 'terrain')
        for _ in range(3):
            lua('_pzAmFreeze()'); time.sleep(1.0)
        lua('local w=_pzM9Fix; w:setContainsClip(true); w:setCurrentAmmoCount(8); w:setRoundChambered(true); return 1')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(1.5)
        modes = {}
        for name, hold in [('hip standing', None), ('hip walking', ['FWD']), ('aimed walking', ['USE', 'FWD']), ('aimed standing', ['USE'])]:
            p.send_input(release=True); time.sleep(.8)
            if hold:
                p.send_input(hold, 3500, wait=False)
            time.sleep(1.2 if hold and 'USE' in hold else .6)      # aim blend (0.2 s) and walking speed settle
            start_pos = p.state()['steve']
            rows = track(1.2)
            end_pos = p.state()['steve']
            s = spread(rows)
            s['aim'] = p.state()['minecraftGui']['gunFeel']['aim']
            s['moved'] = round(((end_pos['x'] - start_pos['x']) ** 2 + (end_pos['z'] - start_pos['z']) ** 2) ** .5, 2)
            modes[name] = s
            p.send_input(release=True)
            step(name, **s)
        result['modes'] = modes
        aw, as_, hw = modes['aimed walking'], modes['aimed standing'], modes['hip walking']
        assert aw['moved'] > 1.5 and hw['moved'] > 1.5, 'Steve did not walk'
        assert aw['aim'] > .95 and as_['aim'] > .95
        move = lambda m: (m['x'] ** 2 + m['y'] ** 2) ** .5
        assert move(aw) > move(as_) * 3 + 0.01, f'aimed walking does not move: {aw} vs {as_}'
        assert move(aw) < move(hw) * 1.2, f'aimed walking moves more than hip walking: {aw} vs {hw}'

        # 2: nothing may show up in the off-hand
        p.send_input(release=True); time.sleep(.8)
        base_rows = track(1.0)
        base_max = max(r[3] for r in base_rows if r[0] > .2)
        cmd_rows = track(1.5, lambda: p.cmd('mc item replace entity @s weapon.offhand with minecraft:poppy 1'))
        tri_max = max(r[3] for r in cmd_rows)
        time.sleep(.5)
        inv = p.state()['minecraftGui']['inventory']
        step('off-hand item', baselineTriangles=base_max, triangles=tri_max, poppies=sum('poppy' in s for s in inv), cleared=p.state()['minecraftGui']['gunFeel']['offhandCleared'])
        assert tri_max <= base_max + 6, f'off-hand geometry appeared: {tri_max} > {base_max}'
        assert sum('poppy' in s for s in inv) == 1, inv
        # the swap key
        p.cmd('mc item replace entity @s hotbar.0 with minecraft:poppy 1') if False else None
        before_main = lua('return getPlayer():getPrimaryHandItem() and getPlayer():getPrimaryHandItem():getFullType() or "none"')
        p.api('POST', '/gui', {'key': 70, 'action': 1}); p.api('POST', '/gui', {'key': 70, 'action': 0})
        time.sleep(.8)
        s = p.state()['minecraftGui']
        step('swap key', held=s['held']['item'], slot=s['selectedSlot'], inventory=s['inventory'])
        assert 'pz_m9' in s['held']['item'] or 'pistol' in s['held']['item'].lower() or s['held']['item'].startswith('1 pzcraft:pz_m9'), s['held']
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e)
        result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('input', lambda: p.send_input(release=True))
        clean('poppy', lambda: p.cmd('mc clear @s minecraft:poppy'))
        clean('pistol fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('position', lambda: p.cmd(f'mc tp @s {baseline["steve"]["x"]} {baseline["steve"]["y"]} {baseline["steve"]["z"]}'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 25, .2, 'inventory restored'))
        clean('pause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzAmHeld then for _,v in ipairs(_pzAmHeld) do v[1]:setUseless(v[2]) end;_pzAmHeld=nil;_pzAmSeen=nil;_pzAmFreeze=nil end'))
        result['after'] = p.state()
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
