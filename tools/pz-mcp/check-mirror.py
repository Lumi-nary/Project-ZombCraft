"""0.14 unified inventory, step H4: the hidden PZ inventory mirrors Steve's Minecraft inventory, and PZ-side changes come back.

Checks, through the control API only (no desktop focus), in the backed-up PzCraftFeatureTest-20261002 save with Steve driving:
  A. projection: every PZ item Steve carries is in the PZ pockets (same type counts), every Minecraft-only stack is one stand-in
     item (PzCraft.MCItem) with the right count, and the PZ items that were only in the pockets before (ID card, key ring with its
     key) are now also in Steve's inventory;
  B. quiet: nothing changes on its own (no transactions, no slot moves);
  C. Minecraft -> PZ: /give a diamond (stand-in appears, then goes), /pzgive an apple (a PZ item appears, then goes);
  D. PZ -> Minecraft: an item added to the pockets from PZ's side reaches Steve's inventory in an empty slot and leaves it again when
     removed in PZ; an item put into the key ring in PZ changes Steve's key ring in its own slot, not in a new one;
  E. at the end both inventories are what they were before the test and neither bridge reports a failure.

Only creates and removes what it creates itself: never clears Steve's slots or the pockets. Needs a game that is linked with Steve
driving and the mirror in step (pzMirror.status = "in step").
"""
import collections
import json
import re
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent


def gui():
    return p.state()['minecraftGui']


def mc_slots():
    return list(gui()['inventory'])


def mc_all():
    g = gui()
    return list(g['inventory']) + list(g['worn'])


def split(stack):
    m = re.match(r'(\d+) (pz:)?(\S+)', stack)
    return (int(m.group(1)), bool(m.group(2)), m.group(3)) if m else (0, False, '')


def mc_totals():
    """({'Base.Apple': 3}, {'minecraft:tnt': 59}) over everything Steve carries (main slots and worn)."""
    pz, mc = collections.Counter(), collections.Counter()
    for s in mc_all():
        n, is_pz, t = split(s)
        if n and (is_pz or not t.startswith('minecraft:air')):
            (pz if is_pz else mc)[t] += n
    mc.pop('minecraft:air', None)
    return pz, mc


POCKETS = r"""
local out = {}
local items = getPlayer():getInventory():getItems()
for i = 0, items:size()-1 do
  local it = items:get(i)
  local line = it:getFullType() .. '|' .. it:getID()
  local md = it:hasModData() and it:getModData().pzcraftMc or nil
  if md then line = line .. '|' .. tostring(md.n) .. '|' .. tostring(md.snbt) else line = line .. '||' end
  if instanceof(it, 'InventoryContainer') then
    local inner = {}
    local ii = it:getInventory():getItems()
    for k = 0, ii:size()-1 do inner[#inner+1] = ii:get(k):getFullType() end
    line = line .. '|' .. table.concat(inner, ',')
  else line = line .. '|' end
  out[#out+1] = line
end
return table.concat(out, '\n')
"""


def pockets():
    """[(type, id, standInCount, standInText, innerTypes)] for what the hidden PZ character carries."""
    text = p.lua(POCKETS)['results'][0]
    rows = []
    for line in text.split('\n'):
        if not line:
            continue
        t, i, n, snbt, inner = (line.split('|', 4) + [''] * 5)[:5]
        rows.append((t, int(i), int(float(n)) if n else 0, snbt, [x for x in inner.split(',') if x]))
    return rows


def pocket_totals(rows):
    pz, mc = collections.Counter(), collections.Counter()
    for t, _i, n, snbt, _inner in rows:
        if t == 'PzCraft.MCItem':
            m = re.search(r'id:"(minecraft:[^"]+)"', snbt)
            mc[m.group(1) if m else '?'] += n
        else:
            pz[t] += 1
    return pz, mc


def wait(pred, timeout, what, every=0.3):
    return p.wait_for(lambda: pred() or None, timeout, every, what)


def stats():
    s = p.state()
    return s['pzMirror'], s['minecraftGui']['mirror']


def in_step():
    pz, _ = stats()
    return pz['active'] and pz['status'] == 'in step' and pz['pending'] == 0


def run():
    result = {'passed': False}
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    created = []   # undo actions for everything this test makes
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        wait(in_step, 60, 'the mirror to be in step')
        time.sleep(1.0)
        pzs, mcs = stats()
        result['stats0'] = {'pz': pzs, 'mc': mcs}

        # ---- A. projection ----
        mc_pz, mc_mc = mc_totals()
        rows = pockets()
        pk_pz, pk_mc = pocket_totals(rows)
        result['A'] = {'mcPz': dict(mc_pz), 'pocketsPz': dict(pk_pz), 'mcOnly': dict(mc_mc), 'standIns': dict(pk_mc)}
        assert pk_pz == mc_pz, f'PZ pockets hold {dict(pk_pz)} but Steve carries {dict(mc_pz)}'
        assert pk_mc == mc_mc, f'stand-ins {dict(pk_mc)} do not match Steve\'s Minecraft-only stacks {dict(mc_mc)}'
        assert mc_pz['Base.IDcard'] >= 1 and mc_pz['Base.KeyRing'] >= 1, 'the ID card and key ring never reached Steve'
        ring = next(r for r in rows if r[0] == 'Base.KeyRing')
        assert 'Base.Key1' in ring[4], f'the key ring lost its key: {ring}'
        slots_after_sync = mc_slots()
        pockets_after_sync = collections.Counter((r[0], r[2]) for r in rows)

        # ---- B. quiet ----
        sent0, tx0 = stats()[0]['txSent'], stats()[1]['transactions']
        time.sleep(6)
        sent1, tx1 = stats()[0]['txSent'], stats()[1]['transactions']
        result['B'] = {'txSent': [sent0, sent1], 'applied': [tx0, tx1]}
        assert sent1 == sent0 and tx1 == tx0, 'the mirror sent changes nobody made'
        assert mc_slots() == slots_after_sync, 'Steve\'s slots moved on their own'

        # ---- C. Minecraft -> PZ ----
        assert mc_mc['minecraft:diamond'] == 0, 'Steve already carries diamonds: refusing to touch them'
        p.cmd('mc give @s minecraft:diamond 3')
        created.append(lambda: p.cmd('mc clear @s minecraft:diamond 3'))
        wait(lambda: pocket_totals(pockets())[1]['minecraft:diamond'] == 3, 20, 'a diamond stand-in in the pockets')
        p.cmd('mc clear @s minecraft:diamond 3')
        created.pop()
        wait(lambda: pocket_totals(pockets())[1]['minecraft:diamond'] == 0, 20, 'the diamond stand-in to go')
        assert not any(r[0] == 'PzCraft.MCItem' and 'diamond' in r[3] for r in pockets())
        result['C1'] = 'diamond stand-in appeared with 3 and went'

        apples_before = pocket_totals(pockets())[0]['Base.Apple']
        before = mc_slots()
        assert not any(re.fullmatch(r'\d+ pz:Base\.Apple', s) for s in before), 'Steve carries a plain apple: it would merge'
        p.cmd('mc pzgive Base.Apple 2')
        wait(lambda: mc_slots() != before, 6, 'the apples in the inventory')
        new_slot = next(i for i, (a, b) in enumerate(zip(before, mc_slots())) if a != b)
        created.append(lambda: p.cmd(f'mc item replace entity @s container.{new_slot} with minecraft:air'))
        wait(lambda: pocket_totals(pockets())[0]['Base.Apple'] == apples_before + 2, 20, 'two apples in the pockets')
        p.cmd(f'mc item replace entity @s container.{new_slot} with minecraft:air')
        created.pop()
        wait(lambda: pocket_totals(pockets())[0]['Base.Apple'] == apples_before, 20, 'the apples to leave the pockets')
        result['C2'] = 'two given apples appeared in PZ and went'

        # ---- D. PZ -> Minecraft ----
        before = mc_slots()
        assert not any(re.fullmatch(r'\d+ pz:Base\.Hammer( whole)?', s) for s in before), 'Steve carries a plain hammer: it would merge'
        # (undo first: Steve carries no hammer, so removing every Base.Hammer from the pockets removes only this one; the id is
        # not read back, Kahlua prints large integers in exponent form)
        drop_hammer = ("local inv = getPlayer():getInventory(); for i = inv:getItems():size()-1, 0, -1 do local it = inv:getItems():get(i); "
                       "if it:getFullType() == 'Base.Hammer' then inv:Remove(it) end end")
        created.append(lambda: p.lua(drop_hammer))
        p.lua("getPlayer():getInventory():AddItem(instanceItem('Base.Hammer'))")
        wait(lambda: any(re.fullmatch(r'1 pz:Base\.Hammer( whole)?', s) for s in mc_slots()), 20, 'the hammer to reach Steve')
        now = mc_slots()
        changed = [i for i in range(36) if now[i] != before[i]]
        assert len(changed) == 1 and before[changed[0]].startswith('0 '), f'the hammer should take one empty slot, slots changed: {changed}'
        result['D1'] = {'slot': changed[0]}
        p.lua(drop_hammer)
        created.pop()
        wait(lambda: mc_slots() == before, 20, 'the hammer to leave Steve again')

        ring_slot = next(i for i, s in enumerate(mc_slots()) if 'pz:Base.KeyRing' in s)
        changed_before = stats()[1]['changed']
        p.lua("local inv = getPlayer():getInventory(); for i=0,inv:getItems():size()-1 do local it=inv:getItems():get(i); "
              "if it:getFullType()=='Base.KeyRing' then it:getInventory():AddItem('Base.Apple'); return 'ok' end end")
        created.append(lambda: p.lua("local inv = getPlayer():getInventory(); for i=0,inv:getItems():size()-1 do local it=inv:getItems():get(i); "
                                     "if it:getFullType()=='Base.KeyRing' then local a = it:getInventory():getFirstType('Base.Apple'); if a then it:getInventory():Remove(a) end end end"))
        wait(lambda: stats()[1]['changed'] > changed_before, 20, 'Minecraft to change the key ring in place')
        assert 'pz:Base.KeyRing' in mc_slots()[ring_slot], 'the key ring left its slot'
        wait(in_step, 20, 'the mirror to settle')
        p.lua("local inv = getPlayer():getInventory(); for i=0,inv:getItems():size()-1 do local it=inv:getItems():get(i); "
              "if it:getFullType()=='Base.KeyRing' then local a = it:getInventory():getFirstType('Base.Apple'); it:getInventory():Remove(a) end end")
        created.pop()
        changed_mid = stats()[1]['changed']
        wait(lambda: stats()[1]['changed'] > changed_mid, 20, 'Minecraft to change the key ring back')
        assert 'pz:Base.KeyRing' in mc_slots()[ring_slot], 'the key ring moved'
        result['D2'] = {'ringSlot': ring_slot, 'changedCount': stats()[1]['changed']}

        # ---- F. a duplicate that exists for a moment must destroy nothing ----
        # (a stack copied in Minecraft has the same PZ item id as the original; PZ cannot hold both. This once deleted the original.)
        assert not any('pz:Base.Jacket_Police' in s for s in mc_all()), 'Steve already carries a jacket: refusing to touch it'
        p.cmd('mc pzgive Base.Jacket_Police 1')
        created.append(lambda: p.lua("local inv = getPlayer():getInventory(); for i = inv:getItems():size()-1, 0, -1 do local it = inv:getItems():get(i); "
                                     "if it:getFullType() == 'Base.Jacket_Police' then inv:Remove(it) end end"))
        wait(lambda: any(s == '1 pz:Base.Jacket_Police whole' for s in mc_slots()), 20, 'the jacket to settle as a whole item')
        wait(in_step, 20, 'the mirror to settle')
        jacket = next(i for i, s in enumerate(mc_slots()) if s == '1 pz:Base.Jacket_Police whole')
        spare = next(i for i in range(35, -1, -1) if mc_slots()[i].startswith('0 '))
        created.append(lambda: p.cmd(f'mc item replace entity @s container.{spare} with minecraft:air'))
        p.cmd(f'mc item replace entity @s container.{spare} from entity @s container.{jacket}')   # now there are two
        time.sleep(2.5)                                                                           # long enough for the mirror to see both
        p.cmd(f'mc item replace entity @s container.{spare} with minecraft:air')                  # and one again
        created.pop()
        wait(in_step, 30, 'the mirror to settle after the duplicate')
        time.sleep(1.5)
        in_mc = sum(1 for s in mc_all() if s == '1 pz:Base.Jacket_Police whole')
        in_pz = sum(1 for r in pockets() if r[0] == 'Base.Jacket_Police')
        result['F'] = {'jacketsInMinecraft': in_mc, 'jacketsInPockets': in_pz, 'jacketSlot': jacket}
        assert in_mc == 1 and in_pz == 1, f'the duplicate window destroyed or duplicated the jacket: minecraft {in_mc}, pockets {in_pz}'
        assert mc_slots()[jacket] == '1 pz:Base.Jacket_Police whole', 'the jacket left its slot'
        p.cmd(f'mc item replace entity @s container.{jacket} with minecraft:air')   # the test's own jacket goes again
        created.pop()
        wait(lambda: not any('Jacket_Police' in s for s in mc_all()) and not any(r[0] == 'Base.Jacket_Police' for r in pockets()), 20, 'the test jacket to go from both')

        # ---- E. as before ----
        wait(in_step, 30, 'the mirror to settle at the end')
        time.sleep(1.0)
        assert mc_slots() == slots_after_sync, f'Steve\'s inventory is not as after the first sync:\n{slots_after_sync}\n{mc_slots()}'
        rows = pockets()
        assert collections.Counter((r[0], r[2]) for r in rows) == pockets_after_sync, 'the pockets are not as after the first sync'
        pzs, mcs = stats()
        result['stats1'] = {'pz': pzs, 'mc': mcs}
        assert pzs['failures'] == 0 and mcs['failures'] == 0, (pzs, mcs)
        result['passed'] = True
    finally:
        for undo in reversed(created):
            try:
                undo()
            except Exception as e:
                result.setdefault('cleanupErrors', []).append(str(e))
        time.sleep(2)
        try:
            result['end'] = {'pz': stats()[0], 'mc': stats()[1]}
        except Exception:
            pass
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        stamp = time.strftime('%Y%m%d-%H%M%S')
        result['finished'] = stamp
        text = json.dumps(result, indent=2)
        (OUT / 'mirror-results.json').write_text(text)
        (OUT / f'mirror-results-{stamp}.json').write_text(text)
    print(json.dumps(result, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    run()
