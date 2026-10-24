"""Look at the swings: for each weapon, Steve swings once at open ground and the screen is captured as fast as the control API
allows, into tools/pz-mcp/swing-frames-<stamp>/<weapon>-<n>.png (private: PZ + Minecraft pictures, never commit or upload).

  python swing-frames.py [Base.Katana Base.Axe ...]

Steve is taken to open ground at least 12 tiles from his spot and faces east; before a swing Minecraft's own crosshair line must
be MISS (an attack on a block would mine it: the owner builds near spawn). Uses the camera the owner has (F5 is a desktop key
and is not pressed here). Creates and removes only its own items, freezes zombies for the duration, restores position, look,
hand and pause. Needs Steve driving with the mirror in step and a free hotbar slot.
"""
import sys
import time
from pathlib import Path
import pzcore as p

OUT = Path(__file__).parent
DEFAULT = ['Base.Katana', 'Base.Axe', 'Base.Hammer', 'Base.BaseballBat', 'Base.Sledgehammer', 'Base.Machete']

OUTDOORS = """local p=getPlayer(); local cell=getCell(); local px,py=math.floor(p:getX()),math.floor(p:getY())
local function clear(x,y)
 for dx=-2,8 do for dy=-2,4 do local sq=cell:getGridSquare(x+dx,y+dy,0)
  if not sq or not sq:isFree(false) or sq:getObjects():size()>1 then return false end end end return true end
for r=12,40 do for dx=-r,r do for dy=-r,r do
 if math.max(math.abs(dx),math.abs(dy))==r and clear(px+dx,py+dy) then return (px+dx+0.5)..","..(py+dy+0.5) end
end end end return "none" """


def gui():
    return p.state()['minecraftGui']


def in_step():
    m = p.state()['pzMirror']
    return m['active'] and m['status'] == 'in step' and m['pending'] == 0


def crosshair_since(t0):
    log = Path(p.LOGS['minecraft'])
    deadline = time.time() + 7
    while time.time() < deadline:
        time.sleep(0.5)
        for line in reversed(log.read_text(encoding='utf-8', errors='replace').splitlines()[-40:]):
            if 'crosshair:' in line and line.split(']')[0] > time.strftime('[%H:%M:%S', time.localtime(t0 - 1)):
                return line.split('crosshair: ')[1].split(' at ')[0]
    return None


def run(weapons):
    stamp = time.strftime('%Y%m%d-%H%M%S')
    out = OUT / f'swing-frames-{stamp}'
    out.mkdir()
    s0 = p.state()
    assert s0['gameState'] == 'IngameState' and s0['link']['ready'], 'game must be in the world with Minecraft linked'
    origin = s0['steve']
    previous_hand = gui().get('selectedSlot')
    slot = next((i for i in range(9) if gui()['inventory'][i].startswith('0 ')), None)
    assert slot is not None, 'no free hotbar slot'
    option = p.lua('return tostring(getCore():getOptionPauseOnFocusloss())')['results'][0]
    moved = held = False
    try:
        p.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        p.lua('_pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); '
              'table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
        p.wait_for(lambda: in_step() or None, 40, 0.5, 'the mirror to be in step')
        spot = p.lua(OUTDOORS)['results'][0]
        assert spot != 'none', 'no open ground near Steve'
        sx, sz = (float(v) for v in spot.split(','))
        p.cmd(f'mc tp @s {sx} 0.02 {sz}')
        moved = True
        p.wait_for(lambda: (abs(p.state()['player']['x'] - sx) < 0.2 and abs(p.state()['player']['y'] - sz) < 0.2) or None, 20, 0.3, 'PZ to follow')
        for wid in weapons:
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
            held = False
            p.wait_for(lambda: (gui()['inventory'][slot].startswith('0 ') and in_step()) or None, 30, 0.5, 'the slot to empty')
            inv = list(gui()['inventory'])
            p.cmd(f'mc pzgive {wid} 1')
            p.wait_for(lambda: (list(gui()['inventory']) != inv) or None, 8, 0.3, wid)
            at = next(i for i in range(36) if gui()['inventory'][i] != inv[i])
            assert inv[at].startswith('0 '), f'{wid} landed on "{inv[at]}"'
            if at != slot:
                p.cmd(f'mc item replace entity @s hotbar.{slot} from entity @s container.{at}')
                p.cmd(f'mc item replace entity @s container.{at} with minecraft:air')
            held = True
            time.sleep(0.4)
            p._select_slot(slot)
            p.send_input(release=True)
            p.send_input(look={'yaw': -90, 'pitch': 0, 'relative': False}, wait=True)
            time.sleep(2.0)       # the cooldown recharges and the pose settles
            seen = crosshair_since(time.time())
            if seen != 'MISS':
                print(f'{wid}: skipped, the crosshair is on {seen}', flush=True)
                continue
            preset = gui()['held'].get('preset', '')
            frames = []
            t0 = time.time()
            p.send_input(['ATTACK'], 150, wait=False)
            while time.time() - t0 < 1.6:
                frames.append((time.time() - t0, p.screenshot(0.5)))
            for n, (dt, png) in enumerate(frames):
                (out / f'{wid.replace("Base.", "")}-{n:02d}-{int(dt * 1000):04d}ms.png').write_bytes(png)
            print(f'{wid}: {preset}, {len(frames)} frames in {frames[-1][0]:.1f} s', flush=True)
            time.sleep(1.0)
    finally:
        try:
            p.send_input(release=True)
        except Exception:
            pass
        if held:
            p.cmd(f'mc item replace entity @s hotbar.{slot} with minecraft:air')
        p.lua("if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end")
        if moved:
            try:
                p.cmd(f'mc tp @s {origin["x"]} 0.02 {origin["z"]}')
                p.send_input(look={'yaw': origin['yaw'], 'pitch': origin['pitch'], 'relative': False}, wait=True)
            except Exception as e:
                print('could not return Steve:', e)
        time.sleep(1.0)
        try:
            p.restore_hand(previous_hand)
            p.wait_for(lambda: in_step() or None, 30, 0.5, 'the mirror to settle at the end')
        except Exception as e:
            print('cleanup:', e)
        p.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0); getCore():setOptionPauseOnFocusloss(' + option + ')')
    print(out)


if __name__ == '__main__':
    run(sys.argv[1:] or DEFAULT)
