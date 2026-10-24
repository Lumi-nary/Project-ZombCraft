"""One steady full-size aimed frame (hold USE for a second, no shot) with the pistol, to measure where the sights sit relative to the
screen centre. Fixture: temporary pistol in the borrowed empty slot 8, removed by identity. Frames go to ads-views/<stamp>/.
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


def run():
    out = HERE / 'ads-views' / time.strftime('%Y%m%d-%H%M%S')
    out.mkdir(parents=True)
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and baseline['pzMirror']['status'] == 'in step' and g0['inventory'][8].startswith('0 ')
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'passed': False}
    try:
        lua('_pzAvHeld={};_pzAvSeen={};function _pzAvFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzAvSeen[z] then _pzAvSeen[z]=true;table.insert(_pzAvHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzAvFreeze();Events.OnTick.Add(_pzAvFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        lua('_pzAvFreeze()'); time.sleep(.6)
        lua('local w=_pzM9Fix;w:setContainsClip(true);w:setCurrentAmmoCount(14);w:setRoundChambered(true);w:setJamGunChance(0);return 1')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.8)
        p.cmd('look 20 0'); time.sleep(.6)
        (out / 'hip.png').write_bytes(p.screenshot(1.0))
        p.send_input(['USE'], 3000, wait=False); time.sleep(1.3)
        (out / 'ads.png').write_bytes(p.screenshot(1.0))
        result['gunFeel'] = p.state()['minecraftGui']['gunFeel']
        p.send_input(release=True)
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e); result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('input', lambda: p.send_input(release=True))
        clean('pistol fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 20, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzAvFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzAvHeld then for _,v in ipairs(_pzAvHeld) do v[1]:setUseless(v[2]) end;_pzAvHeld=nil end'))
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
