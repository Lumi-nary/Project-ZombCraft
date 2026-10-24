"""0.13 TNT destroys PZ objects. Spawns real PZ fixtures on a lawn, detonates one primed TNT among them and checks which
survive: objects inside the blast are smashed or removed, the far control and the wall shielded by a brick wall stay.

Only use the backed-up PzCraftFeatureTest-20261002 save. Control API only (no desktop focus). Fixtures that survive are
removed again. Steve stands 12 blocks away diagonally (the blast reaches 2 x radius = 8).
"""
import json
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent

# (role, kind, sprite, dx, dz, north) relative to the TNT's tile; the TNT sits at the centre of tile (0, 0).
FIXTURES = [
    ('brick_near',   'wall',   'walls_exterior_house_01_1',   0, -2, True),   # 2.5 blocks north: destroyed
    ('wood_shielded', 'wall',  'walls_exterior_house_02_33',  0, -5, True),   # 5.5 north, behind the brick wall: shielded
    ('window',       'window', 'fixtures_windows_01_0',       2,  0, False),  # 1.5 east: smashed
    ('door',         'door',   'fixtures_doors_01_0',         2,  1, False),  # 1.5-2 east: destroyed
    ('crate',        'solid',  'furniture_storage_01_9',      0,  2, None),   # 2 south: destroyed
    ('fence',        'wall',   'fencing_01_35',              -2,  0, False),  # 2.5 west: destroyed
    ('wood_far',     'wall',   'walls_exterior_house_02_32', -7,  0, False),  # 7.5 west, outside the blast: intact
]

SETUP = """
local cell=getCell(); _pzTnt={}
local function sqAt(x,y) return cell:getGridSquare(x,y,0) end
local function addWall(role,x,y,sprite)
  local sq=sqAt(x,y); local o=IsoObject.new(sq,sprite,'PzCraftTnt_'..role); sq:AddTileObject(o); sq:RecalcAllWithNeighbours(true); _pzTnt[role]={o,sq}
end
local function addDoor(role,x,y,sprite,north)
  local sq=sqAt(x,y); local o=IsoDoor.new(cell,sq,sprite,north); sq:AddSpecialObject(o); sq:RecalcAllWithNeighbours(true); _pzTnt[role]={o,sq}
end
local function addWindow(role,x,y,sprite,north)
  local sq=sqAt(x,y); local o=IsoWindow.new(cell,sq,IsoSpriteManager.instance:getSprite(sprite),north); sq:AddSpecialObject(o); sq:RecalcAllWithNeighbours(true); _pzTnt[role]={o,sq}
end
"""

CHECK = """
local out={}
for role,v in pairs(_pzTnt) do
  local o,sq=v[1],v[2]
  local present=sq:getObjects():contains(o)
  local state=present and 'present' or 'gone'
  if present and instanceof(o,'IsoWindow') then state=o:isDestroyed() and 'smashed' or 'intact' end
  out[#out+1]=role..'='..state
end
table.sort(out); return table.concat(out,',')
"""

CLEAN = """
local n=0
if _pzTnt then
  for role,v in pairs(_pzTnt) do
    local o,sq=v[1],v[2]
    if sq:getObjects():contains(o) then sq:transmitRemoveItemFromSquare(o); n=n+1 end
    sq:RecalcAllWithNeighbours(true)
  end
  _pzTnt=nil
end
return 'removed '..n..' leftover fixtures'
"""


def lua(code):
    r = p.lua(code)
    assert r.get('ok'), r
    return r['results'][0] if r.get('results') else None


def run():
    result = {'passed': False}
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    origin = s0['steve']
    # The lawn south of the white house, where the tree check ran: TNT tile (2158, 6108).
    tx, tz = 2158, 6108
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
              'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
        # A diagonal vantage point 12 blocks away (the blast reaches 8), clear of the lawn's bushes.
        p.cmd(f'mc tp @s {tx + 8.5} 0.01 {tz + 8.5}')
        time.sleep(1.5)
        p.send_input(release=True)
        p.send_input(look={'yaw': 135, 'pitch': 7, 'relative': False}, hotbar=5, wait=True)
        code = [SETUP]
        for role, kind, sprite, dx, dz, north in FIXTURES:
            x, z = tx + dx, tz + dz
            if kind == 'wall' or kind == 'solid':
                code.append(f"addWall('{role}',{x},{z},'{sprite}')")
            elif kind == 'door':
                code.append(f"addDoor('{role}',{x},{z},'{sprite}',{str(bool(north)).lower()})")
            else:
                code.append(f"addWindow('{role}',{x},{z},'{sprite}',{str(bool(north)).lower()})")
        code.append("return 'fixtures spawned'")
        lua('\n'.join(code))
        time.sleep(3.5)                       # let PZ send its updated materials to Minecraft
        result['before'] = lua(CHECK + "")
        before_state = p.state()
        (OUT / 'tnt-before.png').write_bytes(p.screenshot(0.7))
        base_blasts = before_state['minecraftGui']['explosions']['blasts']
        p.cmd(f'mc summon minecraft:tnt {tx + .5} 0.0 {tz + .5} {{fuse:20}}')
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and p.state()['minecraftGui']['explosions']['blasts'] == base_blasts:
            time.sleep(0.2)
        time.sleep(2.5)                       # PZ applies a few targets per frame
        after = p.state()
        result['after'] = lua(CHECK)
        result['minecraft'] = after['minecraftGui']['explosions']
        result['pz'] = after['pzExplosions']
        (OUT / 'tnt-after.png').write_bytes(p.screenshot(0.7))
        outcome = dict(item.split('=') for item in result['after'].split(','))
        expect = {'brick_near': 'gone', 'window': 'smashed', 'door': 'gone', 'crate': 'gone', 'fence': 'gone',
                  'wood_shielded': 'present', 'wood_far': 'present'}
        result['expected'] = expect
        result['mismatches'] = {k: [outcome.get(k), v] for k, v in expect.items() if outcome.get(k) != v}
        result['passed'] = (not result['mismatches'] and result['minecraft']['blasts'] > base_blasts
                            and after['pzExplosions']['failures'] == 0)
    finally:
        p.send_input(release=True)
        result['cleanup'] = lua(CLEAN)
        p.cmd(f'mc tp @s {origin["x"]} 0.01 {origin["z"]}')
        time.sleep(1.0)
        p.lua('if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end')
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
        (OUT / 'tnt-results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2), flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    run()
