"""Point Blank-style view recoil (GunRecoil): the temporary pistol is fired once from the hip and once aimed; the look kick and
its return are sampled. Exact fixture cleanup (borrowed empty slot 8, one temporary magazine, position/look/camera/tuning).

Asserts per shot: the offset reaches about the M9 amplitude (3 deg hip, 1.5 aimed) up and a little right, Steve's real pitch
(steve.pitch, the player's look that shots use) moved by the same amount, and the offset is back near zero a second later.
"""
import json
import sys
import time
import traceback
from pathlib import Path
import runpy
import pzcore as p

HERE = Path(__file__).parent
m9 = runpy.run_path(str(HERE / 'm9-session.py'))


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def sample(seconds, step=.025):
    rows, t0 = [], time.monotonic()
    while time.monotonic() - t0 < seconds:
        s = p.state()
        f = s['minecraftGui']['gunFeel']
        rows.append({'t': round(time.monotonic() - t0, 3), 'offsetPitch': f['recoilPitch'], 'offsetYaw': f['recoilYaw'], 'active': f['recoilActive'],
                     'peakPitch': f['recoilPeakPitch'], 'peakYaw': f['recoilPeakYaw'], 'shots': f['recoilShots'],
                     'stevePitch': s['steve']['pitch'], 'steveYaw': s['steve']['yaw'], 'aim': f['aim']})
        time.sleep(step)
    return rows


def run():
    out = HERE / ('gun-recoil-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and not baseline['input']['live'] and not baseline['overrideActive']
    assert baseline['pzMirror']['status'] == 'in step'
    assert g0['inventory'][8].startswith('0 '), 'fixture hotbar slot8 occupied'
    tuning_path = Path.home() / '.pzcraft/tuning.json'
    tuning0 = tuning_path.read_bytes() if tuning_path.exists() else b'{}'
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'before': baseline, 'nativeBefore': native0, 'shots': [], 'passed': False}
    try:
        lua('_pzRecoilHeld={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);table.insert(_pzRecoilHeld,{z,z:isUseless()});z:setUseless(true) end;getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('look 0 -30')
        p._select_slot(8)
        p.cmd('camera 0')
        time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        time.sleep(1)
        lua('local p=getPlayer();local w=_pzM9Fix;assert(w);w:setContainsClip(false);w:setCurrentAmmoCount(0);w:setRoundChambered(false);'
            '_pzRecoilMag=p:getInventory():AddItem("Base.9mmClip");_pzRecoilMag:setCurrentAmmoCount(15);return _pzRecoilMag:getID()')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'fixture magazine mirrored')
        time.sleep(.5)
        p.api('POST', '/gui', {'key': 82, 'action': 1})
        p.api('POST', '/gui', {'key': 82, 'action': 0})
        p.wait_for(lambda: p.state()['pzGuns']['last'].get('status') == 'reloaded' or None, 8, .1, 'reloaded')
        time.sleep(.6)
        p.cmd('look 0 -30')
        time.sleep(.5)
        for name, aimed, low, high in [('hip', False, 2.5, 3.4), ('aimed', True, 1.2, 1.8)]:
            if aimed:
                tuning = json.loads(tuning0 or b'{}'); tuning['ads.seconds'] = .02
                tuning_path.write_text(json.dumps(tuning)); time.sleep(.6)
            before = p.state()
            shots0 = before['pzGuns']['shots']
            recoil_shots0 = before['minecraftGui']['gunFeel']['recoilShots']
            pitch0 = before['steve']['pitch']
            p.send_input(['USE', 'ATTACK'] if aimed else ['ATTACK'], 150, wait=False)
            rows = sample(1.4)
            p.send_input(release=True)
            fresh = [r for r in rows if r['shots'] > recoil_shots0]      # peaks before the new shot belong to the previous one
            assert fresh, 'the client never started a recoil for this shot'
            peak = min(r['peakPitch'] for r in fresh); peak_yaw = max(r['peakYaw'] for r in fresh)
            lowest_pitch = min(r['stevePitch'] for r in rows)
            last = rows[-1]
            record = {'name': name, 'pitchBefore': pitch0, 'peakPitch': peak, 'peakYaw': peak_yaw, 'lowestStevePitch': lowest_pitch,
                      'finalOffsetPitch': last['offsetPitch'], 'finalOffsetYaw': last['offsetYaw'], 'finalStevePitch': last['stevePitch'],
                      'shotsFired': p.state()['pzGuns']['shots'] - shots0, 'samples': rows}
            result['shots'].append(record)
            assert record['shotsFired'] == 1, record['shotsFired']
            assert low <= -peak <= high, f'{name}: peak {peak}'
            assert 0.1 < peak_yaw < 1.0, f'{name}: yaw {peak_yaw}'
            assert pitch0 - lowest_pitch >= low - .4, f'{name}: the real look did not follow ({pitch0} -> {lowest_pitch})'
            assert abs(last['offsetPitch']) < .05 and abs(last['offsetYaw']) < .05, f'{name}: offset left {last["offsetPitch"]},{last["offsetYaw"]}'
            assert abs(last['stevePitch'] - pitch0) < .1, f'{name}: look did not return ({pitch0} -> {last["stevePitch"]})'
            time.sleep(.6)
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
        clean('magazine', lambda: lua('if _pzRecoilMag then getPlayer():getInventory():Remove(_pzRecoilMag);_pzRecoilMag=nil end'))
        clean('pistol fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('position', lambda: p.cmd(f'mc tp @s {baseline["steve"]["x"]} {baseline["steve"]["y"]} {baseline["steve"]["z"]}'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 20, .2, 'owner inventory restored'))
        clean('tuning', lambda: tuning_path.write_bytes(tuning0))
        clean('pause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzRecoilHeld then for _,v in ipairs(_pzRecoilHeld) do v[1]:setUseless(v[2]) end;_pzRecoilHeld=nil end'))
        result['after'] = p.state()
        result['nativeAfter'] = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if result['nativeAfter'] != native0 or result['after']['health']['steveHealth'] != baseline['health']['steveHealth']:
            errors.append('owner baseline differs')
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
