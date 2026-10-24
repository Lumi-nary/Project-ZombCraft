"""0.13 punchable PZ trees: Steve punches a tree by hand, PZ topples it, Minecraft drops logs.

Only use the backed-up PzCraftFeatureTest-20261002 save. Uses the control API (no desktop focus). The felled tree stays
felled in the test save (restore it from .pzcraft-backups if needed); the dropped items are removed from Steve again.
"""
import argparse
import json
import math
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent


def gui():
    return p.state()['minecraftGui']


def count(inventory, item):
    total = 0
    for stack in inventory:
        parts = stack.split(' ', 1)
        if len(parts) == 2 and parts[1] == item:
            total += int(parts[0])
    return total


def pz_has_tree(x, y):
    r = p.lua(f'local s=getCell():getGridSquare({x},{y},0); return tostring(s ~= nil and s:HasTree())')
    return r['results'][0] == 'true'


def run(size_wanted, hold_ms, tool, measure=False, give=None):
    result = {'passed': False, 'size': size_wanted, 'tool': tool, 'give': give}
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    origin = s0['steve']
    inventory_before = list(s0['minecraftGui']['inventory'])
    held = False
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
              'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
        held = True
        trees = gui()['trees']['nearest']
        candidates = [t for t in trees if t['placed'] and (size_wanted is None or t['size'] == size_wanted)] or [t for t in trees if t['placed']]
        assert candidates, f'no placed tree near Steve: {trees}'
        tree = candidates[0]
        tx, tz = tree['x'], tree['z']
        result['tree'] = tree
        assert pz_has_tree(tx, tz), 'PZ has no tree at the chosen tile'
        # Stand two and a half blocks south of the trunk, looking north at its middle.
        sx, sz = tx + 0.5, tz + 2.5
        if give:
            p.cmd(f'mc give @s {give}')
            time.sleep(0.6)
        p.cmd(f'mc tp @s {sx} 0.01 {sz}')
        time.sleep(1.5)
        p.send_input(release=True)
        p.send_input(look={'yaw': 180, 'pitch': 0, 'relative': False}, hotbar=tool, wait=True)
        time.sleep(1.0)
        result['crosshair'] = p.logs('minecraft', 'crosshair', 2)
        before = gui()
        base_felled = before['trees']['felled']          # counters are cumulative across runs
        base_toppled = p.state()['pzTrees']['toppled']
        (OUT / 'trees-before.png').write_bytes(p.screenshot(0.7))
        t0 = time.monotonic()
        if measure:
            # Hold attack in the background and time how long the tree takes to fall.
            p.send_input(['ATTACK'], hold_ms)
            while time.monotonic() - t0 < hold_ms / 1000 and gui()['trees']['felled'] == base_felled:
                time.sleep(0.05)
            result['secondsToFell'] = round(time.monotonic() - t0, 2)
            p.send_input(release=True)
        else:
            p.send_input(['ATTACK'], hold_ms, wait=True)
            result['heldSeconds'] = round(time.monotonic() - t0, 2)
        time.sleep(1.5)
        after = gui()
        (OUT / 'trees-after.png').write_bytes(p.screenshot(0.7))
        result['felled'] = after['trees']['felled'] - base_felled
        result['pz'] = p.state()['pzTrees']
        result['pzToppled'] = result['pz']['toppled'] - base_toppled
        result['pzStillHasTree'] = pz_has_tree(tx, tz)
        # Walk onto the drops to pick them up.
        p.send_input(['FWD'], 900, wait=True)
        time.sleep(1.0)
        inventory_after = gui()['inventory']
        gained = {}
        for item in ('minecraft:oak_log', 'minecraft:spruce_log', 'minecraft:birch_log', 'minecraft:dark_oak_log',
                     'minecraft:cherry_log', 'minecraft:stick', 'minecraft:spruce_sapling', 'minecraft:oak_sapling',
                     'minecraft:birch_sapling', 'minecraft:dark_oak_sapling', 'minecraft:cherry_sapling'):
            delta = count(inventory_after, item) - count(inventory_before, item)
            if delta:
                gained[item] = delta
        result['gained'] = gained
        result['inventoryAfter'] = inventory_after
        result['passed'] = (result['felled'] == 1 and result['pzToppled'] == 1 and not result['pzStillHasTree']
                            and any(k.endswith('_log') for k in gained))
        for item, n in gained.items():
            p.cmd(f'mc clear @s {item} {n}')   # remove only what the check collected
            time.sleep(0.2)
        if give:
            p.cmd(f'mc clear @s {give}')
    finally:
        p.send_input(release=True)
        p.cmd(f'mc tp @s {origin["x"]} 0.01 {origin["z"]}')
        time.sleep(1.0)
        if held:
            p.lua('if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        (OUT / 'trees-results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({k: v for k, v in result.items() if k != 'inventoryAfter'}, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--size', type=int, default=3, help='preferred PZ tree size (1-8)')
    parser.add_argument('--hold-ms', type=int, default=6000)
    parser.add_argument('--tool', type=int, default=5, help='hotbar slot 1-9 (default: an empty one, so by hand)')
    parser.add_argument('--measure', action='store_true', help='time how long the tree takes to fall')
    parser.add_argument('--give', help='give Steve this item first (e.g. minecraft:iron_axe) and remove it afterwards')
    a = parser.parse_args()
    run(a.size, a.hold_ms, a.tool, a.measure, a.give)
