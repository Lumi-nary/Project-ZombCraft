"""Pistol magazine ejection, the empty-slide pose and the empty off-hand rule, with a temporary pistol (new PZ id, borrowed empty
slot 8) and temporary magazines (identified by new ids, removed by id). Checks:
  A  shift+R with a loaded magazine in the gun: the magazine returns to the inventory with its 5 rounds, the chambered round stays,
     a second shift+R says there is no magazine.
  B  the last chambered round fired with an empty magazine in the gun: the empty magazine is ejected into the inventory by itself,
     the gun is empty and the client plays the slide-back idle (idleempty) once the fire clip is over; screenshot kept.
  C  a dry click with an empty magazine still in the gun ejects it too.
  D  an item put in the off-hand while the pistol is in the main hand goes back to the inventory.
  E  frames around a normal shot (kept for a look at how the gun moves).
Cleanup removes exactly the fixture items; the whole inventory is compared with the start.
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
MAG = 'Base.9mmClip'


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def mag_ids():
    return lua('local o={}; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); '
               f'if m:getFullType()=="{MAG}" then o[#o+1]=tostring(m:getID())..":"..m:getCurrentAmmoCount() end end; return table.concat(o,",")')


def new_mags(known):
    now = [x for x in (mag_ids() or '').split(',') if x]
    k = {x.split(':')[0] for x in known}
    return [x for x in now if x.split(':')[0] not in k]


def gun_state():
    return lua('local w=_pzM9Fix; return tostring(w:isContainsClip())..","..w:getCurrentAmmoCount()..","..tostring(w:isRoundChambered())')


def set_gun(clip, ammo, chamber):
    lua(f'local w=_pzM9Fix; w:setContainsClip({str(clip).lower()}); w:setCurrentAmmoCount({ammo}); w:setRoundChambered({str(chamber).lower()}); return 1')
    p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror')
    time.sleep(1.2)


def run():
    out = HERE / ('gun-eject-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and not baseline['input']['live'] and not baseline['overrideActive']
    assert baseline['pzMirror']['status'] == 'in step'
    assert g0['inventory'][8].startswith('0 '), 'fixture hotbar slot8 occupied'
    assert not any('poppy' in s for s in g0['inventory']), 'the off-hand fixture item is already carried'
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    known = [x for x in (mag_ids() or '').split(',') if x]
    result = {'before': baseline, 'nativeBefore': native0, 'steps': [], 'passed': False}
    def step(name, **d):
        result['steps'].append({'name': name, **d}); (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    try:
        lua('_pzEjHeld={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);table.insert(_pzEjHeld,{z,z:isUseless()});z:setUseless(true) end;getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('look 0 -5')
        p._select_slot(8)
        p.cmd('camera 0')
        time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        time.sleep(1)

        # A: shift+R takes the magazine out
        set_gun(True, 5, True)
        before = p.state()['pzGuns']
        p.send_input(['SNEAK'], 1500, wait=False)
        time.sleep(.25)
        p.api('POST', '/gui', {'key': 82, 'action': 1}); p.api('POST', '/gui', {'key': 82, 'action': 0})
        p.wait_for(lambda: p.state()['pzGuns']['last'].get('status') == 'magazine ejected' or None, 6, .1, 'magazine ejected')
        last = p.state()['pzGuns']['last']
        p.send_input(release=True)
        assert gun_state() == 'false,0,true', gun_state()
        mags = new_mags(known)
        assert [m.split(':')[1] for m in mags] == ['5'], mags
        assert last.get('ejected') is True, last
        step('A shift+R', gun=gun_state(), magazines=mags, last=last)
        p.send_input(['SNEAK'], 1500, wait=False); time.sleep(.25)
        p.api('POST', '/gui', {'key': 82, 'action': 1}); p.api('POST', '/gui', {'key': 82, 'action': 0})
        p.wait_for(lambda: p.state()['pzGuns']['last'].get('status') == 'no magazine' or None, 6, .1, 'no magazine message')
        p.send_input(release=True)
        step('A second shift+R', last=p.state()['pzGuns']['last'])

        # B: the last shot with an empty magazine in the gun
        known_b = known + new_mags(known)
        set_gun(True, 0, True)
        shots0 = p.state()['pzGuns']['shots']
        p.send_input(['ATTACK'], 150, wait=False)
        p.wait_for(lambda: p.state()['pzGuns']['shots'] == shots0 + 1 or None, 6, .05, 'last shot')
        p.send_input(release=True)
        last = p.state()['pzGuns']['last']
        assert gun_state() == 'false,0,false', gun_state()
        mags = new_mags(known_b)
        assert [m.split(':')[1] for m in mags] == ['0'], mags
        assert last.get('status') == 'fired' and last.get('ejected') is True, last
        time.sleep(1.2)
        (out / 'empty-slide-open.png').write_bytes(p.screenshot(.8))
        clip = p.state()['minecraftGui']['gunFeel']['animation'].get('clip')
        step('B last shot', gun=gun_state(), magazines=mags, last=last, clip=clip)
        assert clip == 'animation.model.idleempty', clip

        # C: dry click with an empty magazine still inserted
        known_c = known_b + mags
        set_gun(True, 0, False)
        dry0 = p.state()['pzGuns']['dry']
        p.send_input(['ATTACK'], 150, wait=False)
        p.wait_for(lambda: p.state()['pzGuns']['dry'] == dry0 + 1 or None, 6, .05, 'dry click')
        p.send_input(release=True)
        time.sleep(.3)
        last = p.state()['pzGuns']['last']
        assert gun_state() == 'false,0,false', gun_state()
        mags = new_mags(known_c)
        assert [m.split(':')[1] for m in mags] == ['0'], mags
        assert last.get('status') == 'empty' and last.get('ejected') is True, last
        step('C dry click', gun=gun_state(), magazines=mags, last=last)

        # D: nothing may be held in the off-hand with the pistol in the main hand
        known_d = known_c + mags
        cleared0 = p.state()['minecraftGui']['gunFeel']['offhandCleared']
        p.cmd('mc item replace entity @s weapon.offhand with minecraft:poppy 1')
        p.wait_for(lambda: p.state()['minecraftGui']['gunFeel']['offhandCleared'] == cleared0 + 1 or None, 6, .1, 'off-hand cleared')
        time.sleep(.5)
        inv = p.state()['minecraftGui']['inventory']
        assert sum('poppy' in s for s in inv) == 1, inv
        step('D off-hand', inventory=inv)

        # E: frames around a normal shot
        set_gun(True, 5, True)
        frames = []
        (out / 'shot-before.png').write_bytes(p.screenshot(.6))
        shots0 = p.state()['pzGuns']['shots']
        p.send_input(['ATTACK'], 150, wait=False)
        t0 = time.monotonic()
        while time.monotonic() - t0 < .7:
            name = f'shot-{len(frames):02d}.png'
            (out / name).write_bytes(p.screenshot(.6)); frames.append({'file': name, 't': round(time.monotonic() - t0, 3)})
        p.send_input(release=True)
        assert p.state()['pzGuns']['shots'] == shots0 + 1
        step('E shot frames', frames=frames)
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
        clean('magazines', lambda: lua('local keep={}; for id in string.gmatch("' + ','.join(x.split(':')[0] for x in known) + '", "[^,]+") do keep[id]=true end; '
              'local it=getPlayer():getInventory():getItems(); local rm={}; for i=0,it:size()-1 do local m=it:get(i); '
              f'if m:getFullType()=="{MAG}" and not keep[tostring(m:getID())] then rm[#rm+1]=m end end; for _,m in ipairs(rm) do getPlayer():getInventory():Remove(m) end; return #rm'))
        clean('pistol fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('position', lambda: p.cmd(f'mc tp @s {baseline["steve"]["x"]} {baseline["steve"]["y"]} {baseline["steve"]["z"]}'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 25, .2, 'inventory restored'))
        clean('pause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzEjHeld then for _,v in ipairs(_pzEjHeld) do v[1]:setUseless(v[2]) end;_pzEjHeld=nil end'))
        result['after'] = p.state()
        result['nativeAfter'] = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if result['nativeAfter'] != native0: errors.append('native inventory differs')
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
