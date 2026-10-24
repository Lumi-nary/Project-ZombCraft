"""0.14 unified inventory, H4: the mirror across a restart (clean stop, PZ crash, Minecraft crash).

  check-mirror-restart.py record FILE     snapshot both inventories (Steve's slots and worn items; the PZ pockets) into FILE
  check-mirror-restart.py change          make one PZ-side change (a hammer into the pockets) and wait until the mirror is in step
  check-mirror-restart.py compare FILE [--expect same|hammer|nohammer]
                                          after the games were restarted: wait for the mirror to be in step, then compare with FILE

  same      both inventories equal the snapshot, nothing extra (a clean stop; a crash with nothing changed)
  hammer    equal to the snapshot plus exactly one plain hammer on BOTH sides (PZ crashed after Minecraft applied and saved it)
  nohammer  equal to the snapshot, no hammer on either side (Minecraft crashed and lost it; PZ follows Minecraft)

The caller stops and starts the games (pzctl kill / launch, or a plain taskkill to simulate a crash). Only the one test hammer is
ever created here; `compare` removes a leftover hammer through PZ when asked to with --cleanup. Steve's other items are never touched.
"""
import argparse
import collections
import json
import re
import time
from pathlib import Path
import pzcore as p

POCKETS = r"""
local out = {}
local items = getPlayer():getInventory():getItems()
for i = 0, items:size()-1 do
  local it = items:get(i)
  local line = it:getFullType() .. '|' .. string.format('%.0f', it:getID())
  local md = it:hasModData() and it:getModData().pzcraftMc or nil
  if md then line = line .. '|' .. tostring(md.n) .. '|' .. tostring(md.snbt) else line = line .. '||' end
  if instanceof(it, 'InventoryContainer') then
    local inner = {}
    local ii = it:getInventory():getItems()
    for k = 0, ii:size()-1 do inner[#inner+1] = ii:get(k):getFullType() .. '#' .. string.format('%.0f', ii:get(k):getID()) end
    table.sort(inner)
    line = line .. '|' .. table.concat(inner, ',')
  else line = line .. '|' end
  out[#out+1] = line
end
table.sort(out)
return table.concat(out, '\n')
"""

DROP_HAMMER = ("local inv = getPlayer():getInventory(); for i = inv:getItems():size()-1, 0, -1 do local it = inv:getItems():get(i); "
               "if it:getFullType() == 'Base.Hammer' then inv:Remove(it) end end")


def snapshot():
    g = p.state()['minecraftGui']
    return {'slots': list(g['inventory']), 'worn': list(g['worn']),
            'pockets': [l for l in p.lua(POCKETS)['results'][0].split('\n') if l]}


def in_step():
    s = p.state()
    m = s.get('pzMirror', {})
    return s['gameState'] == 'IngameState' and m.get('active') and m.get('status') == 'in step' and m.get('pending') == 0


def wait_step(timeout=90):
    p.wait_for(lambda: in_step() or None, timeout, 0.5, 'the mirror to be in step')
    time.sleep(1.5)


def unpause():
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')


def pause():
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')


def hammers(snap):
    return (sum(1 for s in snap['slots'] if re.fullmatch(r'1 pz:Base\.Hammer( whole)?', s)),
            sum(1 for l in snap['pockets'] if l.startswith('Base.Hammer|')))


def strip_hammer(snap):
    return {'slots': [('0 minecraft:air' if re.fullmatch(r'1 pz:Base\.Hammer( whole)?', s) else s) for s in snap['slots']],
            'worn': snap['worn'], 'pockets': [l for l in snap['pockets'] if not l.startswith('Base.Hammer|')]}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('command', choices=('record', 'change', 'compare'))
    ap.add_argument('file', nargs='?')
    ap.add_argument('--expect', choices=('same', 'hammer', 'nohammer'), default='same')
    ap.add_argument('--cleanup', action='store_true', help='compare: afterwards remove the test hammer (through PZ) if there is one')
    a = ap.parse_args()
    s = p.state()
    assert s['gameState'] == 'IngameState' and s['link']['ready'], 'the game must be in the world with Minecraft linked'
    unpause()
    try:
        if a.command == 'record':
            wait_step()
            snap = snapshot()
            snap['stats'] = {'pz': p.state()['pzMirror'], 'mc': p.state()['minecraftGui']['mirror']}
            Path(a.file).write_text(json.dumps(snap, indent=1))
            print(f"recorded {len([x for x in snap['slots'] if not x.startswith('0 ')])} occupied slots, {len(snap['pockets'])} pocket items -> {a.file}")
        elif a.command == 'change':
            wait_step()
            before = hammers(snapshot())
            assert before == (0, 0), f'a hammer is already there: {before}'
            p.lua("getPlayer():getInventory():AddItem(instanceItem('Base.Hammer'))")
            p.wait_for(lambda: hammers(snapshot()) == (1, 1) or None, 30, 0.5, 'the hammer to reach Minecraft')
            wait_step()
            print('hammer in both inventories, mirror in step')
        else:
            before = json.loads(Path(a.file).read_text())
            wait_step()
            after = snapshot()
            h = hammers(after)
            result = {'expect': a.expect, 'hammers': h}
            expected = before
            if a.expect == 'hammer':
                assert h == (1, 1), f'expected one hammer on both sides, found {h}'
                after = strip_hammer(after)
            else:
                assert h == (0, 0), f'expected no hammer, found {h}'
            ok = True
            if after['slots'] != expected['slots']:
                ok = False
                diff = [(i, expected['slots'][i], after['slots'][i]) for i in range(len(expected['slots'])) if expected['slots'][i] != after['slots'][i]]
                result['slotDiff'] = diff
            if after['worn'] != expected['worn']:
                ok = False
                result['wornDiff'] = [expected['worn'], after['worn']]
            if collections.Counter(after['pockets']) != collections.Counter(expected['pockets']):
                ok = False
                c1, c2 = collections.Counter(expected['pockets']), collections.Counter(after['pockets'])
                result['pocketsMissing'] = list((c1 - c2).elements())
                result['pocketsExtra'] = list((c2 - c1).elements())
            st = p.state()
            result['stats'] = {'pz': st['pzMirror'], 'mc': st['minecraftGui']['mirror']}
            result['ok'] = ok and st['pzMirror']['failures'] == 0 and st['minecraftGui']['mirror']['failures'] == 0
            print(json.dumps(result, indent=1))
            if a.cleanup and h != (0, 0):
                p.lua(DROP_HAMMER)
                p.wait_for(lambda: hammers(snapshot()) == (0, 0) or None, 30, 0.5, 'the hammer to go from both')
                wait_step()
                print('test hammer removed from both')
            if not result['ok']:
                raise SystemExit(1)
    finally:
        pause()


if __name__ == '__main__':
    main()
