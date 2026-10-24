"""Where a bullet tracer appears relative to the gun and the crosshair, hip and aimed. The tracer is slowed (tracer.life / tracer.speed
in the tuning file) so screenshots can catch it; the tuning file is restored byte for byte. Fixture: temporary pistol (borrowed empty
hotbar slot 8), removed by identity. Frames go to tracer-views/<stamp>/.
"""
import json
import runpy
import sys
import time
import traceback
from pathlib import Path
import pzcore as p

HERE = Path(__file__).parent
m9 = runpy.run_path(str(HERE / 'm9-session.py'))


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def run(extra=None):
    out = HERE / 'tracer-views' / time.strftime('%Y%m%d-%H%M%S')
    out.mkdir(parents=True)
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and baseline['pzMirror']['status'] == 'in step' and g0['inventory'][8].startswith('0 ')
    tuning_path = Path.home() / '.pzcraft/tuning.json'
    tuning0 = tuning_path.read_bytes() if tuning_path.exists() else b'{}'
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'passed': False, 'shots': []}
    try:
        lua('_pzTvHeld={};_pzTvSeen={};function _pzTvFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzTvSeen[z] then _pzTvSeen[z]=true;table.insert(_pzTvHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzTvFreeze();Events.OnTick.Add(_pzTvFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        lua('_pzTvFreeze()'); time.sleep(.6)
        lua('local w=_pzM9Fix;w:setContainsClip(true);w:setCurrentAmmoCount(14);w:setRoundChambered(true);w:setJamGunChance(0);return 1')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.8)
        tuning = json.loads(tuning0 or b'{}')
        tuning.update({'tracer.life': 1.2, 'tracer.speed': 25})
        tuning.update(extra or {})
        tuning_path.write_text(json.dumps(tuning)); time.sleep(.8)
        p.cmd('look 20 -4'); time.sleep(.6)       # off to the side and a little up: the whole streak is on screen
        for name, aimed in (('hip', False), ('aimed', True)):
            if aimed:
                p.send_input(['USE'], 3500, wait=False); time.sleep(.9)
            p.send_input(['USE', 'ATTACK'] if aimed else ['ATTACK'], 150, wait=False)
            t0 = time.time()
            for n in range(7):
                (out / f'{name}-{n}-{int((time.time() - t0) * 1000):04d}ms.png').write_bytes(p.screenshot(.6))
            p.send_input(release=True); time.sleep(1.5)
            result['shots'].append({'name': name, 'lastShot': p.state()['minecraftGui']['gunFeel'].get('lastShot')})
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e); result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('input', lambda: p.send_input(release=True))
        clean('tuning', lambda: tuning_path.write_bytes(tuning0))
        clean('pistol fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 20, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzTvFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzTvHeld then for _,v in ipairs(_pzTvHeld) do v[1]:setUseless(v[2]) end;_pzTvHeld=nil end'))
        nativeAfter = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if nativeAfter != native0: errors.append('owner native inventory differs')
        if p.state()['health']['steveHealth'] < float(baseline['health']['steveHealth']) - 0.01: errors.append('Steve lost health during the run: ' + str(baseline['health']['steveHealth']) + ' -> ' + str(p.state()['health']['steveHealth']))
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
