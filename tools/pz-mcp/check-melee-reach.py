"""Where a real swing with the session's temporary weapon (m9-session.py start Base.Katana) lands, by distance.

One frozen, full-health zombie is moved to each distance straight ahead of Steve; one ATTACK tap each. Reports whether PZ applied
the hit (pzWeapons.meleeApplied) and whether Minecraft's guard rejected it (meleeRangeRejected). The expected reach is
PZ MaxRange * MeleeReach.SCALE (+ margin). Only the temporary zombie is touched; it is removed afterwards.
  python check-melee-reach.py [d1 d2 ...]
"""
import json
import math
import sys
import time
import traceback
from pathlib import Path
import pzcore as p

DEFAULT = [1.5, 2.4, 2.8, 3.1, 3.6]


def run(distances):
    out = Path(__file__).parent / ('melee-reach-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    ses = json.loads((Path(__file__).parent / 'm9-session.json').read_text())
    st = p.state()
    assert st['paused'] and not st['input']['live'] and st['pzMirror']['status'] == 'in step'
    steve = st['steve']; yaw = math.radians(steve['yaw'])
    r = {'passed': False, 'weapon': ses['item'], 'samples': []}
    def save(): (out / 'result.json').write_text(json.dumps(r, indent=2))
    lua = lambda code: p.lua(code)['results'][0]
    held = lambda: lua('local w=getPlayer():getPrimaryHandItem(); return w and (w:getFullType()..":"..tostring(w:getMaxRange(getPlayer()))..":"..tostring(w:getMinRange())) or "nil"')
    def put(d):
        x, y = steve['x'] - math.sin(yaw) * d, steve['z'] + math.cos(yaw) * d
        lua(f'local z=_pzReachTarget; z:setX({x}); z:setY({y}); z:setHealth(100); z:setUseless(true); z:setFakeDead(false); z:setOnFloor(false); z:setKnockedDown(false); return 1')
        time.sleep(0.5)
    try:
        r['held'] = held()
        lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); '
            'local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local w=it:get(i); if w:getID()==_pzM9Fix:getID() then w:setCriticalChance(0) end end; return 1')
        x, y = steve['x'] - math.sin(yaw) * 5, steve['z'] + math.cos(yaw) * 5
        n = lua('local known={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do known[a:get(i)]=true end; '
                f'addZombiesInOutfit({math.floor(x)},{math.floor(y)},0,1,nil,50); a=getCell():getZombieList(); local n=0; '
                f'for i=0,a:size()-1 do local z=a:get(i); if not known[z] then z:setX({x}); z:setY({y}); z:setHealth(100); z:setUseless(true); _pzReachTarget=z; n=n+1 end end; return n')
        assert float(n) == 1
        time.sleep(1.0)
        for d in distances:
            for attempt in range(2):
                put(d)
                old = p.state()['pzWeapons']
                crawling = str(lua('return _pzReachTarget:isCrawling()')).lower() == 'true'
                p.send_input(['ATTACK'], 150, wait=True)
                time.sleep(1.3)
                new = p.state()['pzWeapons']
                health = float(lua('return _pzReachTarget:getHealth()'))
                hit = new['meleeApplied'] > old['meleeApplied']
                r['samples'].append({'distance': d, 'attempt': attempt, 'hit': hit, 'health': health, 'crawling': crawling,
                                     'rejected': new['meleeRangeRejected'] - old['meleeRangeRejected']}); save()
                if hit or d > 3.0: break     # a miss far out is the answer; a near miss may be a crawler: retry once
        r['passed'] = True
    except Exception as e:
        r['error'] = f'{type(e).__name__}: {e}'; r['traceback'] = traceback.format_exc()
    finally:
        try: p.send_input(release=True)
        except Exception: pass
        try: lua('if _pzReachTarget then _pzReachTarget:removeFromWorld(); _pzReachTarget:removeFromSquare(); _pzReachTarget=nil end; UIManager.getSpeedControls():SetCurrentGameSpeed(0); return 1')
        except Exception as e: r['cleanupError'] = str(e)
        save()
    print(json.dumps({'folder': str(out.resolve()), 'passed': r['passed'], 'held': r.get('held'), 'error': r.get('error')}))
    for s in r['samples']: print(' ', s)


if __name__ == '__main__':
    run([float(a) for a in sys.argv[1:]] or DEFAULT)
