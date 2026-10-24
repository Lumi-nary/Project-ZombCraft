"""A first-person tuning session with a temporary pistol (identified by its new id; nothing else is touched).

  python m9-session.py start [ITEM]   give the item (default Base.Pistol) into the (empty) selected slot, keep the world paused
  python m9-session.py shot NAME      screenshot into m9-views/NAME.png
  python m9-session.py play CLIP [NAME]  unpause (zombies frozen), trigger the clip with the debug command, capture ~16 frames, pause
  python m9-session.py stop           remove exactly that pistol, restore camera
State is kept in m9-session.json so a session survives between commands. Values being tuned: ~/.pzcraft/tuning.json.
"""
import json, sys, time
from pathlib import Path
import pzcore as p

HERE = Path(__file__).parent
SESSION = HERE / 'm9-session.json'
OUT = HERE / 'm9-views'
gui = lambda: p.state()['minecraftGui']


def start(item='Base.Pistol'):
    assert not SESSION.exists(), 'a session is already open: run stop first'
    s0 = p.state(); g = gui()
    assert s0['paused'] and s0['pzMirror']['status'] == 'in step'
    inv0 = list(g['inventory']); slot = g['selectedSlot']
    assert inv0[slot].startswith('0 '), 'the selected slot must be empty'
    known = p.lua('local o={}; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); if m:getFullType()=="' + item + '" then o[#o+1]=tostring(m:getID()) end end; return table.concat(o,",")')['results'][0]
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    SESSION.write_text(json.dumps({'item': item, 'inv0': inv0, 'slot': slot, 'camera': g['cameraMode'], 'known': known, 'option': option}))
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); _pzM9Held={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_pzM9Held,{z,z:isUseless()}); z:setUseless(true) end; UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        time.sleep(1.0)
        p.cmd(f'mc pzgive {item} 1')
        def landed():
            now = gui()['inventory']; return next((i for i in range(36) if now[i] != inv0[i] and item in now[i]), None)
        at = p.wait_for(lambda: landed(), 10, .3, 'pistol')
        if at != slot:
            p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}'); p.cmd(f'mc item replace entity @s container.{at} with minecraft:air'); time.sleep(.6)
        p.wait_for(lambda: ' whole' in gui()['inventory'][slot] or None, 20, .3, 'whole pistol')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' and p.state()['pzMirror']['pending'] == 0 or None, 30, .4, 'mirror')
        p.lua('local known={}; for id in string.gmatch("' + known + '", "[^,]+") do known[id]=true end; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); if m:getFullType()=="' + item + '" and not known[tostring(m:getID())] then _pzM9Fix=m end end; assert(_pzM9Fix); return 1')
        time.sleep(2.5)
    finally:
        p.lua('if _pzM9Held then for _,v in ipairs(_pzM9Held) do v[1]:setUseless(v[2]) end; _pzM9Held=nil end; UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    print('session open: pistol in slot', slot, '| world paused:', p.state()['paused'])


def shot(name):
    OUT.mkdir(exist_ok=True)
    (OUT / f'{name}.png').write_bytes(p.screenshot(0.7)); print('saved', OUT / f'{name}.png')


def play(clip, name=None):
    OUT.mkdir(exist_ok=True)
    name = name or clip
    folder = OUT / f'{name}-{time.strftime("%H%M%S")}'; folder.mkdir()
    p.lua('_pzM9Held2={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_pzM9Held2,{z,z:isUseless()}); z:setUseless(true) end; UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    try:
        time.sleep(1.2)
        t0 = time.time(); p.cmd(f'mc pzgunanim {clip}')
        frames = []
        while time.time() - t0 < 2.6: frames.append((time.time() - t0, p.screenshot(0.4)))
        for n, (dt, png) in enumerate(frames): (folder / f'{n:02d}-{int(dt * 1000):04d}ms.png').write_bytes(png)
        print(len(frames), 'frames in', folder)
    finally:
        p.lua('if _pzM9Held2 then for _,v in ipairs(_pzM9Held2) do v[1]:setUseless(v[2]) end; _pzM9Held2=nil end; UIManager.getSpeedControls():SetCurrentGameSpeed(0)')


def swing(name='swing'):
    """One attack tap (crosshair must be on air: an attack on a block would mine it) with a frame burst."""
    OUT.mkdir(exist_ok=True)
    folder = OUT / f'{name}-{time.strftime("%H%M%S")}'; folder.mkdir()
    log = Path(p.LOGS['minecraft'])
    p.lua('_pzM9Held2={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_pzM9Held2,{z,z:isUseless()}); z:setUseless(true) end; UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    try:
        time.sleep(2.5)       # cooldown recharged, pose settled
        t0 = time.time(); seen = None
        while time.time() - t0 < 7 and seen is None:
            time.sleep(0.5)
            for line in reversed(log.read_text(encoding='utf-8', errors='replace').splitlines()[-40:]):
                if 'crosshair:' in line and line.split(']')[0] > time.strftime('[%H:%M:%S', time.localtime(t0 - 1)):
                    seen = line.split('crosshair: ')[1].split(' at ')[0]; break
        if seen != 'MISS':
            print('not swinging: the crosshair is on', seen); return
        frames = []; t0 = time.time()
        p.send_input(['ATTACK'], 150, wait=False)
        while time.time() - t0 < 1.5: frames.append((time.time() - t0, p.screenshot(0.4)))
        for n, (dt, png) in enumerate(frames): (folder / f'{n:02d}-{int(dt * 1000):04d}ms.png').write_bytes(png)
        print(len(frames), 'frames in', folder)
    finally:
        try: p.send_input(release=True)
        except Exception: pass
        p.lua('if _pzM9Held2 then for _,v in ipairs(_pzM9Held2) do v[1]:setUseless(v[2]) end; _pzM9Held2=nil end; UIManager.getSpeedControls():SetCurrentGameSpeed(0)')


def _frozen(fn):
    """Run fn with the world running and every zombie frozen; restores their flags and the pause afterwards."""
    p.lua('_pzM9Held2={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_pzM9Held2,{z,z:isUseless()}); z:setUseless(true) end; UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    try:
        time.sleep(1.0)
        return fn()
    finally:
        try: p.send_input(release=True)
        except Exception: pass
        p.lua('if _pzM9Held2 then for _,v in ipairs(_pzM9Held2) do v[1]:setUseless(v[2]) end; _pzM9Held2=nil end; UIManager.getSpeedControls():SetCurrentGameSpeed(0)')


def _burst(name, seconds, hold=None, ms=0, look=None, every=0.0):
    OUT.mkdir(exist_ok=True)
    folder = OUT / f'{name}-{time.strftime("%H%M%S")}'; folder.mkdir()
    def go():
        if look: p.send_input(look=look, wait=True); time.sleep(0.5)
        frames = []; t0 = time.time()
        if hold: p.send_input(hold, ms or int(seconds * 1000), wait=False)
        while time.time() - t0 < seconds: frames.append((time.time() - t0, p.screenshot(0.4)))
        for n, (dt, png) in enumerate(frames): (folder / f'{n:02d}-{int(dt * 1000):04d}ms.png').write_bytes(png)
        print(len(frames), 'frames in', folder)
    _frozen(go)


def arm():
    """Load the fixture pistol directly (14 rounds + one chambered): only the fixture item changes."""
    p.lua('assert(_pzM9Fix, "no fixture pistol"); local w=_pzM9Fix; w:setContainsClip(true); w:setCurrentAmmoCount(14); w:setRoundChambered(true); w:setJamGunChance(0); return 1')
    time.sleep(2.0)
    print('fixture pistol loaded:', gui()['inventory'][json.loads(SESSION.read_text())['slot']])


def stop():
    ses = json.loads(SESSION.read_text())
    errors = []
    try:
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'); time.sleep(.8)
        p.lua('if _pzM9Fix then getPlayer():getInventory():Remove(_pzM9Fix); _pzM9Fix=nil end; return 1')
        p.wait_for(lambda: gui()['inventory'] == ses['inv0'] and p.state()['pzMirror']['status'] == 'in step' or None, 30, .4, 'inventory restored')
    except Exception as e:
        errors.append(str(e))
    try:
        if gui()['cameraMode'] != ses['camera']: p.cmd(f'camera {ses["camera"]}')
    except Exception as e:
        errors.append('camera: ' + str(e))
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + ses['option'] + ')')
    st = p.state()
    print('inventory restored:', st['minecraftGui']['inventory'] == ses['inv0'], '| camera', st['minecraftGui']['cameraMode'], '| paused', st['paused'], '| errors', errors)
    if not errors and st['minecraftGui']['inventory'] == ses['inv0']: SESSION.unlink()


if __name__ == '__main__':
    {'start': lambda: start(*sys.argv[2:3]), 'stop': stop, 'play': lambda: play(*sys.argv[2:4]), 'swing': lambda: swing(*sys.argv[2:3]), 'arm': arm,
        'ads': lambda: _burst(sys.argv[2], 2.2, ['USE'], 2400), 'fire': lambda: _burst(sys.argv[2], 1.2, ['ATTACK'], 150, {'yaw': float(sys.argv[3]) if len(sys.argv) > 3 else None, 'pitch': -8, 'relative': False} if False else None),
        'walk': lambda: _burst(sys.argv[2], 2.0, ['FWD'], 1800), 'sprint': lambda: _burst(sys.argv[2], 2.0, ['FWD', 'SPRINT'], 1800)}.get(sys.argv[1], lambda: shot(sys.argv[2]))()
