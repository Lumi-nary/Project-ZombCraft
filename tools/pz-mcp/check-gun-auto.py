"""Select fire and full auto on the M16 (Base.AssaultRifle): B cycles Single / Auto, a held attack key keeps firing in Auto and fires
exactly one shot in Single. Fixture: temporary gun in the borrowed empty slot 8 (loaded by field), removed by identity.
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
GUN = 'Base.AssaultRifle'


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def mode():
    return lua('return tostring(_pzM9Fix:getFireMode())')


def hud_mode():
    s = p.state()['minecraftGui']
    return s['held']['item']


def key(code):
    p.api('POST', '/gui', {'key': code, 'action': 1})
    p.api('POST', '/gui', {'key': code, 'action': 0})


def run():
    out = HERE / ('gun-auto-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and baseline['pzMirror']['status'] == 'in step' and g0['inventory'][8].startswith('0 ')
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'passed': False, 'steps': []}
    def step(name, **d):
        result['steps'].append({'name': name, **d}); (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    try:
        lua('_pzGaHeld={};_pzGaSeen={};function _pzGaFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzGaSeen[z] then _pzGaSeen[z]=true;table.insert(_pzGaHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzGaFreeze();Events.OnTick.Add(_pzGaFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); p.cmd('look 0 -40'); time.sleep(.4)      # at the ground in front: no zombie, no sky shots needed
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start'](GUN)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        for _ in range(2):
            lua('_pzGaFreeze()'); time.sleep(.4)
        lua('local w=_pzM9Fix;w:setJamGunChance(0);w:setContainsClip(true);w:setCurrentAmmoCount(29);w:setRoundChambered(true);return 1')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.6)
        m0 = mode()
        step('start', mode=m0)
        # B cycles the mode and the stack reports it
        key(66)
        p.wait_for(lambda: mode() != m0 or None, 6, .1, 'mode changed')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.5)
        step('B pressed', mode=mode(), last=p.state()['pzGuns']['last'].get('status'))
        if mode() != 'Auto':
            key(66); p.wait_for(lambda: mode() == 'Auto' or None, 6, .1, 'Auto'); time.sleep(.6)
        time.sleep(.6)
        # Auto: hold the attack key
        n0 = p.state()['pzGuns']['shots']
        p.send_input(['ATTACK'], 1300, wait=True)
        time.sleep(.5)
        auto_shots = p.state()['pzGuns']['shots'] - n0
        step('held in Auto', shots=auto_shots)
        assert auto_shots >= 7, auto_shots
        # Single: the same hold fires one shot
        key(66); p.wait_for(lambda: mode() == 'Single' or None, 6, .1, 'Single')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.8)
        n0 = p.state()['pzGuns']['shots']
        p.send_input(['ATTACK'], 1300, wait=True)
        time.sleep(.5)
        single_shots = p.state()['pzGuns']['shots'] - n0
        step('held in Single', shots=single_shots, mode=mode())
        assert single_shots == 1, single_shots
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e); result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('input', lambda: p.send_input(release=True))
        clean('mode', lambda: lua('if _pzM9Fix then _pzM9Fix:setFireMode("Auto") end;return 1'))   # the script's default for the M16 (catalogue: Auto)
        clean('gun fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 30, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzGaFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzGaHeld then for _,v in ipairs(_pzGaHeld) do v[1]:setUseless(v[2]) end;_pzGaHeld=nil end'))
        native_after = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if native_after != native0: errors.append('owner native inventory differs')
        if p.state()['health']['steveHealth'] < float(baseline['health']['steveHealth']) - 0.01: errors.append('Steve lost health during the run: ' + str(baseline['health']['steveHealth']) + ' -> ' + str(p.state()['health']['steveHealth']))
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
