"""Generic firearm mechanics for any PZ gun, from the gun's own script data (items.json weapon.* fields).

  python check-guns.py Base.Pistol2 [Base.Revolver ...]

Per gun (temporary gun in the borrowed empty hotbar slot 8; every added item is removed by its PZ id afterwards):
  1  an empty gun reloads with R (magazine guns: a full magazine; rounds guns: rounds one at a time, with the rack afterwards when
     the gun has a chamber) and ends with the right ammunition fields
  2  one shot: 'fired', the round counts follow PZ's onShoot; a rack-after-shoot gun is 'racking' and then 'racked'
  3  every remaining round, then a dry click (and, for a magazine gun, the empty magazine drops out)
  4  shift+R: takes the magazine out (magazine guns) or the rounds back one at a time (the others)
  5  I: 'inspecting'
  The clips the gun model plays are recorded all the while (every clip start with its time and the step it fell in), and with
  --shots pictures are taken through the reload and the first shot (gun-mechanics-<stamp>/<gun>-reload.png, -shot.png).
Result: gun-mechanics-<stamp>/result.json. The world runs with every native zombie frozen while a gun is tested; Steve looks at open ground.
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
ITEMS = Path.home() / '.pzcraft/items/items.json'


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def catalog(fulltype):
    for it in json.loads(ITEMS.read_text(encoding='utf-8'))['items']:
        if it['id'] == fulltype:
            return it['weapon']
    raise SystemExit('not in the catalogue: ' + fulltype)


def gstate():
    return lua('local w=_pzM9Fix;return tostring(w:isContainsClip())..","..w:getCurrentAmmoCount()..","..tostring(w:isRoundChambered())..","..'
               'w:getSpentRoundCount()..","..tostring(w:isSpentRoundChambered())..","..tostring(w:isJammed())')


def parse(s):
    clip, ammo, chamber, spent_count, spent_chambered, jam = s.split(',')
    return {'clip': clip == 'true', 'ammo': int(ammo), 'chamber': chamber == 'true', 'spentCount': int(spent_count),
            'spentChambered': spent_chambered == 'true', 'jam': jam == 'true'}


def ids():
    return set(x for x in (lua('local o={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do o[#o+1]=tostring(it:get(i):getID()) end;return table.concat(o,",")') or '').split(',') if x)


def key(code, action_pairs=((1,), (0,))):
    p.api('POST', '/gui', {'key': code, 'action': 1})
    p.api('POST', '/gui', {'key': code, 'action': 0})


def last():
    return p.state()['pzGuns']['last']


def retry_slot(slot, attempts=4):
    """The hotbar request only arrives while the world runs; one failed restore once left the owner's slot on the fixture's."""
    for i in range(attempts):
        try:
            p._select_slot(slot, timeout=8)
            return
        except Exception:
            if i == attempts - 1: raise
            time.sleep(1.0)


def wait_status(prefix, seconds=10, what=None):
    return p.wait_for(lambda: last().get('status', '').startswith(prefix) and last() or None, seconds, .05, what or prefix)


def tap():
    p.send_input(['ATTACK'], 150, wait=True)


class Watch(threading.Thread):
    """Notes every clip the gun model starts (the client counts them) and, on request, takes pictures as fast as it can."""
    def __init__(self):
        super().__init__(daemon=True)
        self.clips, self.frames, self.step, self.t0 = [], {}, 'start', time.time()
        self.capture, self.stop = None, False
        self.played = None

    def run(self):
        while not self.stop:
            try:
                a = p.state()['minecraftGui']['gunFeel'].get('animation') or {}
                n = a.get('clipsPlayed')
                if n is not None and n != self.played:
                    if self.played is not None:
                        self.clips.append({'t': round(time.time() - self.t0, 2), 'step': self.step, 'clip': a.get('lastClip'), 'speed': round(a.get('speed', 1), 2), 'plays': n - self.played})
                    self.played = n
                if self.capture and len(self.frames.setdefault(self.capture, [])) < 24:
                    self.frames[self.capture].append((round(time.time() - self.t0, 2), p.screenshot(.5)))
                    time.sleep(.12)
                else:
                    time.sleep(.05)
            except Exception:
                time.sleep(.1)


def sheets(watch, out_root, gun_type):
    """The pictures of each capture as one image, four to a row."""
    try:
        import numpy as np
        sys.path.insert(0, str(HERE.parent / 'vic-guns'))
        import pngio
    except Exception as e:
        return ['no picture sheet: ' + repr(e)]
    made = []
    for label, frames in watch.frames.items():
        if not frames: continue
        images = [pngio.read_bytes(data)[..., :3] for _, data in frames]
        while len(images) % 4: images.append(np.zeros_like(images[0]))
        rows = [np.concatenate(images[i:i + 4], 1) for i in range(0, len(images), 4)]
        out = out_root / f'{gun_type}-{label}.png'
        pngio.write(out, np.concatenate(rows, 0))
        made.append(str(out))
    return made


SHOTS = False


def run_one(gun_type, out_root):
    w = catalog(gun_type)
    magazine, ammo_key = w.get('magazine'), w['ammoKey']
    capacity = w['maxAmmo']
    rack_after = w['rackAfterShoot']
    have_chamber = w['haveChamber']
    cooldown = max(.1, w['recoilDelay'] / 30.0) + .15
    record = {'gun': gun_type, 'catalogue': {k: w[k] for k in ('reloadType', 'magazine', 'ammoKey', 'maxAmmo', 'rackAfterShoot', 'haveChamber', 'insertAll', 'needsClose', 'manualSpent', 'recoilDelay', 'projectiles', 'fireMode')}, 'steps': [], 'passed': False}
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and not baseline['input']['live'] and not baseline['overrideActive'] and baseline['pzMirror']['status'] == 'in step'
    assert g0['inventory'][8].startswith('0 '), 'fixture hotbar slot8 occupied'
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    known = ids()
    watch = Watch()
    def step(name, **d):
        watch.step = name
        record['steps'].append({'name': name, **d}); (out_root / f'{gun_type}.json').write_text(json.dumps(record, indent=2, default=str))
    try:
        lua('_pzGmHeld={};_pzGmSeen={};function _pzGmFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzGmSeen[z] then _pzGmSeen[z]=true;table.insert(_pzGmHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzGmFreeze();Events.OnTick.Add(_pzGmFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); p.cmd('look 0 -5'); time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start'](gun_type)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        for _ in range(2):
            lua('_pzGmFreeze()'); time.sleep(.4)
        lua('local w=_pzM9Fix;w:setJamGunChance(0);w:setContainsClip(false);w:setCurrentAmmoCount(0);w:setRoundChambered(false);w:setSpentRoundCount(0);w:setSpentRoundChambered(false);w:setJammed(false);return 1')
        if w.get('fireMode') == 'Auto':      # a select-fire gun: a 150 ms click would be two shots in Auto (check-gun-auto.py covers that)
            lua('_pzM9Fix:setFireMode("Single");return 1')
            time.sleep(.5)
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.5)
        g = p.state()['pzGuns']
        step('fixture', held=p.state()['minecraftGui']['held']['item'], state=gstate(), kind=(p.state()['minecraftGui']['gunFeel'].get('animation') or {}).get('kind'))
        watch.start()

        # ---- 1: reload an empty gun with R ----
        if magazine:
            lua(f'local m=getPlayer():getInventory():AddItem("{magazine}");m:setCurrentAmmoCount({capacity});return 1')
            p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.5)
            if SHOTS: watch.capture = 'reload'
            t0 = time.time(); key(82)
            wait_status('reloaded', 15, 'magazine reload'); took = time.time() - t0
            s = parse(gstate())
            step('1 magazine reload', seconds=round(took, 2), state=s)
            assert s['clip'] and (s['chamber'] if have_chamber else True), s
            assert s['ammo'] == (capacity - 1 if have_chamber else capacity), s
        else:
            n = capacity + 2
            for _ in range(n): lua(f'getPlayer():getInventory():AddItem("{ammo_key}");return 1')
            p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 20, .15, 'mirror'); time.sleep(.5)
            if SHOTS: watch.capture = 'reload'
            r0 = p.state()['pzGuns']['gunRoundsLoaded']; t0 = time.time(); key(82)
            wait_status('loaded', 60, 'rounds loaded'); took = time.time() - t0
            if have_chamber:
                p.wait_for(lambda: not p.state()['pzGuns']['racking'] and not p.state()['pzGuns']['loadingRounds'] or None, 8, .1, 'rack finished')
            s = parse(gstate())
            step('1 rounds loaded', seconds=round(took, 2), state=s, roundsLoaded=p.state()['pzGuns']['gunRoundsLoaded'] - r0)
            assert p.state()['pzGuns']['gunRoundsLoaded'] - r0 == capacity, p.state()['pzGuns']['gunRoundsLoaded'] - r0
            total = s['ammo'] + (1 if s['chamber'] else 0)
            assert total == capacity, s
            if have_chamber: assert s['chamber'], s

        # ---- 2: one shot ----
        time.sleep(1.6 if SHOTS else 0)     # the closing clip
        watch.capture = 'shot' if SHOTS else None
        time.sleep(cooldown)
        before = parse(gstate()); n0 = p.state()['pzGuns']['shots']
        tap(); wait_status('fired', 6, 'shot')
        after = parse(gstate())
        step('2 one shot', before=before, after=after)
        assert p.state()['pzGuns']['shots'] == n0 + 1
        if rack_after:
            assert not after['chamber'] and after['spentChambered'], after
            r = wait_status('racked', 6, 'rack after the shot')
            time.sleep(.2)
            done = parse(gstate())
            step('2 racked', state=done)
            assert done['chamber'] and not done['spentChambered'], done
        else:
            total_before = before['ammo'] + (1 if before['chamber'] else 0)
            total_after = after['ammo'] + (1 if after['chamber'] else 0)
            assert total_after == total_before - 1, (before, after)

        # ---- 3: the rest, then a dry click ----
        if SHOTS: time.sleep(1.2)
        watch.capture = None
        fired = 1
        for _ in range(60):
            time.sleep(cooldown + (1.1 if rack_after else 0))
            s = parse(gstate())
            if not s['chamber'] and (s['ammo'] < 1 if have_chamber else s['ammo'] < 1):
                break
            n = p.state()['pzGuns']['shots']
            tap(); time.sleep(.25)
            if p.state()['pzGuns']['shots'] > n: fired += 1
        s = parse(gstate())
        step('3 emptied', shotsFired=fired, state=s)
        assert fired == capacity or fired == capacity + 0, (fired, capacity)
        d0 = p.state()['pzGuns']['dry']
        time.sleep(cooldown); tap(); time.sleep(.4)
        step('3 dry click', dry=p.state()['pzGuns']['dry'] - d0, last=last())
        assert p.state()['pzGuns']['dry'] == d0 + 1, last()

        # ---- 4: shift+R ----
        if magazine:
            lua(f'local w=_pzM9Fix;w:setContainsClip(true);w:setCurrentAmmoCount(5);w:setRoundChambered({str(have_chamber).lower()});return 1')
            p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.4)
            p.send_input(['SNEAK'], 1500, wait=False); time.sleep(.25); key(82)
            wait_status('magazine ejected', 6, 'magazine ejected'); p.send_input(release=True)
            s = parse(gstate())
            step('4 magazine ejected', state=s)
            assert not s['clip'] and s['ammo'] == 0, s
        else:
            lua(f'local w=_pzM9Fix;w:setCurrentAmmoCount({min(capacity, 3)});w:setRoundChambered(false);return 1')
            for _ in range(0): pass
            p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.4)
            u0 = p.state()['pzGuns']['roundsUnloaded']
            p.send_input(['SNEAK'], 12000, wait=False); time.sleep(.25); key(82)
            wait_status('unloaded', 30, 'unloaded'); p.send_input(release=True)
            s = parse(gstate())
            step('4 rounds taken out', state=s, unloaded=p.state()['pzGuns']['roundsUnloaded'] - u0)
            assert s['ammo'] == 0 and p.state()['pzGuns']['roundsUnloaded'] - u0 == min(capacity, 3), s

        # ---- 5: inspect ----
        time.sleep(1.0)
        i0 = p.state()['pzGuns']['inspects']
        key(73)
        wait_status('inspecting', 4, 'inspect')
        step('5 inspect', clip=last().get('clip'), inspects=p.state()['pzGuns']['inspects'] - i0)
        record['passed'] = True
    except Exception as e:
        record['error'] = repr(e); record['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('input', lambda: p.send_input(release=True))
        clean('new items', lambda: lua('local keep={};for id in string.gmatch("' + ','.join(known) + '","[^,]+") do keep[id]=true end;local it=getPlayer():getInventory():getItems();local rm={};'
              'for i=0,it:size()-1 do local x=it:get(i);if not keep[tostring(x:getID())] and x~=_pzM9Fix then rm[#rm+1]=x end end;for _,x in ipairs(rm) do getPlayer():getInventory():Remove(x) end;return #rm'))
        clean('gun fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('slot', lambda: retry_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 30, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzGmFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzGmHeld then for _,v in ipairs(_pzGmHeld) do v[1]:setUseless(v[2]) end;_pzGmHeld=nil end'))
        native_after = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if native_after != native0: errors.append('owner native inventory differs')
        if p.state()['health']['steveHealth'] < float(baseline['health']['steveHealth']) - 0.01: errors.append('Steve lost health during the run: ' + str(baseline['health']['steveHealth']) + ' -> ' + str(p.state()['health']['steveHealth']))
        watch.stop = True
        record['clips'] = watch.clips
        record['pictures'] = sheets(watch, out_root, gun_type) if watch.frames else []
        record['cleanupErrors'] = errors
        if errors: record['passed'] = False
        (out_root / f'{gun_type}.json').write_text(json.dumps(record, indent=2, default=str))
    return record


def main():
    global SHOTS
    SHOTS = '--shots' in sys.argv
    guns = [a for a in sys.argv[1:] if a != '--shots'] or ['Base.Pistol2']
    out = HERE / ('gun-mechanics-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    summary = {}
    for g in guns:
        r = run_one(g, out)
        summary[g] = {'passed': r['passed'], 'error': r.get('error'), 'cleanupErrors': r.get('cleanupErrors')}
        print(json.dumps({g: summary[g]}))
        last_step, line = None, []
        for c in r.get('clips', []):
            if c['step'] != last_step:
                if line: print('   ', last_step, '->', ' '.join(line))
                last_step, line = c['step'], []
            line.append(f"{c['clip']}@{c['t']}" + (f"x{c['plays']}" if c['plays'] > 1 else '') + (f"(s{c['speed']})" if c['speed'] != 1 else ''))
        if line: print('   ', last_step, '->', ' '.join(line))
    (out / 'summary.json').write_text(json.dumps(summary, indent=2))
    return 0 if all(v['passed'] for v in summary.values()) else 1


if __name__ == '__main__':
    sys.exit(main())
