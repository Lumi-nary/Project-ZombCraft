"""Magazine loading and the mirror's ammo state, through the real input path. Only temporary items are touched.

1  R with a temporary Base.9mmClip in hand loads off-hand Base.Bullets9mm natively (one per press, PZ's own
   items): 15 rounds in the magazine, the rest stay loose; the Minecraft stack shows "r=15" after the mirror syncs;
2  shift+R unloads it; changing the hotbar slot stops the work part-way and no round is lost or made;
3  with a full magazine and a temporary pistol: R loads the pistol natively (the magazine leaves the inventory), one shot
   (camera pointed at the sky: nothing is hit) uses one round, and the Minecraft stack of the pistol follows ("r=14+1", "r=13+1").
Cleanup removes exactly the fixture items by PZ id, restores look, selected slot, zombie flags and the pause.
"""
import json
import time
import traceback
from pathlib import Path
import pzcore as p

AMMO = 'Base.Bullets9mm'
MAG = 'Base.9mmClip'


def run():
    out = Path(__file__).parent / ('magazine-' + time.strftime('%Y%m%d-%H%M%S'))
    out.mkdir()
    before = p.state()
    assert before['paused'] and not before['input']['live'] and not before['overrideActive']
    assert before['pzMirror']['status'] == 'in step'
    g0 = before['minecraftGui']
    inv0 = list(g0['inventory'])
    off0 = g0.get('offhand')
    assert not off0 or off0.startswith('0 '), 'fixture needs an empty off-hand'
    slot = next(i for i in range(9) if inv0[i].startswith('0 '))
    option = str(p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]).lower()
    r = {'passed': False, 'before': {'inventory': inv0, 'slot': g0['selectedSlot'], 'look': {'yaw': before['steve']['yaw'], 'pitch': before['steve']['pitch']}}, 'steps': []}

    def save():
        (out / 'result.json').write_text(json.dumps(r, indent=2))

    def gui():
        return p.state()['minecraftGui']

    def step(name, **data):
        r['steps'].append({'name': name, **data})
        save()

    def lua(code):
        return p.lua(code)['results'][0]

    def mag():     # (ammo) of the fixture magazine, or None
        v = lua('local m=_pzMagFix; if not m then return "none" end; local inv=getPlayer():getInventory(); '
                'if not inv:contains(m) then return "gone" end; return tostring(m:getCurrentAmmoCount())')
        return int(v) if v.lstrip('-').isdigit() else v

    def loose():
        return int(float(lua(f'return getPlayer():getInventory():getCountTypeRecurse("{AMMO}")')))

    def mc_stack(contains):
        return next((s for s in gui()['inventory'] if contains in s), None)

    def settle(what='the mirror to settle'):
        p.wait_for(lambda: p.state()['pzMirror']['status'] == 'in step' and p.state()['pzMirror']['pending'] == 0 or None, 30, .4, what)

    def last_status():
        return p.state()['pzGuns']['last'].get('status', '')

    def give(item, count, expect_in_slot=None):
        inv = list(gui()['inventory'])
        p.cmd(f'mc pzgive {item} {count}')
        # the slot whose new content names what was given (another slot may change at the same moment: a magazine's rounds)
        def landed():
            now = gui()['inventory']
            return next((i for i in range(36) if now[i] != inv[i] and item in now[i]), None)
        at = p.wait_for(lambda: landed(), 10, .3, item)
        if expect_in_slot is not None and at != expect_in_slot:
            p.cmd(f'mc item replace entity @s hotbar.{expect_in_slot} from entity @s container.{at}')
            p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
            at = expect_in_slot
        return at

    def press_r(sneak=False):
        if sneak:
            p.send_input(['SNEAK'], 400, wait=False)
            time.sleep(.12)
        p.api('POST', '/gui', {'key': 82, 'action': 1})
        p.api('POST', '/gui', {'key': 82, 'action': 0})
        if sneak: p.send_input(release=True)

    def load_full():
        for expected in range(mag() + 1, 16):
            base = p.state()['pzGuns']['roundsLoaded']
            press_r()
            p.wait_for(lambda: mag() == expected and p.state()['pzGuns']['roundsLoaded'] == base + 1 and not p.state()['pzGuns']['loading'] or None, 10, .1, f'R round {expected}')
            settle()

    loose0 = None      # the owner's own rounds, once known
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false)')
        # nothing may touch Steve while the world runs
        lua('_pzMagHeld={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
            'table.insert(_pzMagHeld,{z,z:isUseless()}); z:setUseless(true) end; return #_pzMagHeld')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        r['looseBefore'] = loose0 = loose()      # the owner's own rounds: left exactly as they are
        r['magazinesBefore'] = lua('local o={}; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); '
                                   f'if m:getFullType()=="{MAG}" then o[#o+1]=tostring(m:getID()) end end; return table.concat(o,",")')
        r['pistolsBefore'] = lua('local o={}; local it=getPlayer():getInventory():getItems(); for i=0,it:size()-1 do local m=it:get(i); '
                                 'if m:getFullType()=="Base.Pistol" then o[#o+1]=tostring(m:getID()) end end; return table.concat(o,",")')

        # ---- 1: load by R/off-hand ----
        give(MAG, 1, slot)
        p.cmd(f'mc pzoffhand {AMMO} 20')
        settle('PZ to hold the fixture')
        time.sleep(1.5)
        # the fixture's magazine is the one whose id was not there before
        lua('local known={}; for id in string.gmatch("' + r['magazinesBefore'] + '", "[^,]+") do known[id]=true end; '
            'local inv=getPlayer():getInventory():getItems(); for i=0,inv:size()-1 do local m=inv:get(i); '
            f'if m:getFullType()=="{MAG}" and not known[tostring(m:getID())] then _pzMagFix=m end end; assert(_pzMagFix, "no new magazine in PZ"); return _pzMagFix:getID()')
        total = loose0 + 20
        assert mag() == 0 and loose() == total, (mag(), loose())
        p._select_slot(slot)
        p.wait_for(lambda: ' whole' in gui()['inventory'][slot] or None, 20, .3, 'the fixture magazine to become a whole item')
        base = p.state()['pzGuns']
        p.send_input(['USE'], 150, wait=True)
        time.sleep(.8)
        assert mag() == 0 and loose() == total, 'right-click must not load a magazine'
        step('right-click magazine does nothing')
        load_full()
        s = p.state()['pzGuns']
        step('R/off-hand loads', status=s['last'], loaded=s['roundsLoaded'] - base['roundsLoaded'], magazine=mag(), loose=loose())
        assert mag() == 15 and loose() == total - 15, (mag(), loose())
        assert s['roundsLoaded'] - base['roundsLoaded'] == 15 and s['failures'] == 0
        settle()
        p.wait_for(lambda: ' r=15' in gui()['inventory'][slot] or None, 10, .3, 'the Minecraft stack to show 15 rounds')
        step('minecraft shows the rounds', stack=gui()['inventory'][slot])

        # ---- 2: unload part-way, then swap away: nothing is lost ----
        press_r(sneak=True)
        p.wait_for(lambda: p.state()['pzGuns']['loading'] or None, 6, .1, 'unloading to start')
        time.sleep(2.2)
        p._select_slot((slot + 1) % 9 if (slot + 1) % 9 != slot else 0)
        p.wait_for(lambda: not p.state()['pzGuns']['loading'] or None, 6, .1, 'the swap to stop the work')
        part = mag()
        step('swap stops unloading', magazine=part, loose=loose(), status=last_status())
        assert 1 <= part <= 14, f'unloading did not stop part-way: {part}'
        assert part + loose() == total, 'a round was lost or made'
        p._select_slot(slot)
        time.sleep(.4)
        press_r(sneak=True)
        p.wait_for(lambda: last_status().startswith('unloaded') or None, 40, .2, 'the magazine to finish unloading')
        assert mag() == 0 and loose() == total, (mag(), loose())
        step('unloaded', magazine=mag(), loose=loose())

        # ---- 3: a full magazine reloads the pistol; the mirror follows the rounds ----
        load_full()
        assert mag() == 15
        settle()
        look0 = {'yaw': p.state()['steve']['yaw'], 'pitch': p.state()['steve']['pitch']}
        pistol_slot = slot
        magazine_at = next(i for i in range(9, 36) if gui()['inventory'][i].startswith('0 '))
        p.cmd(f'mc item replace entity @s container.{magazine_at} from entity @s hotbar.{slot}')
        p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
        settle()
        give('Base.Pistol', 1, pistol_slot)
        settle()
        time.sleep(1.5)
        lua('local known={}; for id in string.gmatch("' + r['pistolsBefore'] + '", "[^,]+") do known[id]=true end; '
            'local inv=getPlayer():getInventory():getItems(); for i=0,inv:size()-1 do local w=inv:get(i); '
            'if w:getFullType()=="Base.Pistol" and not known[tostring(w:getID())] then _pzMagPistol=w; w:setJamGunChance(0) end end; '
            'assert(_pzMagPistol, "no new pistol in PZ"); return 1')
        p.wait_for(lambda: ' whole' in gui()['inventory'][pistol_slot] or None, 20, .3, 'the fixture pistol to become a whole item')
        p._select_slot(pistol_slot)
        p.send_input(look={'yaw': look0['yaw'], 'pitch': -70, 'relative': False}, wait=True)   # the sky: nothing to hit
        time.sleep(.6)
        p.api('POST', '/gui', {'key': 82, 'action': 1})
        p.api('POST', '/gui', {'key': 82, 'action': 0})
        try:
            p.wait_for(lambda: last_status() == 'reloaded' or None, 15, .1, 'the pistol reload')
        except Exception:
            st = p.state()
            r['diagnostic'] = {'held': st['minecraftGui'].get('held'), 'selectedSlot': st['minecraftGui']['selectedSlot'], 'pistolSlot': pistol_slot,
                               'pistolStack': gui()['inventory'][pistol_slot], 'pzGuns': st['pzGuns'], 'input': st['input'], 'paused': st['paused'],
                               'gunState': st['minecraftGui'].get('gun')}
            raise
        ammo = lua('local w=_pzMagPistol; return w:getCurrentAmmoCount()..","..tostring(w:isRoundChambered())..","..tostring(w:isContainsClip())')
        step('pistol reloaded from the loaded magazine', native=ammo, magazineLeft=mag())
        assert ammo == '14,true,true', ammo
        assert mag() == 'gone', 'the magazine should have gone into the pistol'
        settle()
        p.wait_for(lambda: ' r=14+1' in gui()['inventory'][pistol_slot] or None, 10, .3, 'the pistol stack to show 14+1')
        step('minecraft shows the pistol rounds', stack=gui()['inventory'][pistol_slot])
        shots = p.state()['pzGuns']['shots']
        p.send_input(['ATTACK'], 150, wait=True)
        p.wait_for(lambda: p.state()['pzGuns']['shots'] == shots + 1 or None, 6, .1, 'the shot')
        settle()
        p.wait_for(lambda: ' r=13+1' in gui()['inventory'][pistol_slot] or None, 10, .3, 'the pistol stack to show 13+1')
        step('minecraft follows the shot', stack=gui()['inventory'][pistol_slot], native=lua('local w=_pzMagPistol; return w:getCurrentAmmoCount()..","..tostring(w:isRoundChambered())'))
        r['passed'] = True
    except Exception as e:
        r['error'] = str(e)
        r['traceback'] = traceback.format_exc()
    finally:
        errors = []
        try:
            p.send_input(release=True)
        except Exception as e:
            errors.append('release: ' + str(e))
        try:
            lua('local inv=getPlayer():getInventory(); '
                'if _pzMagFix then inv:Remove(_pzMagFix) end; if _pzMagPistol then inv:Remove(_pzMagPistol) end; '
                + (f'local extra=inv:getCountTypeRecurse("{AMMO}")-{loose0}; for i=1,extra do inv:RemoveOneOf("{AMMO}", true) end; ' if loose0 is not None else '') +
                '_pzMagFix=nil; _pzMagPistol=nil; return 1')
        except Exception as e:
            errors.append('remove items: ' + str(e))
        try:
            # One side only: PZ removed exactly the fixture's items above and the mirror carries that to Minecraft. Clearing
            # Minecraft slots as well would remove the same items twice (a removal on either side reaches the other).
            p.wait_for(lambda: gui()['inventory'] == inv0 and gui().get('offhand') == off0 and p.state()['pzMirror']['status'] == 'in step' or None, 30, .4, 'the inventory to be restored')
        except Exception as e:
            errors.append('inventory: ' + str(e))
        try:
            p.send_input(look={'yaw': r['before']['look']['yaw'], 'pitch': r['before']['look']['pitch'], 'relative': False}, wait=True)
            p._select_slot(r['before']['slot'])
        except Exception as e:
            errors.append('look/slot: ' + str(e))
        try:
            lua('if _pzMagHeld then for _,v in ipairs(_pzMagHeld) do v[1]:setUseless(v[2]) end; _pzMagHeld=nil end; return 1')
        except Exception as e:
            errors.append('zombie flags: ' + str(e))
        try:
            p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        except Exception as e:
            errors.append('pause: ' + str(e))
        after = p.state()
        r['after'] = {'inventory': after['minecraftGui']['inventory'], 'slot': after['minecraftGui']['selectedSlot'], 'paused': after['paused'],
                      'mirror': after['pzMirror']['status'], 'pzGuns': after['pzGuns']}
        r['cleanupErrors'] = errors
        if errors or after['minecraftGui']['inventory'] != inv0 or after['minecraftGui'].get('offhand') != off0:
            r['passed'] = False
        save()
    print(json.dumps({'folder': str(out.resolve()), 'passed': r['passed'], 'error': r.get('error'), 'cleanupErrors': r['cleanupErrors']}))
    raise SystemExit(0 if r['passed'] else 1)


if __name__ == '__main__':
    run()
