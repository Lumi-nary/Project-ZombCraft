"""Inspect (I key) and the shot's impact effects, with the numbers that show where a shot really went.

A  inspect: I with a loaded pistol plays animation.model.inspect and PZ answers 'inspecting'; with an empty gun inspectempty; a
   shot ends it; aiming ends it; the clip finishes back to idle.
B  block impact: a probe shot finds an open lane, one stone block is placed 4 blocks ahead in it (only after the probe proved the
   cell empty) and shot: the ray must end on that block, smoke (5) and chips (6) are spawned, frames are saved.
C  actor impact: one frozen native zombie 4 blocks ahead; shots until PZ's roll hits (blood sprites in the frame), then a kill with
   one hit point left (blood mist). The ray's end point must lie inside the proxy's box (aim check) and the box is compared with
   the zombie's own position.
Fixture: temporary pistol (new PZ id, borrowed empty hotbar slot 8), one stone block, one zombie; all removed by identity.
"""
import json
import math
import runpy
import sys
import time
import traceback
from pathlib import Path
import pzcore as p

HERE = Path(__file__).parent
m9 = runpy.run_path(str(HERE / 'm9-session.py'))
native_frame = runpy.run_path(str(HERE / 'capture-weapon-view.py'))['native_frame']


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def gf():
    return p.state()['minecraftGui']['gunFeel']


def key(code):
    p.api('POST', '/gui', {'key': code, 'action': 1})
    p.api('POST', '/gui', {'key': code, 'action': 0})


def frames(folder, name, count, gap=.1, scale=.6):
    folder.mkdir(exist_ok=True)
    t0 = time.time()
    for n in range(count):
        (folder / f'{name}-{n:02d}-{int((time.time() - t0) * 1000):04d}ms.png').write_bytes(p.screenshot(scale))
        time.sleep(gap)


def tap(ms=150):
    p.send_input(['ATTACK'], ms, wait=True)


def shot_count():
    return (gf().get('lastShot') or {}).get('n', 0)


def wait_shot(before, seconds=6):
    """before: shot_count() read before the tap; returns the new confirmed shot's record (the client's, with the ray)."""
    return p.wait_for(lambda: (gf().get('lastShot') or {}).get('n', 0) > before and gf()['lastShot'] or None, seconds, .05, 'a confirmed shot')


def arm_gun():
    lua('local w=_pzM9Fix;assert(w);w:setContainsClip(true);w:setCurrentAmmoCount(14);w:setRoundChambered(true);w:setJamGunChance(0);return 1')
    p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.6)


def run():
    out = HERE / ('gun-impacts-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and not baseline['input']['live'] and not baseline['overrideActive']
    assert baseline['pzMirror']['status'] == 'in step'
    assert g0['inventory'][8].startswith('0 '), 'fixture hotbar slot8 occupied'
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    result = {'before': baseline, 'nativeBefore': native0, 'steps': [], 'passed': False}
    placed = None
    def step(name, **d):
        result['steps'].append({'name': name, **d}); (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    try:
        lua('_pzGiHeld={};_pzGiSeen={};function _pzGiFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzGiSeen[z] then _pzGiSeen[z]=true;table.insert(_pzGiHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzGiFreeze();Events.OnTick.Add(_pzGiFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start']()
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        for _ in range(2):
            lua('_pzGiFreeze()'); time.sleep(.5)
        arm_gun()
        steve = p.state()['steve']
        step('start', steve=steve, held=p.state()['minecraftGui']['held']['item'])

        # ---------------- A: inspect ----------------
        p.cmd('look 0 0'); time.sleep(.5)
        i0 = p.state()['pzGuns']['inspects']
        key(73)
        r = p.wait_for(lambda: p.state()['pzGuns']['last'].get('status') == 'inspecting' and p.state()['pzGuns'] or None, 4, .05, 'inspect answer')
        assert r['inspects'] == i0 + 1 and r['last']['clip'] == 'inspect', r['last']
        t0 = time.time(); clips = []
        folder = out / 'inspect'; folder.mkdir()
        while time.time() - t0 < 6.6:
            a = gf()['animation']
            clips.append((round(time.time() - t0, 2), a['clip'], round(a['time'], 2), a['triggered']))
            if len(clips) % 5 == 1: (folder / f'loaded-{int((time.time() - t0) * 1000):05d}ms.png').write_bytes(p.screenshot(.5))
            time.sleep(.2)
        step('inspect loaded', clips=clips)
        assert any(c[1] == 'animation.model.inspect' and c[3] for c in clips[:5]), clips[:5]
        assert clips[-1][1] == 'animation.model.idle', clips[-1]
        # a shot ends the inspection
        key(73); p.wait_for(lambda: p.state()['pzGuns']['inspects'] == i0 + 2 or None, 4, .05, 'second inspect'); time.sleep(1.2)
        s0 = shot_count()
        tap(); wait_shot(s0); time.sleep(.3)
        a = gf()['animation']
        step('shot cancels inspect', clip=a['clip'])
        assert a['clip'] in ('animation.model.fire', 'animation.model.idle'), a
        time.sleep(1.2)
        # aiming ends it
        key(73); p.wait_for(lambda: p.state()['pzGuns']['inspects'] == i0 + 3 or None, 4, .05, 'third inspect'); time.sleep(1.0)
        p.send_input(['USE'], 900, wait=False); time.sleep(.7)
        a = gf()['animation']
        step('aim cancels inspect', clip=a['clip'], aim=gf()['aim'])
        assert a['clip'] == 'animation.model.idle' and gf()['aim'] > .5, (a, gf()['aim'])
        p.send_input(release=True); time.sleep(.6)
        # empty gun
        lua('local w=_pzM9Fix;w:setContainsClip(false);w:setCurrentAmmoCount(0);w:setRoundChambered(false);return 1')
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(1.5)
        key(73)
        r = p.wait_for(lambda: p.state()['pzGuns']['last'].get('status') == 'inspecting' and p.state()['pzGuns'] or None, 4, .05, 'inspect answer (empty)')
        assert r['last']['clip'] == 'inspectempty', r['last']
        time.sleep(.6)
        a = gf()['animation']
        frames(folder, 'empty', 4, .5)
        step('inspect empty', clip=a['clip'])
        assert a['clip'] == 'animation.model.inspectempty', a
        time.sleep(6.5)
        arm_gun()

        # ---------------- B: block impact ----------------
        lane = None
        for yaw in (0, 90, 180, 270, 45, 135, 225, 315):
            p.cmd(f'look {yaw} 0'); time.sleep(.6)
            s0 = shot_count()
            tap(); shot = wait_shot(s0); time.sleep(1.3)
            step(f'probe yaw {yaw}', lastShot=shot)
            if shot.get('block') is None and 'targetMin' not in shot and shot['distance'] > 18:
                lane = yaw; break
        assert lane is not None, 'no open lane for the fixtures'
        steve = p.state()['steve']
        yawr = math.radians(lane)
        bx, bz = math.floor(steve['x'] - math.sin(yawr) * 4), math.floor(steve['z'] + math.cos(yawr) * 4)
        by = math.floor(steve['y'] + 1.62)
        p.cmd(f'mc setblock {bx} {by} {bz} minecraft:stone keep')
        placed = (bx, by, bz); time.sleep(.8)
        s0 = shot_count(); imp0 = dict(gf()['impacts'])
        tap(); shot = wait_shot(s0)
        frames(out / 'block', 'impact', 8, .12)
        imp1 = gf()['impacts']
        step('block impact', lastShot=shot, impactsBefore=imp0, impactsAfter=imp1)
        assert shot.get('block') == f'{bx}, {by}, {bz}', shot.get('block')
        assert imp1['smoke'] == imp0['smoke'] + 5 and imp1['chips'] == imp0['chips'] + 6, (imp0, imp1)
        end = shot['end']
        assert abs(end[1] - (steve['y'] + 1.62)) < .25, f'the ray did not stay at eye level: {end}'
        time.sleep(1.2)

        # ---------------- C: actor impact ----------------
        p.cmd('mc setblock %d %d %d minecraft:air replace' % placed); placed = None
        tx, tz = steve['x'] - math.sin(yawr) * 4, steve['z'] + math.cos(yawr) * 4
        # a killed zombie leaves a corpse: remember the bodies already around the spot so only the new one is removed afterwards
        lua('_pzGiBodyAt={' + f'{math.floor(tx)},{math.floor(tz)}' + '};_pzGiOldBodies={};for dx=-2,2 do for dy=-2,2 do local sq=getCell():getGridSquare(_pzGiBodyAt[1]+dx,_pzGiBodyAt[2]+dy,0);'
            'if sq then local bodies=sq:getDeadBodys();for i=0,bodies:size()-1 do _pzGiOldBodies[bodies:get(i)]=true end end end end;return 1')
        made = lua('local known={};local a=getCell():getZombieList();for i=0,a:size()-1 do known[a:get(i)]=true end;'
                   f'addZombiesInOutfit({math.floor(tx)},{math.floor(tz)},0,1,nil,50);'
                   'a=getCell():getZombieList();local n=0;for i=0,a:size()-1 do local z=a:get(i);if not known[z] then '
                   f'z:setX({tx});z:setY({tz});z:setHealth(100);z:setUseless(true);_pzGiTarget=z;n=n+1 end end;return n')
        assert float(made) == 1, made
        time.sleep(1.0)
        p.cmd(f'look {lane} 6'); time.sleep(.6)       # a little down: chest height
        hit_shot, tries = None, 0
        while tries < 12 and hit_shot is None:
            tries += 1
            lua('local z=_pzGiTarget;z:setHealth(100);z:setFakeDead(false);z:setOnFloor(false);z:setKnockedDown(false);return 1')
            time.sleep(1.3)
            s0 = shot_count(); imp0 = dict(gf()['impacts'])
            tap(); shot = wait_shot(s0)
            if shot['hit']:
                hit_shot = shot
                frames(out / 'blood', 'hit', 8, .12)
                batches = native_frame(all_batches=True)
                step('actor hit', tries=tries, lastShot=shot, impactsBefore=imp0, impactsAfter=gf()['impacts'],
                     batches=[{k: b[k] for k in ('texture', 'width', 'height', 'flags', 'triangles', 'alpha')} for b in (batches or {'batches': []})['batches']])
            else:
                step('actor missed (PZ roll)', tries=tries, lastShot=shot)
        assert hit_shot is not None, 'PZ never hit in 12 shots'
        box_lo, box_hi, end = hit_shot['targetMin'], hit_shot['targetMax'], hit_shot['end']
        assert all(box_lo[a] - .05 <= end[a] <= box_hi[a] + .05 for a in range(3)), f'the ray end {end} is not in the box {box_lo}..{box_hi}'
        assert gf()['impacts']['blood'] > imp0['blood'], gf()['impacts']
        zpos = lua('local z=_pzGiTarget;return z:getX()..","..z:getY()..","..z:getZ()')
        step('proxy box vs the zombie', zombie=zpos, box=[box_lo, box_hi])
        time.sleep(2.2)
        # a kill: one hit point left
        killed = None
        for tries in range(12):
            lua('local z=_pzGiTarget;z:setHealth(0.03);z:setFakeDead(false);z:setOnFloor(false);z:setKnockedDown(false);return 1')
            time.sleep(1.3)
            s0 = shot_count(); imp0 = dict(gf()['impacts'])
            tap(); shot = wait_shot(s0)
            if shot['hit'] and shot['killed']:
                killed = shot
                frames(out / 'mist', 'kill', 10, .15)
                step('actor kill', tries=tries + 1, lastShot=shot, impactsBefore=imp0, impactsAfter=gf()['impacts'])
                break
        assert killed is not None, 'no kill in 12 shots'
        assert gf()['impacts']['mist'] > imp0['mist'], gf()['impacts']

        # ---------------- D: a PZ surface (the ground): smoke and dirt debris ----------------
        p.cmd(f'look {lane} 35'); time.sleep(1.4)
        s0 = shot_count(); imp0 = dict(gf()['impacts'])
        tap(); shot = wait_shot(s0)
        frames(out / 'ground', 'impact', 8, .12)
        imp1 = gf()['impacts']
        step('ground impact', lastShot=shot, impactsBefore=imp0, impactsAfter=imp1)
        assert shot.get('normal') is not None and shot.get('block') is None and 'targetMin' not in shot, shot
        assert imp1['smoke'] == imp0['smoke'] + 5 and imp1['debris'] == imp0['debris'] + 6, (imp0, imp1)
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
        clean('stone', lambda: p.cmd('mc setblock %d %d %d minecraft:air replace' % placed) if placed else None)
        clean('zombie', lambda: lua('if _pzGiTarget then _pzGiTarget:removeFromWorld();_pzGiTarget:removeFromSquare();_pzGiTarget=nil end;return 1'))
        clean('corpse', lambda: lua('if _pzGiOldBodies then local n=0;for dx=-3,3 do for dy=-3,3 do local sq=getCell():getGridSquare(_pzGiBodyAt[1]+dx,_pzGiBodyAt[2]+dy,0);'
            'if sq then local bodies=sq:getDeadBodys();local fresh={};for i=0,bodies:size()-1 do local b=bodies:get(i);if not _pzGiOldBodies[b] then fresh[#fresh+1]=b end end;'
            'for _,b in ipairs(fresh) do sq:removeCorpse(b,false);n=n+1 end end end end;_pzGiOldBodies=nil;return n end;return 0'))
        clean('pistol fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('camera', lambda: p.cmd(f'camera {g0["cameraMode"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 20, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzGiFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzGiHeld then for _,v in ipairs(_pzGiHeld) do v[1]:setUseless(v[2]) end;_pzGiHeld=nil end'))
        result['after'] = p.state()
        result['nativeAfter'] = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if result['nativeAfter'] != native0: errors.append('owner native inventory differs')
        if p.state()['health']['steveHealth'] < float(baseline['health']['steveHealth']) - 0.01: errors.append('Steve lost health during the run: ' + str(baseline['health']['steveHealth']) + ' -> ' + str(p.state()['health']['steveHealth']))
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run())
