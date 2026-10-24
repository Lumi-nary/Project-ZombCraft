"""Phase I.1: PZ melee weapons work in Minecraft with PZ's numbers.

W1  for several PZ melee weapons: give one (/pzgive), hold it, and compare what Steve's attack attribute says with the
    formula (2 * average PZ damage / 0.3), the attack speed, the tree-felling speed and the durability bar;
    and carries the Better Combat preset chosen for its PZ category (swing animation, arc);
W2  one real hit: a PZ zombie with known health is frozen two tiles in front of Steve, Steve swings a hammer once, and the
    health PZ took off the zombie must equal the damage Minecraft dealt times the forwarding scale (0.3), which is
    twice the hammer's average PZ damage;
W3  one crowbar swing (PZ allows 3 targets) at three frozen zombies in an arc (Better Combat's sweep): they take PZ's
    damage, damage / 2 and damage / 3 (CombatManager: damage / (split++ * 0.5));
W4  one hammer swing (PZ allows 1 target) at the same arc: exactly one zombie is hurt.

Creates and removes only its own items (each goes into a slot that was empty before), never presses the use key, restores the
selected hotbar slot. Needs Steve driving with the mirror in step and a free hotbar slot.
"""
import json
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent
CATALOGUE = Path.home() / '.pzcraft' / 'items' / 'items.json'
WEAPONS = ['Base.Katana', 'Base.Axe', 'Base.Hammer', 'Base.BaseballBat', 'Base.Crowbar', 'Base.Machete', 'Base.Sledgehammer']
PRESETS = {'Base.Katana': 'katana', 'Base.Axe': 'heavy_axe', 'Base.Hammer': 'mace', 'Base.BaseballBat': 'staff',
           'Base.Crowbar': 'staff', 'Base.Machete': 'sword', 'Base.Sledgehammer': 'hammer'}


def gui():
    return p.state()['minecraftGui']


def expected(w):
    avg = (w['minDamage'] + w['maxDamage']) / 2
    damage = max(1.0, min(60.0, avg * 2 / 0.3))
    speed = 1.2 if w['twoHanded'] else 1.8
    tree = 1 + w['treeDamage'] * 0.15 if w['treeDamage'] >= 2 else None
    return damage, speed, tree

OUTDOORS = """local p=getPlayer(); local cell=getCell(); local px,py=math.floor(p:getX()),math.floor(p:getY())
local function clear(x,y)
 for dx=-2,8 do for dy=-2,4 do local sq=cell:getGridSquare(x+dx,y+dy,0)
  if not sq or not sq:isFree(false) or sq:getObjects():size()>1 then return false end end end return true end
for r=12,40 do for dx=-r,r do for dy=-r,r do
 if math.max(math.abs(dx),math.abs(dy))==r and clear(px+dx,py+dy) then return (px+dx+0.5)..","..(py+dy+0.5) end
end end end return "none" """


def in_step():
    s = p.state()
    m = s['pzMirror']
    return m['active'] and m['status'] == 'in step' and m['pending'] == 0


def free_hotbar():
    g = gui()
    return next((i for i in range(9) if g['inventory'][i].startswith('0 ')), None)


def run():
    result = {'passed': False, 'weapons': {}}
    items = {i['id']: i for i in json.loads(CATALOGUE.read_text(encoding='utf-8'))['items']}
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    previous_hand = gui().get('selectedSlot')
    origin = s0['steve']
    moved = False
    slot = free_hotbar()
    assert slot is not None, 'no free hotbar slot to hold a test weapon'
    given_in = None
    zombie_made = False
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
        'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')  # nothing may attack Steve during the test
        p.wait_for(lambda: in_step() or None, 40, 0.5, 'the mirror to be in step')

        # ---- W1: the numbers ----
        for wid in WEAPONS:
            if wid not in items or not items[wid].get('weapon'):
                result['weapons'][wid] = 'not in the catalogue'
                continue
            assert slot is not None and gui()['inventory'][slot].startswith('0 '), 'the test slot is not empty'
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')   # (it is empty already: makes the intent explicit)
            before = list(gui()['inventory'])
            p.cmd(f'mc pzgive {wid} 1')
            p.wait_for(lambda: (list(gui()['inventory']) != before) or None, 8, 0.3, f'{wid} in the inventory')
            landed = next(i for i in range(36) if gui()['inventory'][i] != before[i])
            assert before[landed].startswith('0 '), f'{wid} landed on top of "{before[landed]}"'
            given_in = landed
            if landed != slot:      # it fell into the main inventory: move it into the free hotbar slot
                p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{landed}')
                p.cmd(f'mc item replace entity @s container.{landed} with minecraft:air')
                given_in = slot
                time.sleep(0.4)
            p._select_slot(slot)
            time.sleep(0.5)
            held = gui()['held']
            w = items[wid]['weapon']
            damage, speed, tree = expected(w)
            got = {'item': held['item'], 'attackDamage': held.get('serverAttackDamage', held['attackDamage']), 'attackSpeed': held['attackSpeed'],
                   'treeSpeed': held['treeSpeed'], 'durability': held['durability']}
            result['weapons'][wid] = {'got': got, 'expectedDamage': damage, 'expectedSpeed': speed, 'expectedTreeSpeed': tree, 'pz': w}
            assert abs(got['attackDamage'] - damage) < 0.05, f'{wid}: attack damage {got["attackDamage"]}, expected {damage}'
            assert abs(held['attackSpeed'] - speed) < 0.05, f'{wid}: attack speed {held["attackSpeed"]}, expected {speed}'
            if tree:
                assert held['treeSpeed'] >= tree - 0.05, f'{wid}: tree speed {held["treeSpeed"]}, expected {tree}'
            else:
                assert held['treeSpeed'] <= 1.0 + 1e-6, f'{wid}: tree speed {held["treeSpeed"]} but it is no tree tool'
            assert held['durability'].endswith(f'/{w["conditionMax"]}'), f'{wid}: durability {held["durability"]}'
            got['preset'] = held.get('preset', '')
            assert f'bettercombat:{PRESETS[wid]}' in got['preset'], f'{wid}: Better Combat preset "{got["preset"]}", expected {PRESETS[wid]}'
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
            given_in = None
            p.wait_for(lambda: (gui()['inventory'][slot].startswith('0 ')) or None, 6, 0.3, 'the test weapon to go')
            p.wait_for(lambda: in_step() or None, 30, 0.5, 'the mirror to settle')

        # ---- W2: one real hit with the hammer ----
        hammer = items['Base.Hammer']['weapon']
        expected_loss = (hammer['minDamage'] + hammer['maxDamage']) / 2 * 2    # PZ: first target takes damage / 0.5
        before = list(gui()['inventory'])
        p.cmd('mc pzgive Base.Hammer 1')
        p.wait_for(lambda: (list(gui()['inventory']) != before) or None, 8, 0.3, 'the hammer')
        landed = next(i for i in range(36) if gui()['inventory'][i] != before[i])
        assert before[landed].startswith('0 ')
        if landed != slot:
            p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{landed}')
            p.cmd(f'mc item replace entity @s container.{landed} with minecraft:air')
        given_in = slot
        time.sleep(0.4)
        p._select_slot(slot)
        p.send_input(release=True)
        spot = p.lua(OUTDOORS)['results'][0]
        assert spot != 'none', 'no open area near Steve for the hit test'
        sx, sz = (float(v) for v in spot.split(','))
        p.cmd(f'mc tp @s {sx} 0.02 {sz}')
        moved = True
        p.wait_for(lambda: (abs(p.state()['player']['x'] - sx) < 0.2 and abs(p.state()['player']['y'] - sz) < 0.2) or None, 20, 0.3, 'PZ to follow the teleport')
        p.send_input(look={'yaw': -90, 'pitch': 0, 'relative': False}, wait=True)   # east, into the open space
        time.sleep(0.8)
        p.cmd('spawn 1 2.0')
        zombie_made = True
        time.sleep(0.6)
        r = p.lua("local pl = getPlayer(); local best, bd = nil, 1e9; local a = getCell():getZombieList(); "
                  "for i = 0, a:size()-1 do local z = a:get(i); local d = (z:getX()-pl:getX())^2 + (z:getY()-pl:getY())^2; "
                  "if d < bd then best, bd = z, d end end; if not best then return 'none' end; "
                  "best:setUseless(true); best:setHealth(2.0); _pzWeaponTarget = best; return string.format('%.2f %.2f', math.sqrt(bd), best:getHealth())")['results'][0]
        assert r != 'none', 'no zombie to hit'
        result['W2'] = {'spawned': r}
        time.sleep(1.0)    # its proxy appears in Minecraft
        # Minecraft logs what the crosshair is on every three seconds: the line must not be blocked by a block (the owner builds near spawn)
        log = Path(p.LOGS['minecraft'])
        t0 = time.time()
        seen = None
        while time.time() - t0 < 6 and seen is None:
            time.sleep(0.5)
            for line in reversed(log.read_text(encoding='utf-8', errors='replace').splitlines()[-40:]):
                if 'crosshair:' in line and line.split(']')[0] > time.strftime('[%H:%M:%S', time.localtime(t0 - 1)):
                    seen = line.split('crosshair: ')[1].split(' at ')[0]; break
        result['W2']['crosshair'] = seen
        assert seen in ('MISS', 'ENTITY'), f'the line to the zombie is blocked (the crosshair is on a {seen}): move Steve or clear the blocks'
        p.send_input(['ATTACK'], 200, wait=True)   # one tap: one full-strength swing (the cooldown has long recharged)
        time.sleep(1.2)
        health = float(p.lua("return string.format('%.3f', _pzWeaponTarget and _pzWeaponTarget:getHealth() or -1)")['results'][0])
        loss = 2.0 - health
        held = gui()['held']
        result['W2'].update({'healthAfter': health, 'loss': loss, 'expectedLoss': expected_loss, 'mcAttackDamage': held['attackDamage']})
        assert health > 0, 'the hammer killed a full-health zombie in one hit: the damage is too high'
        assert abs(loss - expected_loss) < 0.08 * expected_loss + 0.05, f'the hammer took {loss:.2f} off the zombie, PZ would take {expected_loss:.2f}'
        # ---- W3 / W4: one swing at three zombies in an arc: PZ's rules for several targets ----
        p.lua("if _pzWeaponTarget then _pzWeaponTarget:removeFromWorld(); _pzWeaponTarget:removeFromSquare(); _pzWeaponTarget = nil end")
        zombie_made = False
        px, py = int(sx // 1), int(sz // 1)

        def hold(wid):
            """One wid in the test slot (a test item there before is removed), selected."""
            nonlocal given_in
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
            given_in = None
            p.wait_for(lambda: (gui()['inventory'][slot].startswith('0 ') and in_step()) or None, 30, 0.5, 'the slot to empty')
            inv = list(gui()['inventory'])
            p.cmd(f'mc pzgive {wid} 1')
            p.wait_for(lambda: (list(gui()['inventory']) != inv) or None, 8, 0.3, wid)
            at = next(i for i in range(36) if gui()['inventory'][i] != inv[i])
            assert inv[at].startswith('0 '), f'{wid} landed on top of "{inv[at]}"'
            if at != slot:
                p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}')
                p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
            given_in = slot
            time.sleep(0.4)
            p._select_slot(slot)

        def arc_swing():
            """Three frozen zombies east of Steve (two tiles ahead, one tile apart); one swing; their health losses, largest first."""
            nonlocal zombie_made
            arc = p.lua(
                "if _pzArc then for _, z in ipairs(_pzArc) do z:removeFromWorld(); z:removeFromSquare() end end; "
                "local known = {}; local a = getCell():getZombieList(); for i = 0, a:size()-1 do known[a:get(i)] = true end; "
                "for _, d in ipairs({{2,-1},{2,0},{2,1}}) do addZombiesInOutfit(" + str(px) + "+d[1], " + str(py) + "+d[2], 0, 1, nil, 50) end; "
                "_pzArc = {}; a = getCell():getZombieList(); for i = 0, a:size()-1 do local z = a:get(i); if not known[z] then "
                "z:setUseless(true); z:setHealth(2.0); table.insert(_pzArc, z) end end; return #_pzArc")['results'][0]
            zombie_made = True
            assert str(arc).split('.')[0] == '3', f'expected three arc zombies, got {arc}'
            time.sleep(1.5)
            capped = p.state()['minecraftGui']['combat'].get('swingCapped', 0)
            p.send_input(['ATTACK'], 200, wait=True)
            time.sleep(1.8)
            healths = [float(v) for v in p.lua("local out = {}; for _, z in ipairs(_pzArc) do table.insert(out, string.format('%.3f', z:isDead() and 0 or z:getHealth())) end; return table.concat(out, ',')")['results'][0].split(',')]
            return sorted((2.0 - h for h in healths), reverse=True), p.state()['minecraftGui']['combat'].get('swingCapped', 0) - capped

        # W3: a crowbar (PZ maxHitCount 3): the targets take damage, damage / 2, damage / 3
        crowbar = items['Base.Crowbar']['weapon']
        first = (crowbar['minDamage'] + crowbar['maxDamage']) / 2 * 2
        hold('Base.Crowbar')
        losses, capped = arc_swing()
        want = [first, first / 2, first / 3]
        result['W3'] = {'weapon': 'Base.Crowbar', 'maxHitCount': crowbar['maxHitCount'], 'losses': losses, 'expected': want, 'capped': capped}
        assert all(abs(l - w) < 0.08 * w + 0.05 for l, w in zip(losses, want)), f'crowbar losses {losses}, PZ would take {want}'
        assert capped == 0, f'{capped} hits were cancelled although the crowbar may hit 3 targets'

        # W4: a hammer (PZ maxHitCount 1) never hurts more than one of the three
        hold('Base.Hammer')
        losses, capped = arc_swing()
        result['W4'] = {'weapon': 'Base.Hammer', 'maxHitCount': hammer['maxHitCount'], 'losses': losses, 'capped': capped}
        assert sum(1 for l in losses if l > 0.05) == 1, f'the hammer hurt {sum(1 for l in losses if l > 0.05)} of 3 zombies (losses {losses})'
        assert abs(losses[0] - expected_loss) < 0.08 * expected_loss + 0.05, f'the hammer took {losses[0]:.2f}, PZ would take {expected_loss:.2f}'
        result['passed'] = True
    finally:
        try:
            p.send_input(release=True)
        except Exception:
            pass
        if given_in is not None:
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
        if zombie_made:
            p.lua("if _pzWeaponTarget then _pzWeaponTarget:removeFromWorld(); _pzWeaponTarget:removeFromSquare(); _pzWeaponTarget = nil end; "
                  "if _pzArc then for _, z in ipairs(_pzArc) do z:removeFromWorld(); z:removeFromSquare() end; _pzArc = nil end; "
                  "if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        else:
            p.lua("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        if moved:
            try:
                p.cmd(f'mc tp @s {origin["x"]} 0.02 {origin["z"]}')
                p.send_input(look={'yaw': origin['yaw'], 'pitch': origin['pitch'], 'relative': False}, wait=True)
            except Exception as e:
                result['cleanupError'] = f'teleport back: {e}'
        time.sleep(1.0)
        try:
            p.restore_hand(previous_hand)
            p.wait_for(lambda: in_step() or None, 30, 0.5, 'the mirror to settle at the end')
        except Exception as e:
            result['cleanupError'] = str(e)
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        stamp = time.strftime('%Y%m%d-%H%M%S')
        result['finished'] = stamp
        text = json.dumps(result, indent=2)
        (OUT / 'weapons-results.json').write_text(text)
        (OUT / f'weapons-results-{stamp}.json').write_text(text)
    print(json.dumps(result, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    run()
