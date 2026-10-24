"""0.13 ground under PZ. Stage 'dig': Steve digs through a PZ grass tile with a pickaxe, PZ's floor goes, the layers below
show, and refilling brings the same floor back. Stage 'crater': TNT leaves a crater in the ground and takes the PZ floors
over it with it.

Only use the backed-up PzCraftFeatureTest-20261002 save. Control API only (no desktop focus). The dug ground is refilled.
"""
import argparse
import json
import re
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent

FIND_GRASS = """
local px,py=math.floor(getPlayer():getX()),math.floor(getPlayer():getY())
local best,bd=nil,1e9
for dx=-14,14 do for dy=-14,14 do
  local x,y=px+dx,py+dy
  local sq=getCell():getGridSquare(x,y,0)
  if sq and sq:getObjects():size()==1 then
    local f=sq:getFloor()
    if f and f:getProperties():get('FootstepMaterial')=='Grass' then
      local clear=true
      for ax=-3,3 do for ay=-3,3 do
        local n=getCell():getGridSquare(x+ax,y+ay,0)
        if not n or n:getObjects():size()~=1 or n:HasTree() or not n:getFloor() then clear=false end
      end end
      local d=dx*dx+dy*dy
      if clear and d<bd and d>16 then best={x,y}; bd=d end
    end
  end
end end
if best then return best[1]..','..best[2] end
return 'none'
"""


def block(state_string):
    m = re.search(r'Block\{([^}]+)\}', state_string or '')
    return m.group(1).replace('minecraft:', '') if m else str(state_string)


def probe_all(names_to_pos):
    p.cmd('mc pzcraft_probe_clear')
    for name, (x, y, z) in names_to_pos.items():
        p.cmd(f'mc pzcraft_probe {name} {x} {y} {z}')
    time.sleep(0.6)
    probes = p.state()['minecraftGui']['server']['probes']
    return {k: block(v) for k, v in probes.items()}


def pick_vantage(x, y):
    """An open spot 8-10 blocks from the tile with a clear line of sight to it (no Minecraft block or PZ obstacle on the
    first three cells toward the tile), and the yaw that faces the tile."""
    import math
    candidates = [(8, 8), (-8, 8), (8, -8), (-8, -8), (10, 0), (-10, 0), (0, 10), (0, -10), (9, 4), (-9, 4), (9, -4), (-9, -4)]
    def line(dx, dz):
        sx = (dx > 0) - (dx < 0)
        sz = (dz > 0) - (dz < 0)
        return [(x + dx - sx * k, y + dz - sz * k) for k in range(0, 4)]
    names = {}
    for i, (dx, dz) in enumerate(candidates):
        for k, (cx, cz) in enumerate(line(dx, dz)):
            for h in (0, 1, 2):
                names[f'v{i}_{k}_{h}'] = (cx, h, cz)
    blocks = probe_all(names)
    for i, (dx, dz) in enumerate(candidates):
        cells = line(dx, dz)
        if any(blocks.get(f'v{i}_{k}_{h}') != 'air' for k in range(len(cells)) for h in (0, 1, 2)):
            continue
        ok = True
        for cx, cz in cells:
            free = p.lua(f"local s=getCell():getGridSquare({cx},{cz},0); return tostring(s ~= nil and s:isFree(false) and not s:HasTree())")['results'][0]
            ok = ok and free == 'true'
        if not ok:
            continue
        yaw = math.degrees(math.atan2(dx, -dz))      # forward = (-sin yaw, cos yaw): from the vantage point back to the tile
        return x + dx + .5, y + dz + .5, yaw
    return x + 8.5, y + 8.5, 135.0


def floor_name(x, y):
    r = p.lua(f"local s=getCell():getGridSquare({x},{y},0); local f=s and s:getFloor(); return f and f:getSprite():getName() or 'NONE'")
    return r['results'][0]


def setup():
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    tile = p.lua(FIND_GRASS)['results'][0]
    assert tile != 'none', 'no clear lawn tile near Steve'
    x, y = (int(v) for v in tile.split(','))
    return s0, x, y


def dig(x, y):
    result = {'stage': 'dig', 'tile': [x, y], 'passed': False}
    original = floor_name(x, y)
    result['floorBefore'] = original
    p.cmd(f'mc tp @s {x + .5} 0.01 {y + 3.5}')
    time.sleep(2.5)
    p.send_input(release=True)
    p.send_input(look={'yaw': 180, 'pitch': 28, 'relative': False}, hotbar=2, wait=True)   # slot 2: the iron pickaxe
    time.sleep(1.0)
    column = {f'd{d}': (x, -d, y) for d in range(1, 6)}
    result['before'] = probe_all(column)
    base = p.state()
    removed0 = base['pzGround']['floorsRemoved']
    (OUT / 'ground-dig-before.png').write_bytes(p.screenshot(0.7))
    p.send_input(['ATTACK'], 1200, wait=True)
    time.sleep(2.0)
    after = p.state()
    result['after'] = probe_all(column)
    result['floorAfterDig'] = floor_name(x, y)
    result['floorsRemoved'] = after['pzGround']['floorsRemoved'] - removed0
    result['ground'] = after['minecraftGui']['ground']
    (OUT / 'ground-dig-after.png').write_bytes(p.screenshot(0.7))
    # Refill: PZ must bring the same floor back.
    restored0 = after['pzGround']['floorsRestored']
    p.cmd(f'mc setblock {x} -1 {y} minecraft:dirt')
    time.sleep(2.0)
    result['floorAfterRefill'] = floor_name(x, y)
    result['floorsRestored'] = p.state()['pzGround']['floorsRestored'] - restored0
    p.cmd(f'mc setblock {x} -1 {y} minecraft:grass_block')   # put the grass top back
    time.sleep(0.5)
    result['passed'] = (result['before']['d1'] == 'grass_block' and result['before']['d2'] == 'dirt' and result['before']['d3'] == 'dirt'
                        and result['after']['d1'] == 'air' and result['after']['d2'] == 'dirt'
                        and result['floorAfterDig'] == 'NONE' and result['floorsRemoved'] == 1
                        and result['floorAfterRefill'] == original and result['floorsRestored'] == 1)
    return result


def crater(x, y):
    result = {'stage': 'crater', 'tile': [x, y], 'passed': False}
    tiles = [(x + dx, y + dy) for dx in range(-4, 5) for dy in range(-4, 5)]
    floors_before = {t: floor_name(*t) for t in tiles}
    names = {f'top_{dx}_{dy}': (x + dx, -1, y + dy) for dx in (-3, 0, 3) for dy in (-3, 0, 3)}
    base = p.state()
    blasts0 = base['minecraftGui']['explosions']['blasts']
    removed0 = base['pzGround']['floorsRemoved']
    vx, vz, yaw = pick_vantage(x, y)
    result['vantage'] = [vx, vz, round(yaw, 1)]
    p.cmd(f'mc tp @s {vx} 0.01 {vz}')
    time.sleep(2.5)
    p.send_input(release=True)
    p.send_input(look={'yaw': yaw, 'pitch': 22, 'relative': False}, hotbar=5, wait=True)
    (OUT / 'ground-crater-before.png').write_bytes(p.screenshot(0.7))
    p.cmd(f'mc summon minecraft:tnt {x + .5} 0.0 {y + .5} {{fuse:20}}')
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and p.state()['minecraftGui']['explosions']['blasts'] == blasts0:
        time.sleep(0.2)
    time.sleep(4.0)
    after = p.state()
    result['tops'] = probe_all(names)
    gone = [t for t in tiles if floor_name(*t) == 'NONE' and floors_before[t] != 'NONE']
    result['floorsGone'] = len(gone)
    result['floorsRemoved'] = after['pzGround']['floorsRemoved'] - removed0
    result['ground'] = after['minecraftGui']['ground']
    result['explosion'] = after['minecraftGui']['explosions']['last']
    (OUT / 'ground-crater-after.png').write_bytes(p.screenshot(0.7))
    # Refill the top layer (and let PZ restore the floors it took).
    restored0 = after['pzGround']['floorsRestored']
    p.cmd(f'mc fill {x - 6} -1 {y - 6} {x + 6} -1 {y + 6} minecraft:grass_block replace minecraft:air')
    time.sleep(3.0)
    still_gone = [t for t in gone if floor_name(*t) == 'NONE']
    result['floorsStillGone'] = len(still_gone)
    result['floorsRestored'] = p.state()['pzGround']['floorsRestored'] - restored0
    result['passed'] = (result['floorsGone'] >= 5 and result['tops']['top_0_0'] == 'air'
                        and result['ground']['explosionBlocks'] > 0 and result['floorsStillGone'] == 0)
    return result


def run(stage):
    s0, x, y = setup()
    origin = s0['steve']
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    result = {'passed': False, 'stage': stage}
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
              'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
        result = dig(x, y) if stage == 'dig' else crater(x, y)
    finally:
        p.send_input(release=True)
        p.cmd('mc pzcraft_probe_clear')
        p.cmd(f'mc tp @s {origin["x"]} 0.01 {origin["z"]}')
        time.sleep(1.0)
        p.lua('if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        (OUT / f'ground-{stage}-results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['dig', 'crater'])
    run(parser.parse_args().stage)
