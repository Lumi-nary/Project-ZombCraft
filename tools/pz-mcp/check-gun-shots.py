"""Pellets and piercing: one shot reaching several native zombies.

  python check-gun-shots.py Base.Shotgun        shotgun-type guns (projectiles > 1): three frozen zombies side by side 3 blocks ahead;
                                                the shots must reach more than one of them (distinct actors in PZ's answer)
  python check-gun-shots.py Base.HuntingRifle   guns with maxHitCount > 1: two zombies in a line

Fixture: temporary gun in the borrowed empty slot 8 (loaded by field), ammunition not needed; zombies and their corpses removed.
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
ITEMS = Path.home() / '.pzcraft/items/items.json'


def lua(text):
    values = p.lua(text)['results']
    return values[0] if values else None


def gf():
    return p.state()['minecraftGui']['gunFeel']


def catalog(t):
    for it in json.loads(ITEMS.read_text(encoding='utf-8'))['items']:
        if it['id'] == t:
            return it['weapon']
    raise SystemExit('not in the catalogue: ' + t)


def shot_count():
    return (gf().get('lastShot') or {}).get('n', 0)


def wait_shot(before, seconds=8):
    return p.wait_for(lambda: (gf().get('lastShot') or {}).get('n', 0) > before and gf()['lastShot'] or None, seconds, .05, 'a confirmed shot')


def spawn(x, z):
    made = lua('local known={};local a=getCell():getZombieList();for i=0,a:size()-1 do known[a:get(i)]=true end;'
               f'addZombiesInOutfit({math.floor(x)},{math.floor(z)},0,1,nil,50);'
               'a=getCell():getZombieList();local n=0;for i=0,a:size()-1 do local z=a:get(i);if not known[z] then '
               f'z:setX({x});z:setY({z});z:setHealth(100);z:setUseless(true);_pzGsT=_pzGsT or {{}};table.insert(_pzGsT,z);n=n+1 end end;return n')
    assert float(made) == 1, made


def run(gun_type):
    w = catalog(gun_type)
    out = HERE / ('gun-shots-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    result = {'gun': gun_type, 'catalogue': {k: w.get(k) for k in ('projectiles', 'spread', 'piercing', 'maxHitCount', 'maxRange', 'rackAfterShoot', 'haveChamber')}, 'steps': [], 'passed': False}
    baseline = p.state()
    g0 = baseline['minecraftGui']
    assert baseline['paused'] and baseline['pzMirror']['status'] == 'in step' and g0['inventory'][8].startswith('0 ')
    option = lua('return tostring(getCore():getOptionPauseOnFocusloss())')
    native0 = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
    def step(name, **d):
        result['steps'].append({'name': name, **d}); (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    try:
        lua('_pzGsHeld={};_pzGsSeen={};_pzGsT={};function _pzGsFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzGsSeen[z] then _pzGsSeen[z]=true;table.insert(_pzGsHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzGsFreeze();Events.OnTick.Add(_pzGsFreeze);getCore():setOptionPauseOnFocusloss(false);UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p._select_slot(8); p.cmd('camera 0'); time.sleep(.4)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        m9['start'](gun_type)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        for _ in range(2):
            lua('_pzGsFreeze()'); time.sleep(.4)
        def load():
            lua('local w=_pzM9Fix;w:setJamGunChance(0);w:setContainsClip(' + str(bool(w.get('magazine'))).lower() + ');w:setCurrentAmmoCount(' + str(max(1, w['maxAmmo'] - 1)) + ');w:setRoundChambered(true);w:setSpentRoundChambered(false);w:setSpentRoundCount(0);return 1')
            p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' or None, 12, .15, 'mirror'); time.sleep(.4)
        load()
        # an open lane (a probe shot into the air)
        lane = None
        for yaw in (0, 90, 180, 270, 45, 135, 225, 315):
            p.cmd(f'look {yaw} 0'); time.sleep(.6)
            n0 = shot_count(); p.send_input(['ATTACK'], 150, wait=True); shot = wait_shot(n0)
            step(f'probe {yaw}', end=shot['end'], distance=shot['distance'], block=shot.get('block'), impacts=shot.get('impacts'), pellets=shot.get('pellets'))
            time.sleep(2.2 if w.get('rackAfterShoot') else 1.4)
            load()
            if shot.get('block') is None and 'targetMin' not in shot and shot['distance'] > 18:
                lane = yaw; break
        assert lane is not None, 'no open lane'
        steve = p.state()['steve']
        yawr = math.radians(lane)
        fwd = (-math.sin(yawr), math.cos(yawr)); side = (math.cos(yawr), math.sin(yawr))
        lua('_pzGsBodyAt={' + f'{math.floor(steve["x"] + fwd[0] * 4)},{math.floor(steve["z"] + fwd[1] * 4)}' + '};_pzGsOld={};for dx=-4,4 do for dy=-4,4 do local sq=getCell():getGridSquare(_pzGsBodyAt[1]+dx,_pzGsBodyAt[2]+dy,0);'
            'if sq then local bodies=sq:getDeadBodys();for i=0,bodies:size()-1 do _pzGsOld[bodies:get(i)]=true end end end end;return 1')

        def volley(label, positions, want_distinct, shots=8):
            best = 0; rows = []
            for k in range(shots):
                lua('local a=_pzGsT;for i=1,#a do local z=a[i];z:setHealth(100);z:setFakeDead(false);z:setOnFloor(false);z:setKnockedDown(false) end;return 1')
                # put them back where they were: a hit pushes a zombie
                for i, (x, z) in enumerate(positions):
                    lua(f'local z=_pzGsT[{i + 1}];z:setX({x});z:setY({z});return 1')
                time.sleep(1.1 + (1.0 if w.get('rackAfterShoot') else 0))
                load()
                p.cmd(f'look {lane} 6'); time.sleep(.5)
                n0 = shot_count(); p.send_input(['ATTACK'], 150, wait=True); shot = wait_shot(n0)
                rows.append({'hits': shot.get('hits', 0), 'impacts': shot.get('impacts'), 'pellets': shot.get('pellets'), 'hit': shot.get('hit')})
                best = max(best, shot.get('hits', 0))
                if best >= want_distinct and k >= 1: break
            step(label, rows=rows, best=best, impacts=gf()['impacts'])
            return best

        if (w.get('projectiles') or 1) > 1:
            d = 3.0
            spots = [(steve['x'] + fwd[0] * d + side[0] * off, steve['z'] + fwd[1] * d + side[1] * off) for off in (0, -0.4, 0.4)]
            for x, z in spots: spawn(x, z)
            time.sleep(1.0)
            best = volley('pellets: three zombies side by side', spots, 2, shots=10)
            assert best >= 2, f'a pellet shot never reached two zombies (best {best})'
        elif (w.get('maxHitCount') or 1) > 1:
            spots = [(steve['x'] + fwd[0] * d, steve['z'] + fwd[1] * d) for d in (3.0, 5.0)]
            for x, z in spots: spawn(x, z)
            time.sleep(1.0)
            best = volley('piercing: two zombies in a line', spots, 2, shots=14)
            assert best >= 2, f'a bullet never went through to the second zombie (best {best})'
        else:
            step('nothing to check', note='single projectile, one hit: the plain shot checks cover it')
        result['passed'] = True
    except Exception as e:
        result['error'] = repr(e); result['traceback'] = traceback.format_exc()
    finally:
        errors = []
        def clean(label, fn):
            try: fn()
            except Exception as e: errors.append(label + ': ' + repr(e))
        clean('input', lambda: p.send_input(release=True))
        clean('zombies', lambda: lua('if _pzGsT then for _,z in ipairs(_pzGsT) do z:removeFromWorld();z:removeFromSquare() end;_pzGsT=nil end;return 1'))
        clean('corpses', lambda: lua('if _pzGsOld then local n=0;for dx=-6,6 do for dy=-6,6 do local sq=getCell():getGridSquare(_pzGsBodyAt[1]+dx,_pzGsBodyAt[2]+dy,0);'
              'if sq then local bodies=sq:getDeadBodys();local fresh={};for i=0,bodies:size()-1 do local b=bodies:get(i);if not _pzGsOld[b] then fresh[#fresh+1]=b end end;'
              'for _,b in ipairs(fresh) do sq:removeCorpse(b,false);n=n+1 end end end end;_pzGsOld=nil;return n end;return 0'))
        clean('gun fixture', lambda: m9['stop']() if (HERE / 'm9-session.json').exists() else None)
        clean('unpause', lambda: lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)'))
        clean('look', lambda: p.cmd(f'look {baseline["steve"]["yaw"]} {baseline["steve"]["pitch"]}'))
        clean('slot', lambda: p._select_slot(g0['selectedSlot']))
        clean('inventory', lambda: p.wait_for(lambda: p.state()['minecraftGui']['inventory'] == g0['inventory'] and p.state()['pzMirror']['status'] == 'in step' or None, 30, .2, 'owner inventory restored'))
        clean('pause', lambda: lua('Events.OnTick.Remove(_pzGsFreeze);UIManager.getSpeedControls():SetCurrentGameSpeed(0);getCore():setOptionPauseOnFocusloss(' + option + ');if _pzGsHeld then for _,v in ipairs(_pzGsHeld) do v[1]:setUseless(v[2]) end;_pzGsHeld=nil end'))
        native_after = lua('local a={};local it=getPlayer():getInventory():getItems();for i=0,it:size()-1 do local m=it:get(i);a[#a+1]=m:getID()..":"..m:getFullType()..":"..m:getCurrentAmmoCount() end;table.sort(a);return table.concat(a,"|")')
        if native_after != native0: errors.append('owner native inventory differs')
        if p.state()['health']['steveHealth'] < float(baseline['health']['steveHealth']) - 0.01: errors.append('Steve lost health during the run: ' + str(baseline['health']['steveHealth']) + ' -> ' + str(p.state()['health']['steveHealth']))
        result['cleanupErrors'] = errors
        if errors: result['passed'] = False
        (out / 'result.json').write_text(json.dumps(result, indent=2, default=str))
    print(json.dumps({'out': str(out.resolve()), 'passed': result['passed'], 'error': result.get('error'), 'cleanupErrors': result['cleanupErrors']}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    sys.exit(run(sys.argv[1] if len(sys.argv) > 1 else 'Base.Shotgun'))
