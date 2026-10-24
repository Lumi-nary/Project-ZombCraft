"""0.14 unified inventory, step H2: PZ containers open as Minecraft chest screens and only the difference goes back.

Finds a PZ container (--kind object: a fridge, a crate...; corpse: a dead zombie; floor: items lying on a tile) with items
near Steve, stands him beside it, opens it with the use key, takes two stacks out with shift-click, closes the screen and
checks that PZ lost exactly those items; then opens it again, puts them back and checks that PZ is as it was. Also checks
that a non-PZ item (cobblestone) will not go into a PZ container.

--state additionally round-trips items with a condition, an age, uses left and a custom name; --blobs round-trips a bag
with things in it, a full water bottle and a bullet-proof vest, which travel as the bytes PZ saves them as.

Only use the backed-up PzCraftFeatureTest-20261002 save. Uses the control API (no desktop focus needed).
"""
import argparse
import collections
import json
import math
import re
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent

COLLECT = r'''
local function collect(kind, X, Y, I)
  local sq = getCell():getGridSquare(X, Y, 0)
  local g = {}
  if kind == 'object' then
    local o = sq:getObjects():get(I)
    for c = 0, o:getContainerCount()-1 do
      local list = {}; local items = o:getContainerByIndex(c):getItems()
      for k = 0, items:size()-1 do list[#list+1] = items:get(k) end
      g[#g+1] = list
    end
  elseif kind == 'corpse' then
    local list = {}; local items = sq:getDeadBodys():get(I):getContainer():getItems()
    for k = 0, items:size()-1 do list[#list+1] = items:get(k) end
    g[1] = list
  else
    local list = {}; local wo = sq:getWorldObjects()
    for k = 0, wo:size()-1 do list[#list+1] = wo:get(k):getItem() end
    g[1] = list
  end
  return g
end
'''

FIND = COLLECT + r'''
local KIND, R, MIN, NC = '%s', %d, %d, %d
local pl = getPlayer()
local px, py = math.floor(pl:getX()), math.floor(pl:getY())
local best, bestd = nil, 1e9
local dirs = {{0,1},{0,-1},{1,0},{-1,0}}
local function consider(sq, i, dx, dy)
  local g = collect(KIND, sq:getX(), sq:getY(), i)
  if #g < NC then return end
  local total = 0
  for _, list in ipairs(g) do total = total + #list end
  local d = dx*dx + dy*dy
  if total < MIN or d >= bestd then return end
  for k, dir in ipairs(dirs) do
    local nsq = getCell():getGridSquare(sq:getX()+dir[1], sq:getY()+dir[2], 0)
    if nsq and nsq:getFloor() and nsq:isFree(false) and not nsq:isBlockedTo(sq) and not sq:isBlockedTo(nsq) then
      best = sq:getX()..','..sq:getY()..','..i..','..#g..','..total..','..k
      bestd = d
      return
    end
  end
end
for dx = -R, R do for dy = -R, R do
  local sq = getCell():getGridSquare(px+dx, py+dy, 0)
  if sq then
    if KIND == 'object' then
      local objs = sq:getObjects()
      for i = 0, objs:size()-1 do if objs:get(i):getContainerCount() > 0 then consider(sq, i, dx, dy) end end
    elseif KIND == 'corpse' then
      for i = 0, sq:getDeadBodys():size()-1 do consider(sq, i, dx, dy) end
    elseif sq:getWorldObjects():size() > 0 then
      consider(sq, 0, dx, dy)
    end
  end
end end
return best or 'none'
'''

DUMP = COLLECT + r'''
local g = collect('%s', %d, %d, %d)
local out = {}
for c, list in ipairs(g) do for _, it in ipairs(list) do out[#out+1] = (c-1) .. ':' .. it:getFullType() end end
table.sort(out)
return table.concat(out, ',')
'''

SEED_STATE = r"""
local sq = getCell():getGridSquare(%d, %d, 0)
local c = sq:getObjects():get(%d):getContainerByIndex(0)
local h = instanceItem('Base.Hammer'); h:setCondition(3); h:setName('My Hammer'); h:setCustomName(true); c:AddItem(h)
local a = instanceItem('Base.Apple'); a:setAge(1.5); c:AddItem(a)
local l = instanceItem('Base.Lighter'); l:setCurrentUsesFloat(0.4); c:AddItem(l)
return 'seeded'
"""

DETAIL_STATE = r"""
local sq = getCell():getGridSquare(%d, %d, 0)
local o = sq:getObjects():get(%d)
local out = {}
for c = 0, o:getContainerCount()-1 do
  local items = o:getContainerByIndex(c):getItems()
  for k = 0, items:size()-1 do
    local it = items:get(k)
    local t = it:getFullType()
    if t == 'Base.Hammer' or t == 'Base.Apple' or t == 'Base.Lighter' then
      local uses = ''
      if instanceof(it, 'DrainableComboItem') then uses = string.format('%%.3f', it:getCurrentUsesFloat()) end
      out[#out+1] = t .. '|' .. it:getCondition() .. '|' .. string.format('%%.3f', it:getAge()) .. '|' .. uses .. '|' .. tostring(it:getDisplayName())
    end
  end
end
table.sort(out)
return table.concat(out, ';')
"""

SEED_BLOBS = r"""
local sq = getCell():getGridSquare(%d, %d, 0)
local c = sq:getObjects():get(%d):getContainerByIndex(0)
local bag = instanceItem('Base.Bag_Schoolbag'); bag:getInventory():AddItem('Base.Apple'); bag:getInventory():AddItem('Base.Hammer'); bag:getInventory():AddItem('Base.Hammer')
c:AddItem(bag)
local w = instanceItem('Base.WaterBottle')
local fc = w:getFluidContainer()
if fc then pcall(function() fc:adjustAmount(0.4) end) end
c:AddItem(w)
local v = instanceItem('Base.Vest_BulletPolice'); v:setCondition(5); c:AddItem(v)
return 'seeded'
"""

DETAIL_BLOBS = r"""
local sq = getCell():getGridSquare(%d, %d, 0)
local o = sq:getObjects():get(%d)
local out = {}
for c = 0, o:getContainerCount()-1 do
  local items = o:getContainerByIndex(c):getItems()
  for k = 0, items:size()-1 do
    local it = items:get(k)
    local t = it:getFullType()
    if t == 'Base.Bag_Schoolbag' then
      local inner = {}
      local ii = it:getInventory():getItems()
      for n = 0, ii:size()-1 do inner[#inner+1] = ii:get(n):getFullType() end
      table.sort(inner)
      out[#out+1] = t .. '|' .. table.concat(inner, '+')
    elseif t == 'Base.WaterBottle' then
      local fc = it:getFluidContainer()
      out[#out+1] = t .. '|' .. (fc and string.format('%%.3f', fc:getAmount()) or '-')
    elseif t == 'Base.Vest_BulletPolice' then
      out[#out+1] = t .. '|' .. it:getCondition()
    end
  end
end
table.sort(out)
return table.concat(out, ';')
"""

CLEAN = r"""
local sq = getCell():getGridSquare(%d, %d, 0)
local o = sq:getObjects():get(%d)
local removed = 0
for ci = 0, o:getContainerCount()-1 do
  local c = o:getContainerByIndex(ci)
  for k = c:getItems():size()-1, 0, -1 do
    local it = c:getItems():get(k)
    local t = it:getFullType()
    if t == 'Base.Hammer' or t == 'Base.Apple' or t == 'Base.Lighter' or t == 'Base.Bag_Schoolbag' or t == 'Base.WaterBottle' or t == 'Base.Vest_BulletPolice' then
      c:Remove(it); removed = removed + 1
    end
  end
end
return tostring(removed)
"""


def gui():
    return p.state()['minecraftGui']


def event(**e):
    reply = p.api('POST', '/gui', e)
    assert reply.get('ok'), reply


def click(x, y, mods=0):
    event(x=x, y=y, button=0, mods=mods, action=1)
    time.sleep(0.12)
    event(x=x, y=y, button=0, mods=mods, action=0)
    time.sleep(0.35)


def counts(slots):
    """{'Base.Apple': 3} out of the playtester's slot strings ('3 pz:Base.Apple a=0.40')."""
    c = collections.Counter()
    for s in slots:
        m = re.match(r'(\d+) pz:(\S+)', s)
        if m:
            c[m.group(2)] += int(m.group(1))
    return c


def pz_slots(inventory, pattern=r'\d+ pz:'):
    """{slot index: text} of the player's slots that hold a PZ item matching the pattern."""
    return {i: s for i, s in enumerate(inventory) if re.search(pattern, s)}


def arrived(before, after):
    """The slots that gained a stack between two snapshots of pz_slots. Steve may already carry the same kinds of item (leftovers,
    or the owner's own); the test must only ever move what it took out itself. A stack that merged into one already there cannot
    be told apart, so that stops the test instead of moving the owner's items into a container."""
    new = {}
    for i, s in after.items():
        if i not in before:
            new[i] = s
        elif s != before[i]:
            raise AssertionError(f'slot {i} changed from "{before[i]}" to "{s}": the items merged into a stack Steve already carried')
    return new


FIXTURE_TYPES = ('Base.Hammer', 'Base.Apple', 'Base.Lighter', 'Base.Bag_Schoolbag', 'Base.WaterBottle', 'Base.Vest_BulletPolice')

COUNT_FIXTURE = r"""
local sq = getCell():getGridSquare(%d, %d, 0)
local o = sq:getObjects():get(%d)
local n = 0
for ci = 0, o:getContainerCount()-1 do
  local items = o:getContainerByIndex(ci):getItems()
  for k = 0, items:size()-1 do
    local t = items:get(k):getFullType()
    if t == 'Base.Hammer' or t == 'Base.Apple' or t == 'Base.Lighter' or t == 'Base.Bag_Schoolbag' or t == 'Base.WaterBottle' or t == 'Base.Vest_BulletPolice' then n = n + 1 end
  end
end
return tostring(n)
"""


def dump(kind, x, y, index):
    r = p.lua(DUMP % (kind, x, y, index))['results'][0]
    return collections.Counter(entry.split(':', 1)[1] for entry in r.split(',') if entry)


def wait(pred, timeout, what, every=0.15):
    return p.wait_for(lambda: pred() or None, timeout, every, what)


def slot_xy(g, rows, area, index):
    """Normalised click position of a slot: area 'chest' (0-53) or 'inv' (player inventory index 0-35)."""
    w, h = g['width'], g['height']
    left, top = (w - 176) // 2, (h - (114 + rows * 18)) // 2
    if area == 'chest':
        row, col = divmod(index, 9)
        return (left + 8 + col * 18 + 8) / w, (top + 18 + row * 18 + 8) / h
    inventory_top = 18 + rows * 18 + 13
    if index < 9:
        return (left + 8 + index * 18 + 8) / w, (top + inventory_top + 58 + 8) / h
    row, col = divmod(index - 9, 9)
    return (left + 8 + col * 18 + 8) / w, (top + inventory_top + row * 18 + 8) / h


def open_container(look_yaw, pitch):
    p.send_input(release=True)
    p.send_input(look={'yaw': look_yaw, 'pitch': pitch, 'relative': False}, wait=True)
    time.sleep(0.8)  # the faces PZ sends refresh five times a second
    p.send_input(['USE'], 150, wait=True)
    wait(lambda: gui()['screen'] == 'ContainerScreen', 8, 'the container screen')
    time.sleep(0.4)
    return gui()


def close_screen():
    event(key=256)
    wait(lambda: gui()['screen'] == '', 6, 'the screen to close')


def committed():
    return p.state()['pzContainers']['committed']


def take_and_return(result, key, pattern, x, y, index, yaw, pitch, detail_lua, check):
    """Seeded items go out into Steve's inventory and come back; check(before, after) compares what PZ holds."""
    before_detail = p.lua(detail_lua % (x, y, index))['results'][0]
    result[key + 'Before'] = before_detail
    base = committed()
    g = open_container(yaw, pitch)
    rows = g['menuRows']
    # Whatever of these kinds Steve already carries is not the test's: only the slots that fill during the take are moved back.
    mine_before = pz_slots(g['inventory'], pattern)
    result[key + 'AlreadyCarried'] = list(mine_before.values())
    shown = [i for i, s in enumerate(g['menuSlots']) if re.search(pattern, s)]
    result[key + 'Shown'] = [g['menuSlots'][i] for i in shown]
    assert shown, f'the seeded items are not on the screen: {g["menuSlots"]}'
    for i in shown:
        click(*slot_xy(g, rows, 'chest', i), mods=1)
    took = arrived(mine_before, pz_slots(gui()['inventory'], pattern))
    result[key + 'Carried'] = list(took.values())
    assert sorted(took.values()) == sorted(result[key + 'Shown']), (took, result[key + 'Shown'])
    close_screen()
    wait(lambda: committed() > base, 6, 'PZ to receive the take')
    time.sleep(0.4)
    assert p.lua(detail_lua % (x, y, index))['results'][0] == '', 'PZ still holds the seeded items after they were taken'
    time.sleep(3.0)  # a few seconds in Steve's pocket
    g = open_container(yaw, pitch)
    rows = g['menuRows']
    base = committed()
    for i, text in took.items():
        assert g['inventory'][i] == text, f'slot {i} holds "{g["inventory"][i]}", not what was taken ("{text}")'
        click(*slot_xy(g, rows, 'inv', i), mods=1)
    close_screen()
    wait(lambda: committed() > base, 6, 'PZ to receive the put-back')
    time.sleep(0.4)
    after_detail = p.lua(detail_lua % (x, y, index))['results'][0]
    result[key + 'After'] = after_detail
    result[key + 'InventoryRestored'] = pz_slots(gui()['inventory'], pattern) == mine_before
    assert result[key + 'InventoryRestored'], 'Steve carries different items of these kinds than before the test'
    check(before_detail, after_detail)


def entries(detail):
    """The items in a detail string as lists of fields, one per item (several of one type stay several)."""
    return sorted(e.split('|') for e in detail.split(';') if e)


def check_state(before, after):
    eb, ea = entries(before), entries(after)
    assert collections.Counter(e[0] for e in ea) == collections.Counter(e[0] for e in eb) == collections.Counter(FIXTURE_TYPES[:3]), (eb, ea)
    b = {e[0]: e for e in eb}
    a = {e[0]: e for e in ea}
    hb, ha = b['Base.Hammer'], a['Base.Hammer']
    assert ha[1] == hb[1] == '3' and ha[4] == 'My Hammer', (hb, ha)                                    # condition, custom name
    lb, la = b['Base.Lighter'], a['Base.Lighter']
    assert abs(float(la[3]) - float(lb[3])) < 0.002 and abs(float(la[3]) - 0.4) < 0.02, (lb, la)         # uses left (32 in a lighter)
    ab, aa = b['Base.Apple'], a['Base.Apple']
    assert 0 <= float(aa[2]) - float(ab[2]) < 0.05 and float(aa[2]) >= 1.5, (ab, aa)                     # age kept, plus a little


def check_blobs(before, after):
    eb, ea = entries(before), entries(after)
    assert collections.Counter(e[0] for e in ea) == collections.Counter(e[0] for e in eb) == collections.Counter(FIXTURE_TYPES[3:]), (eb, ea)
    b = {e[0]: e[1] for e in eb}
    a = {e[0]: e[1] for e in ea}
    assert a['Base.Bag_Schoolbag'] == b['Base.Bag_Schoolbag'] == 'Base.Apple+Base.Hammer+Base.Hammer', (b, a)  # contents of the bag
    assert abs(float(a['Base.WaterBottle']) - float(b['Base.WaterBottle'])) < 0.01, (b, a)                   # fluid in the bottle
    assert a['Base.Vest_BulletPolice'] == b['Base.Vest_BulletPolice'] == '5', (b, a)                          # condition


def run(kind, radius, min_items, min_containers, state_test, blob_test):
    result = {'passed': False, 'kind': kind}
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    origin = s0['steve']
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    held = False
    cobble_given = False
    seeded_at = None
    hand = None
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        hand = p.select_inert_hand()  # the use key must only ever open the container, never place or wear what Steve holds
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
              'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
        held = True
        found = p.lua(FIND % (kind, radius, min_items, min_containers))['results'][0]
        assert found != 'none', f'no PZ {kind} with {min_items}+ items within {radius} tiles of Steve'
        x, y, index, ncont, total, side = (int(v) for v in found.split(','))
        result['container'] = {'x': x, 'y': y, 'index': index, 'containers': ncont, 'items': total}
        # Steve stands on the free side (1 south, 2 north, 3 east, 4 west), one tile from the middle, and looks down at it.
        dx, dy, yaw = {1: (0, 1, 180), 2: (0, -1, 0), 3: (1, 0, 90), 4: (-1, 0, -90)}[side]
        aim = {'object': 0.7, 'corpse': 0.2, 'floor': 0.2}[kind]
        pitch = round(math.degrees(math.atan2(1.62 - aim, 1.0)), 1)
        before = dump(kind, x, y, index)
        result['before'] = dict(before)
        base = p.state()['pzContainers']
        p.cmd(f'mc tp @s {x + dx + 0.5} 0.01 {y + dy + 0.5}')
        time.sleep(1.8)

        # ---- 1. open, and compare what the screen shows with what PZ holds ----
        g = open_container(yaw, pitch)
        rows = g['menuRows']
        shown = counts(g['menuSlots'])
        result['screen'] = {'rows': rows, 'shown': dict(shown), 'hidden': g['pzContainers']['hiddenStacks']}
        (OUT / f'containers-{kind}-open.png').write_bytes(p.screenshot(0.7))
        if g['pzContainers']['hiddenStacks'] == 0:
            assert shown == before, f'screen shows {dict(shown)} but PZ holds {dict(before)}'
        else:
            assert all(shown[t] <= before[t] for t in shown), 'screen shows more than PZ holds'

        # ---- 2. shift-click two stacks into Steve's inventory ----
        inv_before = counts(g['inventory'])
        slots_before = pz_slots(g['inventory'])
        # Stacks of kinds Steve already carries could merge into his own and could not be put back alone: take other kinds.
        taking = [i for i, s in enumerate(g['menuSlots']) if re.match(r'\d+ pz:', s) and not counts([s]).keys() & inv_before.keys()][:2]
        assert taking, f'nothing to take that Steve does not already carry ({dict(inv_before)})'
        taken = counts([g['menuSlots'][i] for i in taking])
        for i in taking:
            click(*slot_xy(g, rows, 'chest', i), mods=1)
        g = gui()
        gained = counts(g['inventory']) - inv_before
        result['taken'] = dict(taken)
        result['gained'] = dict(gained)
        assert gained == taken, f'inventory gained {dict(gained)} but {dict(taken)} was taken'
        took = arrived(slots_before, pz_slots(g['inventory']))
        result['tookSlots'] = took

        # ---- 3. a stone cannot go in ----
        cobble_before = sum(int(m.group(1)) for s in g['inventory'] if (m := re.match(r'(\d+) minecraft:cobblestone', s)))
        p.cmd('mc give @s minecraft:cobblestone 3')
        cobble_given = True
        time.sleep(0.8)
        g = gui()
        stone = next(i for i, s in enumerate(g['inventory']) if re.match(r'\d+ minecraft:cobblestone', s))
        click(*slot_xy(g, rows, 'inv', stone), mods=1)
        g = gui()
        cobble_now = sum(int(m.group(1)) for s in g['inventory'] if (m := re.match(r'(\d+) minecraft:cobblestone', s)))
        result['stoneStayed'] = cobble_now == cobble_before + 3 and not any('cobblestone' in s for s in g['menuSlots'])
        assert result['stoneStayed'], 'a non-PZ item went into the PZ container'

        # ---- 4. close: PZ must have lost exactly what was taken ----
        close_screen()
        wait(lambda: committed() > base['committed'], 6, 'PZ to receive the commit')
        time.sleep(0.5)
        after_take = dump(kind, x, y, index)
        result['afterTake'] = dict(after_take)
        assert after_take == before - taken, f'PZ holds {dict(after_take)}, expected {dict(before - taken)}'
        pz = p.state()['pzContainers']
        result['pzStats'] = pz
        assert pz['failures'] == base['failures'], pz

        # ---- 5. open again, put the same items back ----
        g = open_container(yaw, pitch)
        rows = g['menuRows']
        assert counts(g['menuSlots']) == after_take or g['pzContainers']['hiddenStacks'] > 0, 'second screen does not match PZ'
        for i, text in took.items():  # only the slots the take filled: never something else Steve carries
            assert g['inventory'][i] == text, f'slot {i} holds "{g["inventory"][i]}", not what was taken ("{text}")'
            click(*slot_xy(g, rows, 'inv', i), mods=1)
        (OUT / f'containers-{kind}-put.png').write_bytes(p.screenshot(0.7))
        close_screen()
        wait(lambda: committed() > pz['committed'], 6, 'PZ to receive the second commit')
        time.sleep(0.5)
        restored = dump(kind, x, y, index)
        result['restored'] = dict(restored)
        assert restored == before, f'PZ holds {dict(restored)}, expected {dict(before)}'
        result['inventoryRestored'] = counts(gui()['inventory']) == inv_before
        assert result['inventoryRestored'], 'Steve still carries PZ items from the test'
        result['mcStats'] = gui()['pzContainers']

        if (state_test or blob_test) and kind == 'object':
            # The fixture lives in a world container: never delete what is already in it (CLEAN runs only on what this run seeded).
            present = int(p.lua(COUNT_FIXTURE % (x, y, index))['results'][0])
            assert present == 0, f'the container at {x},{y} already holds {present} item(s) of the fixture types: clean it by hand or pick another'
            seeded_at = (x, y, index)
            if state_test:
                assert p.lua(SEED_STATE % seeded_at)['results'][0] == 'seeded'
                take_and_return(result, 'state', r'pz:Base\.(Hammer|Apple|Lighter)', x, y, index, yaw, pitch, DETAIL_STATE, check_state)
                result['stateRoundTrip'] = True
                result['stateCleaned'] = p.lua(CLEAN % seeded_at)['results'][0]
                assert result['stateCleaned'] == '3', f'the container held {result["stateCleaned"]} fixture items after the round trip, expected 3'
            if blob_test:
                assert p.lua(SEED_BLOBS % seeded_at)['results'][0] == 'seeded'
                take_and_return(result, 'blobs', r'pz:Base\.(Bag_Schoolbag|WaterBottle|Vest_BulletPolice)', x, y, index, yaw, pitch, DETAIL_BLOBS, check_blobs)
                result['blobRoundTrip'] = True
        result['passed'] = True
    finally:
        try:
            if gui()['screen']:
                event(key=256)
                time.sleep(0.5)
        except Exception:
            pass
        p.send_input(release=True)
        if cobble_given:
            p.cmd('mc clear @s minecraft:cobblestone 3')  # exactly the three given: the owner's own stone stays
        if seeded_at:
            result['cleaned'] = p.lua(CLEAN % seeded_at)['results'][0]
        p.cmd(f'mc tp @s {origin["x"]} 0.01 {origin["z"]}')
        p.send_input(look={'yaw': origin['yaw'], 'pitch': origin['pitch'], 'relative': False}, wait=True)  # facing as before
        p.restore_hand(hand)
        time.sleep(1.0)
        if held:
            p.lua('if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        stamp = time.strftime('%Y%m%d-%H%M%S')
        result['finished'] = stamp
        text = json.dumps(result, indent=2)
        (OUT / f'containers-{kind}-results.json').write_text(text)
        (OUT / f'containers-{kind}-results-{stamp}.json').write_text(text)  # each run keeps its own record
    print(json.dumps(result, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kind', choices=('object', 'corpse', 'floor'), default='object')
    parser.add_argument('--radius', type=int, default=24, help='search radius in tiles around Steve')
    parser.add_argument('--min-items', type=int, default=3, help='smallest container worth testing with')
    parser.add_argument('--min-containers', type=int, default=1, help='2 picks a fridge (fridge + freezer)')
    parser.add_argument('--state', action='store_true', help='also round-trip a hammer (condition 3, named), an apple (1.5 days) and a lighter (40%% left)')
    parser.add_argument('--blobs', action='store_true', help='also round-trip a bag with things in it, a water bottle and a bullet-proof vest')
    a = parser.parse_args()
    run(a.kind, a.radius, a.min_items, a.min_containers, a.state, a.blobs)
