"""Bounded native reload-speed and first-person flash/casing checks; exact fixture cleanup and perk restoration."""
import concurrent.futures
import json
import runpy
import sys
import struct
import time
import traceback
from pathlib import Path
import pzcore as p

HERE = Path(__file__).parent
native_frame = runpy.run_path(str(HERE / 'capture-weapon-view.py'))['native_frame']
m9 = runpy.run_path(str(HERE / 'm9-session.py'))
def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def run():
    out = HERE / ('gun-fidelity-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and not baseline['input']['live'] and not baseline['overrideActive']
    assert baseline['pzMirror']['status'] == 'in step'
    processes = p.processes()
    assert processes['pz'] and processes['minecraft']
    assert g0['inventory'][8].startswith('0 '), 'fixture hotbar slot8 occupied'
    tuning_path = Path.home() / '.pzcraft/tuning.json'
    tuning0 = tuning_path.read_bytes()
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    perk0 = lua('local p=getPlayer();return tostring(p:getPerkInfo(Perks.Reloading))..","..p:getPerkLevel(Perks.Reloading)..","..p:getXp():getXP(Perks.Reloading)')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'before': baseline, 'perkBefore': perk0, 'nativeBefore': native0, 'reloads': [], 'shots': [], 'passed': False}
    try:
        lua('_pzCodexFidelityHeld={};local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);table.insert(_pzCodexFidelityHeld,{z,z:isUseless()});z:setUseless(true) end;getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.cmd('mc tp @s 2164 0 6126')
        p.cmd('look 0 -30')
        p._select_slot(8)
        p.cmd('camera 0')
        p.cmd(f'mc time set {(g0["server"]["timeTicks"] // 24000) * 24000 + 6000}')
        time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()
        lua('_pzCodexPerkInfo=getPlayer():getPerkInfo(Perks.Reloading);_pzCodexPerkLevel=getPlayer():getPerkLevel(Perks.Reloading);_pzCodexPerkChanged=true;UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        time.sleep(1)
        for level in (0, 5, 10):
            lua(f'local p=getPlayer();p:setPerkLevelDebug(Perks.Reloading,{level});'
                'local w=_pzM9Fix;assert(w);w:setContainsClip(false);w:setCurrentAmmoCount(0);w:setRoundChambered(false);'
                '_pzCodexReloadMag=p:getInventory():AddItem("Base.9mmClip");_pzCodexReloadMag:setCurrentAmmoCount(15);return _pzCodexReloadMag:getID()')
            p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'fixture magazine mirrored')
            time.sleep(.5)
            sequence0 = p.state()['pzGuns']['last'].get('sequence')
            t0 = time.monotonic()
            p.api('POST', '/gui', {'key': 82, 'action': 1})
            p.api('POST', '/gui', {'key': 82, 'action': 0})
            samples = []
            while time.monotonic() - t0 < 2.2:
                s = p.state()
                samples.append({'t': time.monotonic() - t0, 'native': s['pzGuns'], 'client': s['minecraftGui']['gunFeel'], 'clientGun': s['minecraftGui']['gun']})
                if samples[-1]['native']['last'].get('sequence') != sequence0 and samples[-1]['native']['last'].get('status') == 'reloaded': break
                time.sleep(.02)
            pace = .8 / (.8 + .1 * level)
            active = [row for row in samples if row['native']['last'].get('sequence') != sequence0 and row['native']['last'].get('status') == 'reload started']
            assert active, 'did not sample native reload start'
            # Native state may publish a new result a few frames before the client receives it.
            animations = [row['client']['animation'] for row in active if row['clientGun'].get('sequence') == row['native']['last'].get('sequence')
                          and 'reload' in row['client']['animation'].get('clip', '')]
            assert animations, 'did not sample GeckoLib reload'
            assert all(abs(animation['speed'] - 1 / pace) < .001 for animation in animations), animations
            assert all(abs(row['native']['last'].get('pace', 1) - pace) < .00001 for row in active)
            assert samples[-1]['native']['last']['status'] == 'reloaded', samples[-1]
            assert lua('return _pzM9Fix:getCurrentAmmoCount()..","..tostring(_pzM9Fix:isRoundChambered())') == '14,true'
            result['reloads'].append({'skill': level, 'pace': pace, 'elapsed': samples[-1]['t'], 'samples': samples})
            time.sleep(.8)
        # Restore the perk before shooting or any possible save; remove the exact temporary record if one was created.
        restore_perk()
        p.cmd('look 0 -30')
        time.sleep(.3)
        (out/'native-ammo-hud.png').write_bytes(p.screenshot(.8))
        effect_root = Path.home()/'.pzcraft/private/pointblank-26.1.2-2.2.0/m9-reference/assets/pointblank/textures'
        shell_size = struct.unpack('>2i',(effect_root/'entity/shell_casing/ar_shell.png').read_bytes()[16:24])
        tracer_size = struct.unpack('>2i',(effect_root/'effect/tracers2.png').read_bytes()[16:24])
        for name, milliseconds in [('native-50ms', 50), ('diagnostic-600ms', 600)]:
            tuning = json.loads(tuning0 or b'{}')
            tuning['flash.ms'] = milliseconds
            tuning_path.write_text(json.dumps(tuning))
            time.sleep(.6)
            shots0 = p.state()['pzGuns']['shots']
            frames = []
            def capture_geometry():
                start = time.monotonic()
                while time.monotonic() - start < .45:
                    geom = native_frame(all_batches=True)
                    if geom: frames.append({'t': time.monotonic()-start, 'native': geom})
                    time.sleep(.003)
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                future = pool.submit(capture_geometry)
                p.send_input(['ATTACK'], 150, wait=False)
                if milliseconds > 50:
                    p.wait_for(lambda: p.state()['pzGuns']['shots'] == shots0 + 1 or None, 5, .01, 'diagnostic shot starts')
                images = []
                start = time.monotonic()
                while time.monotonic() - start < .45:
                    image_name = f'{name}-{len(images):02d}.png'
                    (out / image_name).write_bytes(p.screenshot(.65))
                    images.append({'file': image_name, 't': time.monotonic()-start})
                future.result()
            p.send_input(release=True)
            p.wait_for(lambda: p.state()['pzGuns']['shots'] == shots0 + 1 or None, 5, .05, 'native fixture shot')
            flashes = [b for row in frames for b in row['native']['batches'] if b['width'] == 1800 and b['height'] == 200]
            shells = [b for row in frames for b in row['native']['batches'] if (b['width'],b['height']) == shell_size]
            tracers = [b for row in frames for b in row['native']['batches'] if (b['width'],b['height']) == tracer_size]
            result['shots'].append({'name': name, 'images': images, 'geometry': frames})
            assert flashes, f'no textured flash geometry: {name}'
            assert shells, f'no textured casing geometry: {name}'
            assert tracers, f'no textured tracer geometry: {name}'
            assert all(abs(b['uvMax'][0] - b['uvMin'][0] - 1/9) < 1e-5 and b['uvMin'][1] == 0 and b['uvMax'][1] == 1 for b in flashes)
            assert all(b['emission'][0] == 1 and b['textureReady'] for b in flashes)
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
        clean('perk', restore_perk)
        clean('reload fixture', lambda: lua('if _pzCodexReloadMag then getPlayer():getInventory():Remove(_pzCodexReloadMag);_pzCodexReloadMag=nil end'))
        clean('pistol fixture', lambda: m9['stop']() if (HERE/'m9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('position', lambda: p.cmd(f'mc tp @s {baseline["steve"]["x"]} {baseline["steve"]["y"]} {baseline["steve"]["z"]}'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('clock', lambda: p.cmd(f'mc time set {g0["server"]["timeTicks"]}'))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status']=='in step' or None, 20, .2, 'owner inventory restored'))
        clean('tuning', lambda: tuning_path.write_bytes(tuning0))
        clean('pause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss('+option+');if _pzCodexFidelityHeld then for _,v in ipairs(_pzCodexFidelityHeld) do v[1]:setUseless(v[2]) end;_pzCodexFidelityHeld=nil end'))
        result['after'] = p.state()
        result['perkAfter'] = lua('local p=getPlayer();return tostring(p:getPerkInfo(Perks.Reloading))..","..p:getPerkLevel(Perks.Reloading)..","..p:getXp():getXP(Perks.Reloading)')
        result['nativeAfter'] = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if result['nativeAfter'] != native0 or result['perkAfter'] != perk0 or result['after']['health']['steveHealth'] != baseline['health']['steveHealth']: errors.append('owner baseline differs')
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out/'result.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


def restore_perk():
    lua('if _pzCodexPerkChanged then local p=getPlayer();if _pzCodexPerkInfo then p:setPerkLevelDebug(Perks.Reloading,_pzCodexPerkLevel) else local i=p:getPerkInfo(Perks.Reloading);if i then p:getPerkList():remove(i) end end;_pzCodexPerkChanged=nil;_pzCodexPerkInfo=nil;_pzCodexPerkLevel=nil end')


if __name__ == '__main__':
    sys.exit(run())
