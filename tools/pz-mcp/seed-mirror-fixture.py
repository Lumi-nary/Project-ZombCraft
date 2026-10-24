"""Builds a known mixed inventory for the mirror tests: Steve's hotbar and main slots like the dev character had them, with
Minecraft-only items, PZ items given with /pzgive, and the ID card and a key ring (with a key) added to the PZ pockets from
PZ's side. Starts from an EMPTY Steve and an empty set of pockets: run `mc clear @s` first (this refuses otherwise).

  python seed-mirror-fixture.py

Needs the game linked with Steve driving. Leaves the world paused when done.
"""
import time
import pzcore as p

HOTBAR = {0: 'minecraft:iron_sword', 1: 'minecraft:iron_pickaxe', 2: 'minecraft:iron_shovel', 3: 'minecraft:elytra',
          4: 'minecraft:flint_and_steel', 5: 'minecraft:tnt 59', 6: 'minecraft:raw_copper 3'}  # (hotbar 7 and 8 stay free: the item test needs one to hold what it uses)
MAIN = {9: 'minecraft:leather 2', 10: 'minecraft:rotten_flesh 4', 11: 'minecraft:cooked_beef 12', 12: 'minecraft:beef 8',
        14: 'minecraft:gravel 20', 15: 'minecraft:oak_boat', 16: 'minecraft:firework_rocket 54', 30: 'minecraft:flint_and_steel',
        31: 'minecraft:cobbled_deepslate 52', 32: 'minecraft:repeater', 33: 'minecraft:end_rod'}
PZ_GIVEN = ['Base.Hat_Army', 'Base.Axe', 'Base.Generator', 'Base.Katana', 'Base.Katana', 'Base.Bandage', 'Base.Lighter',
            'Base.Pistol', 'Base.Vest_BulletPolice']


def step_ready():
    s = p.state()
    m = s['pzMirror']
    return m['active'] and m['status'] == 'in step' and m['pending'] == 0


def main():
    s = p.state()
    assert s['gameState'] == 'IngameState' and s['link']['ready'], 'game must be in the world with Minecraft linked'
    g = s['minecraftGui']
    assert all(x.startswith('0 ') for x in g['inventory'] + g['worn']), 'Steve is not empty: run `mc clear @s` first'
    assert p.lua('return tostring(getPlayer():getInventory():getItems():size())')['results'][0] == '0', 'the PZ pockets are not empty'
    p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    try:
        for slot, item in {**HOTBAR, **MAIN}.items():
            area = f'hotbar.{slot}' if slot < 9 else f'container.{slot}'
            p.cmd(f'mc item replace entity @s {area} with {item}')
            time.sleep(0.25)
        p.wait_for(lambda: step_ready() or None, 40, 0.5, 'the mirror to be in step')
        for item in PZ_GIVEN:
            p.cmd(f'mc pzgive {item} 1')
            time.sleep(0.6)
        p.wait_for(lambda: step_ready() or None, 60, 0.5, 'the mirror to be in step')
        time.sleep(3)  # plain /pzgive stacks become whole-item stacks through one more round
        # what the PZ character carried by itself: an ID card and a key ring holding a key
        p.lua("local inv = getPlayer():getInventory(); inv:AddItem(instanceItem('Base.IDcard')); "
              "local ring = instanceItem('Base.KeyRing'); ring:getInventory():AddItem('Base.Key1'); inv:AddItem(ring)")
        time.sleep(2)
        p.wait_for(lambda: step_ready() or None, 60, 0.5, 'the mirror to be in step')
        time.sleep(2)
        g = p.state()['minecraftGui']
        occupied = [(i, x) for i, x in enumerate(g['inventory']) if not x.startswith('0 ')]
        print(f'{len(occupied)} occupied slots:')
        for i, x in occupied:
            print(f'  {i:2d}: {x}')
    finally:
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')


if __name__ == '__main__':
    main()
