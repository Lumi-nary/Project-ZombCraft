"""Verify a real melee hit -> PZ condition roll -> Minecraft mirror update, in place.

Uses one temporary hammer and one frozen, nonlethal zombie. Requires a paused,
linked development session with an empty hotbar slot and no nearby zombies.
Preserves the owner's inventory, position, view, selected slot and pause state.
"""
import json
import math
import shutil
import time
from pathlib import Path
import pzcore as p


def run():
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = Path(__file__).parent / f'weapon-wear-{stamp}'
    out.mkdir()
    result = {'passed': False, 'samples': [], 'started': stamp}
    before = p.state()
    (out / 'before.json').write_text(json.dumps(before, indent=2))
    assert before['paused'] and not before['input']['live'] and not before['overrideActive'], 'manual input/session active'
    assert before['link']['ready'] and before['pzMirror']['status'] == 'in step'
    assert before['zombies'] == 0, 'nearby native zombies: choose a safe test session'
    inv = before['minecraftGui']['inventory']
    slot = next((i for i in range(9) if inv[i].startswith('0 ')), None)
    assert slot is not None
    original_slot = before['minecraftGui']['selectedSlot']
    steve = before['steve']
    yaw = math.radians(steve['yaw'])
    tx, tz = steve['x'] - math.sin(yaw) * 2, steve['z'] + math.cos(yaw) * 2
    option = p.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]
    option = str(option).lower()
    assert option in ('true', 'false')
    weapon_slot = None
    try:
        saved = p.save_worlds()
        result['savedBefore'] = saved
        assert 'PZ saved' in saved and 'Minecraft saved' in saved, saved
        backup = p.ROOT / '.pzcraft-backups' / f'before-codex-weapon-check-{stamp}'
        backup.mkdir()
        result['backup'] = str(backup)
        shutil.copytree(p.ZOMBOID / 'Saves/Apocalypse/PzCraftFeatureTest-20261002', backup / 'pz')
        shutil.copytree(p.ROOT / 'fabric-mod/run/saves/pzcraft', backup / 'mc', ignore=shutil.ignore_patterns('session.lock'))
        shutil.copy2(p.ROOT / 'HANDOFF.md', backup / 'HANDOFF.md')
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        time.sleep(.8)
        free = p.lua(f'local q=getCell():getGridSquare({math.floor(tx)},{math.floor(tz)},0); '
                     'return q ~= nil and not q:isSolid() and not q:isSolidTrans()')['results'][0]
        assert str(free).lower() == 'true', 'no clear square in front of Steve'
        p.cmd('mc pzgive Base.Hammer 1')
        p.wait_for(lambda: p.state()['minecraftGui']['inventory'] != inv or None, 8, .3, 'hammer')
        now = p.state()['minecraftGui']['inventory']
        at = next(i for i in range(36) if now[i] != inv[i])
        assert inv[at].startswith('0 ')
        weapon_slot = at
        if at != slot:
            p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}')
            p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
            weapon_slot = slot
        p._select_slot(slot)
        p.wait_for(lambda: (p.state()['pzMirror']['status'] == 'in step') or None, 30, .4, 'mirror')
        time.sleep(1.5)
        item_query = ("local a=getPlayer():getInventory():getItems(); for i=0,a:size()-1 do local w=a:get(i); "
                      "if w:getFullType()=='Base.Hammer' then return tostring(w:getID())..','..w:getCondition()..','.."
                      "w:getConditionMax()..','..w:getConditionLowerChance() end end; return 'missing'")
        item0 = p.lua(item_query)['results'][0]
        assert item0 != 'missing', 'hammer not mirrored into PZ'
        result['pzItemBefore'] = item0
        result['heldBefore'] = p.state()['minecraftGui']['held']
        made = p.lua('local known={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do known[a:get(i)]=true end; '
                     f'addZombiesInOutfit({math.floor(tx)},{math.floor(tz)},0,1,nil,50); '
                     'a=getCell():getZombieList(); local n=0; for i=0,a:size()-1 do local z=a:get(i); if not known[z] then '
                     f'z:setUseless(true); z:setHealth(100); z:setX({tx}); z:setY({tz}); '
                     '_pzCodexWearTarget=z; n=n+1 end end; return n')['results'][0]
        assert float(made) == 1, made
        time.sleep(1.5)
        baseline = p.state()['pzWeapons']
        misses = 0
        for i in range(100):
            p.lua(f'local z=_pzCodexWearTarget; z:setHealth(100); z:setX({tx}); z:setY({tz}); z:setUseless(true)')
            time.sleep(.2)
            rolls = p.state()['pzWeapons']['wearRolls']
            p.send_input(['ATTACK'], 150, wait=True)
            time.sleep(1.0)
            st = p.state()
            item = p.lua(item_query)['results'][0]
            sample = {'swing': i+1, 'pzItem': item, 'pzWeapons': st['pzWeapons'],
                      'held': st['minecraftGui']['held'], 'mirror': st['pzMirror'],
                      'targetHealth': p.lua('return _pzCodexWearTarget:getHealth()')['results'][0]}
            result['samples'].append(sample)
            (out / 'result.json').write_text(json.dumps(result, indent=2))
            misses = misses+1 if st['pzWeapons']['wearRolls'] == rolls else 0
            assert misses < 3, 'three swings did not reach the PZ wear roll'
            if i % 8 == 0:
                print(json.dumps({'swing': i+1, 'pzItem': item, 'wear': st['pzWeapons']}), flush=True)
            if st['pzWeapons']['worn'] > baseline['worn']:
                start_condition = int(str(item0).split(',')[1])
                final_condition = int(str(item).split(',')[1])
                max_condition = int(str(item).split(',')[2])
                assert final_condition < start_condition
                wanted = f'{final_condition}/{max_condition}'
                p.wait_for(lambda: p.state()['minecraftGui']['held']['durability'] == wanted or None,
                           15, .3, 'condition bar from PZ')
                result['heldAfter'] = p.state()['minecraftGui']['held']
                result['pzItemAfter'] = item
                result['passed'] = True
                break
        assert result['passed'], '100 real hits without a condition decrease: inconclusive wear probability'
    except Exception as e:
        result['error'] = repr(e)
    finally:
        errors = []
        for cleanup in [lambda: p.send_input(release=True),
                        lambda: p.lua('if _pzCodexWearTarget then _pzCodexWearTarget:removeFromWorld(); '
                                      '_pzCodexWearTarget:removeFromSquare(); _pzCodexWearTarget=nil end'),
                        lambda: p.cmd(f'mc item replace entity @s container.{weapon_slot} with minecraft:air') if weapon_slot is not None else None,
                        lambda: p._select_slot(original_slot),
                        lambda: p.wait_for(lambda: (p.state()['minecraftGui']['inventory'] == inv and
                                                    p.state()['pzMirror']['status'] == 'in step') or None, 20, .4, 'inventory cleanup'),
                        lambda: p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss('+option+')')]:
            try:
                cleanup()
            except Exception as e:
                errors.append(repr(e))
        final = p.state()
        result['cleanupErrors'] = errors
        result['inventoryPreserved'] = final['minecraftGui']['inventory'] == inv
        result['slotRestored'] = final['minecraftGui']['selectedSlot'] == original_slot
        result['positionPreserved'] = all(abs(final['steve'][axis]-steve[axis]) < .05 for axis in ('x', 'y', 'z'))
        result['paused'] = final['paused']
        result['passed'] = result['passed'] and not errors and result['inventoryPreserved'] and result['positionPreserved'] and result['slotRestored']
        (out / 'after.json').write_text(json.dumps(final, indent=2))
        (out / 'result.json').write_text(json.dumps(result, indent=2))
        print(json.dumps({k:v for k,v in result.items() if k != 'samples'}, indent=2), flush=True)
    raise SystemExit(0 if result['passed'] else 1)


if __name__ == '__main__':
    run()
