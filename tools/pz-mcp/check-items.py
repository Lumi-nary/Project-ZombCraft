"""0.14 unified inventory, step H3: PZ items work in Minecraft.

Checks, with the real controls (hold the use key with the item in hand):
  * food: an apple fills Steve's Minecraft hunger (and one apple is used up);
  * a tin of food leaves its empty tin behind when it is eaten, if PZ says it does;
  * clothes: a bullet-proof vest is worn on the chest by using it and adds armor points; a T-shirt adds none.

Only use the backed-up PzCraftFeatureTest-20261002 save. Uses the control API (no desktop focus needed).
"""
import json
import re
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent
CATALOGUE = Path.home() / '.pzcraft' / 'items' / 'items.json'


def gui():
    return p.state()['minecraftGui']


def wait(pred, timeout, what, every=0.2):
    return p.wait_for(lambda: pred() or None, timeout, every, what)


def have(slots, pattern):
    return sum(int(re.match(r'(\d+)', s).group(1)) for s in slots if re.search(pattern, s))


def give(item, count=1):
    """/pzgive; returns the hotbar slot (1-9) the stack landed in, moving it there if it fell into the main inventory.
    Refuses to run if a plain stack of the item is already carried: the new one would merge into it, and the slot is wiped
    afterwards, which would delete the owner's items."""
    before = list(gui()['inventory'])
    assert not any(re.fullmatch(rf'\d+ pz:{re.escape(item)}', s) for s in before), f'Steve already carries a plain {item}: a given one would merge into it'
    p.cmd(f'mc pzgive {item} {count}')
    wait(lambda: gui()['inventory'] != before, 6, f'{item} in the inventory')
    time.sleep(0.3)
    now = gui()['inventory']
    changed = [i for i in range(36) if now[i] != before[i]]
    assert changed, 'nothing was given'
    slot = changed[0]
    assert before[slot].startswith('0 '), f'{item} landed in slot {slot}, which already held "{before[slot]}"'
    try:
        if slot >= 9:
            free = next((i for i in range(9) if now[i].startswith('0 ')), None)
            assert free is not None, 'no free hotbar slot'
            p.cmd(f'mc item replace entity @s hotbar.{free} from entity @s container.{slot}')
            p.cmd(f'mc item replace entity @s container.{slot} with minecraft:air')
            time.sleep(0.5)
            slot = free
    except BaseException:
        # the given item is in a slot that was empty before: take it back out rather than leave it in the owner's inventory
        p.cmd(f'mc item replace entity @s container.{slot} with minecraft:air')
        raise
    return slot + 1


def drop_slot(slot):
    p.cmd(f'mc item replace entity @s hotbar.{slot - 1} with minecraft:air')
    time.sleep(0.3)


def park_chest():
    """Steve's own chest piece (the owner's worn jacket...) goes into a spare inventory slot for the test: wearing the test vest
    would swap it into the hand slot, and cleaning that slot would delete it. Returns (slot, text) or None if nothing was worn."""
    g = gui()
    worn = g['worn'][1]
    if worn.startswith('0 '):
        return None
    spare = next(i for i in range(35, -1, -1) if g['inventory'][i].startswith('0 '))  # from the far end: give() fills the first free slot
    p.cmd(f'mc item replace entity @s container.{spare} from entity @s armor.chest')
    wait(lambda: gui()['inventory'][spare] == worn, 6, 'the chest piece to move into a spare slot')
    p.cmd('mc item replace entity @s armor.chest with minecraft:air')
    wait(lambda: gui()['worn'][1].startswith('0 '), 6, 'the chest to be bare')
    return spare, worn


def unpark_chest(parked):
    spare, worn = parked
    p.cmd('mc item replace entity @s armor.chest with minecraft:air')  # only test garments can be on it: the real one is parked
    time.sleep(0.3)
    p.cmd(f'mc item replace entity @s armor.chest from entity @s container.{spare}')
    wait(lambda: gui()['worn'][1] == worn, 6, 'the chest piece to be worn again')
    p.cmd(f'mc item replace entity @s container.{spare} with minecraft:air')
    time.sleep(0.3)


def first_with_remainder():
    items = json.loads(CATALOGUE.read_text(encoding='utf-8'))['items']
    for i in items:
        if i.get('food') and i.get('replaceOnUse') and not i.get('hidden') and i['food']['hunger'] < -5:
            return i
    return None


def run():
    result = {'passed': False}
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    slots_used = []
    parked = None
    held = False
    chest_bare = gui()['worn'][1].startswith('0 ')
    prev_hand = gui().get('selectedSlot')
    result['start'] = {'food': gui()['food'], 'armor': gui()['armor'], 'worn': gui()['worn'], 'selectedSlot': prev_hand}
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
              'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
        held = True
        p.send_input(release=True)
        # Looking at the sky, so that holding the use key can only use the item: aimed at a door or container it would use that instead.
        p.send_input(look={'yaw': s0['steve']['yaw'], 'pitch': -85, 'relative': False}, wait=True)
        time.sleep(0.5)

        # ---- food ----
        p.cmd('mc effect give @s minecraft:hunger 8 120 true')
        wait(lambda: gui()['food'] <= 12, 20, 'Steve to get hungry')
        p.cmd('mc effect clear @s minecraft:hunger')
        time.sleep(0.5)
        food_before = gui()['food']
        slot = give('Base.Apple', 2)
        slots_used.append(slot)
        apples_before = have(gui()['inventory'], r'pz:Base\.Apple')
        p.send_input(hotbar=slot, wait=True)
        time.sleep(0.5)
        p.send_input(['USE'], 2400, wait=True)
        time.sleep(0.8)
        g = gui()
        result['food'] = {'before': food_before, 'after': g['food'], 'applesBefore': apples_before,
                          'applesAfter': have(g['inventory'], r'pz:Base\.Apple')}
        assert g['food'] > food_before, result['food']
        assert result['food']['applesAfter'] == apples_before - 1, result['food']
        drop_slot(slot)  # the apple left over from the two given (the slot was empty before, so it holds only test items)
        slots_used.remove(slot)

        # ---- what a tin leaves behind ----
        tin = first_with_remainder()
        if tin:
            p.cmd('mc effect give @s minecraft:hunger 8 120 true')
            wait(lambda: gui()['food'] <= 12, 20, 'Steve to get hungry again')
            p.cmd('mc effect clear @s minecraft:hunger')
            module = tin['id'].split('.')[0]
            left = tin['replaceOnUse'] if '.' in tin['replaceOnUse'] else f'{module}.{tin["replaceOnUse"]}'
            slot = give(tin['id'], 1)
            slots_used.append(slot)
            inv_before = list(gui()['inventory'])
            before_left = have(inv_before, re.escape('pz:' + left))
            p.send_input(hotbar=slot, wait=True)
            time.sleep(0.5)
            p.send_input(['USE'], 2400, wait=True)
            time.sleep(0.8)
            inv_after = list(gui()['inventory'])
            after_left = have(inv_after, re.escape('pz:' + left))
            result['remainder'] = {'item': tin['id'], 'leaves': left, 'before': before_left, 'after': after_left}
            assert after_left == before_left + 1, result['remainder']
            # the empty tin is the test's: remove it from the slot it appeared in, if that slot was empty before (never from a stack of Steve's)
            for i in range(36):
                if re.search(re.escape('pz:' + left), inv_after[i]) and inv_before[i].startswith('0 '):
                    p.cmd(f'mc item replace entity @s container.{i} with minecraft:air')
                    result['remainder']['removedFromSlot'] = i
            drop_slot(slot)
            slots_used.remove(slot)

        # ---- clothes ----
        parked = park_chest()
        result['parkedChest'] = parked
        armor_before = gui()['armor']
        slot = give('Base.Vest_BulletPolice', 1)
        slots_used.append(slot)
        p.send_input(hotbar=slot, wait=True)
        time.sleep(0.5)
        p.send_input(['USE'], 300, wait=True)
        time.sleep(0.8)
        g = gui()
        result['vest'] = {'armorBefore': armor_before, 'armorAfter': g['armor'], 'worn': g['worn']}
        (OUT / 'items-vest.png').write_bytes(p.screenshot(0.7))
        assert 'Vest_BulletPolice' in g['worn'][1], g['worn']
        assert g['armor'] >= armor_before + 6, result['vest']
        p.cmd('mc item replace entity @s armor.chest with minecraft:air')
        time.sleep(0.5)
        drop_slot(slot)
        slots_used.remove(slot)

        slot = give('Base.Tshirt_DefaultTEXTURE_TINT', 1)
        slots_used.append(slot)
        p.send_input(hotbar=slot, wait=True)
        time.sleep(0.5)
        armor_before = gui()['armor']
        p.send_input(['USE'], 300, wait=True)
        time.sleep(0.8)
        g = gui()
        result['shirt'] = {'armorBefore': armor_before, 'armorAfter': g['armor'], 'worn': g['worn']}
        assert 'Tshirt' in g['worn'][1] and g['armor'] == armor_before, result['shirt']
        p.cmd('mc item replace entity @s armor.chest with minecraft:air')
        drop_slot(slot)
        slots_used.remove(slot)
        result['passed'] = True
    finally:
        def cleanup(what, action):
            """Every cleanup step runs on its own: one failing must neither skip the rest nor the results file."""
            try:
                action()
            except Exception as e:  # noqa: BLE001
                result.setdefault('cleanupErrors', []).append(f'{what}: {e}')

        cleanup('release input', lambda: p.send_input(release=True))
        cleanup('facing', lambda: p.send_input(look={'yaw': s0['steve']['yaw'], 'pitch': s0['steve']['pitch'], 'relative': False}, wait=True))
        cleanup('hunger effect', lambda: p.cmd('mc effect clear @s minecraft:hunger'))
        for slot in list(slots_used):
            cleanup(f'drop test slot {slot}', lambda s=slot: drop_slot(s))
        # the chest is only ever cleared when Steve's own piece is parked safely (or he wore none): never delete what he wears
        if parked:
            cleanup('restore the chest piece', lambda: unpark_chest(parked))
            if gui()['worn'][1] != parked[1]:
                result.setdefault('cleanupErrors', []).append(f'THE CHEST PIECE IS NOT BACK: it is still in inventory slot {parked[0]} ({parked[1]})')
        elif chest_bare:
            cleanup('bare chest', lambda: p.cmd('mc item replace entity @s armor.chest with minecraft:air'))
        if result['start']['food'] >= 20 and gui()['food'] < 20:
            def top_up():
                p.cmd('mc effect give @s minecraft:saturation 1 0 true')  # the test made Steve hungry: top him up again (food caps at 20)
                time.sleep(1.5)
                p.cmd('mc effect clear @s minecraft:saturation')
            cleanup('hunger top-up', top_up)
        cleanup('final state', lambda: result.__setitem__('end', {'food': gui()['food'], 'armor': gui()['armor'], 'worn': gui()['worn'], 'selectedSlot': gui().get('selectedSlot')}))
        if held:
            cleanup('zombies', lambda: p.lua('if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end'))
        cleanup('selected slot', lambda: p.restore_hand(prev_hand))
        cleanup('pause', lambda: p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')'))
        if result.get('cleanupErrors'):
            result['passed'] = False
        (OUT / 'items-results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    run()
