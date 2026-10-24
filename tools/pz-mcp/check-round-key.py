"""R with a magazine in hand and 9mm rounds in the off-hand puts ONE round into the magazine, taken from the off-hand stack.

Fixture: one temporary Base.9mmClip (new PZ id) in the selected empty hotbar slot, three temporary rounds in the off-hand
(/pzoffhand). Checks: three R presses load 1, 2, 3 rounds natively, the off-hand stack goes 3 -> 2 -> 1 -> empty, the owner's own
round stack never changes, a fourth R with an empty off-hand changes nothing. Cleanup removes exactly the fixture magazine and
any fixture round left (PZ side only), then compares the whole inventory with the start.
"""
import json
import time
import traceback
from pathlib import Path
import pzcore as p

AMMO, MAG = 'Base.Bullets9mm', 'Base.9mmClip'


def run():
    out = Path(__file__).parent / ('round-key-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    gui = lambda: p.state()['minecraftGui']
    before = p.state()
    assert before['paused'] and before['pzMirror']['status'] == 'in step' and not before['input']['live']
    inv0 = list(gui()['inventory']); slot = gui()['selectedSlot']; off0 = p.state()['minecraftGui'].get('offhand')
    assert inv0[slot].startswith('0 '), 'the selected slot must be empty'
    r = {'passed': False, 'steps': [], 'before': inv0}
    def save(): (out / 'result.json').write_text(json.dumps(r, indent=2))
    def step(name, **d): r['steps'].append({'name': name, **d}); save()
    def lua(code): return p.lua(code)['results'][0]
    def loose(): return int(float(lua(f'return getPlayer():getInventory():getCountTypeRecurse("{AMMO}")')))
    def mag_rounds():
        v = lua('local m=_pzRkMag; if not m then return "none" end; return tostring(m:getCurrentAmmoCount())')
        return int(v) if v.lstrip('-').isdigit() else v
    def offhand():
        # the off-hand shows as the last entry of the whole-inventory list? read it through the server's view
        return lua('return "n/a"')
    option = str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    base_rounds = None
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false)')
        lua('_pzRkHeld={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_pzRkHeld,{z,z:isUseless()}); z:setUseless(true) end; return #_pzRkHeld')
        base_rounds = loose()
        known = lua('local o={}; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); '
                    f'if m:getFullType()=="{MAG}" then o[#o+1]=tostring(m:getID()) end end; return table.concat(o,",")')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        time.sleep(1.0)
        before_slots = list(gui()['inventory'])
        p.cmd(f'mc pzgive {MAG} 1')
        at = p.wait_for(lambda: next((i for i in range(36) if gui()['inventory'][i] != before_slots[i] and MAG in gui()['inventory'][i]), None), 10, .3, 'magazine')
        if at != slot:
            p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}')
            p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
        p.wait_for(lambda: ' whole' in gui()['inventory'][slot] or None, 20, .3, 'whole magazine')
        lua('local known={}; for id in string.gmatch("' + known + '", "[^,]+") do known[id]=true end; local it=getPlayer():getInventory():getItems(); '
            f'for i=0,it:size()-1 do local m=it:get(i); if m:getFullType()=="{MAG}" and not known[tostring(m:getID())] then _pzRkMag=m end end; assert(_pzRkMag); return 1')
        p.cmd(f'mc pzoffhand {AMMO} 3')
        p.wait_for(lambda: loose() == base_rounds + 3 or None, 15, .3, 'the off-hand rounds in PZ')
        time.sleep(1.5)
        assert mag_rounds() == 0
        for n in (1, 2, 3):
            b = p.state()['pzGuns']
            p.api('POST', '/gui', {'key': 82, 'action': 1}); p.api('POST', '/gui', {'key': 82, 'action': 0})
            p.wait_for(lambda: p.state()['pzGuns']['roundsLoaded'] == b['roundsLoaded'] + 1 and not p.state()['pzGuns']['loading'] or None, 10, .1, f'round {n}')
            time.sleep(1.2)
            step(f'R #{n}', magazine=mag_rounds(), looseRounds=loose(), status=p.state()['pzGuns']['last'].get('status'), mcMagazine=gui()['inventory'][slot])
            assert mag_rounds() == n and loose() == base_rounds + 3 - n, (mag_rounds(), loose())
        # the owner's own stack never changed: compare every Minecraft slot except the fixture magazine's
        mid = list(gui()['inventory'])
        assert all(mid[i] == inv0[i] for i in range(36) if i != slot), 'a slot other than the fixture magazine changed'
        # a fourth press with an empty off-hand does nothing
        b = p.state()['pzGuns']
        p.api('POST', '/gui', {'key': 82, 'action': 1}); p.api('POST', '/gui', {'key': 82, 'action': 0})
        time.sleep(1.2)
        a = p.state()['pzGuns']
        step('R #4 (empty off-hand)', roundsLoaded=a['roundsLoaded'], magazine=mag_rounds())
        assert a['roundsLoaded'] == b['roundsLoaded'] and mag_rounds() == 3 and loose() == base_rounds, (mag_rounds(), loose())
        r['passed'] = True
    except Exception as e:
        r['error'] = str(e); r['traceback'] = traceback.format_exc()
    finally:
        errors = []
        try: p.send_input(release=True)
        except Exception as e: errors.append('release: ' + str(e))
        try:
            lua('local inv=getPlayer():getInventory(); if _pzRkMag then inv:Remove(_pzRkMag) end; _pzRkMag=nil; return 1')
            if base_rounds is not None:
                # rounds that are still loose beyond the owner's own (should be none: the three went into the magazine)
                lua(f'local inv=getPlayer():getInventory(); local extra=inv:getCountTypeRecurse("{AMMO}")-{base_rounds}; for i=1,extra do inv:RemoveOneOf("{AMMO}", true) end; return 1')
            p.send_input_noop = None
        except Exception as e: errors.append('remove: ' + str(e))
        try:
            # the off-hand stack, if the test failed before it was used up, is removed by the PZ-side removal above (the mirror carries it over)
            p.wait_for(lambda: gui()['inventory'] == inv0 and p.state()['pzMirror']['status'] == 'in step' or None, 30, .4, 'the inventory to be restored')
        except Exception as e: errors.append('inventory: ' + str(e))
        try: lua('if _pzRkHeld then for _,v in ipairs(_pzRkHeld) do v[1]:setUseless(v[2]) end; _pzRkHeld=nil end; return 1')
        except Exception as e: errors.append('zombies: ' + str(e))
        try: p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        except Exception as e: errors.append('pause: ' + str(e))
        after = p.state()
        r['after'] = {'inventory': after['minecraftGui']['inventory'], 'paused': after['paused'], 'mirror': after['pzMirror']['status']}
        r['cleanupErrors'] = errors
        if errors or after['minecraftGui']['inventory'] != inv0: r['passed'] = False
        save()
    print(json.dumps({'folder': str(out.resolve()), 'passed': r['passed'], 'error': r.get('error'), 'cleanupErrors': r['cleanupErrors']}))
    raise SystemExit(0 if r['passed'] else 1)


if __name__ == '__main__':
    run()
