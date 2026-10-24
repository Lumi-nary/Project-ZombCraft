"""First- and third-person pictures of PZ guns held through the generic mesh renderer (pzgun.* / pzgun3.* in the tuning file), to
place them by looking.

  python gun-mesh-view.py Base.Pistol2 Base.Shotgun ...      (fixture gun in the borrowed empty slot 8, removed by identity)
Frames: gun-mesh-views/<stamp>/<type>-fp.png (camera 0), -tp.png (camera 1, behind), -front.png (camera 2).
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


def run(types):
    out = HERE / 'gun-mesh-views' / time.strftime('%Y%m%d-%H%M%S')
    out.mkdir(parents=True)
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and baseline['pzMirror']['status'] == 'in step' and g0['inventory'][8].startswith('0 ')
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'passed': False, 'views': []}
    try:
        lua('_pzGvHeld={};_pzGvSeen={};function _pzGvFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzGvSeen[z] then _pzGvSeen[z]=true;table.insert(_pzGvHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzGvFreeze();Events.OnTick.Add(_pzGvFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); p.cmd('look 20 0'); time.sleep(.4)
        for t in types:
            lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
            m9['start'](t)
            lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
            lua('_pzGvFreeze()'); time.sleep(1.0)
            for name, cam in (('fp', 0), ('tp', 1), ('front', 2)):
                p.cmd(f'camera {cam}'); time.sleep(1.2)
                (out / f'{t}-{name}.png').write_bytes(p.screenshot(.7))
            p.cmd('camera 0')
            result['views'].append(t)
            lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
            m9['stop']()
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e); result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('gun fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 30, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzGvFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzGvHeld then for _,v in ipairs(_pzGvHeld) do v[1]:setUseless(v[2]) end;_pzGvHeld=nil end'))
        native_after = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if native_after != native0: errors.append('owner native inventory differs')
        if p.state()['health']['steveHealth'] < float(baseline['health']['steveHealth']) - 0.01: errors.append('Steve lost health during the run: ' + str(baseline['health']['steveHealth']) + ' -> ' + str(p.state()['health']['steveHealth']))
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run(sys.argv[1:] or ['Base.Pistol2']))
