"""Create then remove controlled fixtures in Claude's development save. No external model calls."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT = Path(__file__).parent
results = {}
def sample(name):
    s = pz.state(); results[name] = s
    (OUT / 'depth-vehicle-results.json').write_text(json.dumps(results, indent=2))
    print(name, json.dumps({k: s.get(k) for k in ('player','steve','vehicles','health','nativeBlocks')}), flush=True)
    return s
def lua(code):
    r = pz.lua(code)
    if not r.get('ok'): raise RuntimeError(r)
    print('lua', r, flush=True)
    return r
def shot(name): (OUT / (name + '.png')).write_bytes(pz.screenshot(.7))
def teleport(x,z):
    s = pz.state()['player']
    pz.cmd(f'tp {x-s["x"]} {z-s["y"]}')
    pz.wait_for(lambda: pz.state()['link']['ready'], 20, .2, 'placement')
    time.sleep(.6)

if __name__ == '__main__':
    focus_pause = lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]
    lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    pz.cmd('heal')
    try:
        pz.cmd('block 10744 1 9995 minecraft:gold_block')
        teleport(10744.5,9991.5)
        pz.send_input(look={'yaw':0,'pitch':0,'relative':False}, wait=True)
        time.sleep(1)
        shot('native-behind-wall'); sample('depth_native')
        pz.cmd('native off'); time.sleep(.8); shot('video-behind-wall')
        pz.cmd('native on'); time.sleep(.8)
        # Approach from the other side so the same fixture is visible through an open window/door.
        teleport(10740.5,9995.5)
        pz.send_input(look={'yaw':-90,'pitch':0,'relative':False}, wait=True)
        time.sleep(.5); shot('native-room-side')
        pz.cmd('block 10744 1 9995 minecraft:air'); time.sleep(.5)
        teleport(10755.5,9988.5)
        lua('_pzCraftTestCar=addVehicle("Base.CarNormal",10755,9993,0); return tostring(_pzCraftTestCar)')
        time.sleep(3)
        lua('return _pzCraftTestCar:getX()..",".._pzCraftTestCar:getY()..",".._pzCraftTestCar:getZ()')
        pz.send_input(look={'yaw':0,'pitch':10,'relative':False}, wait=True)
        sample('car_before')
        pz.send_input(['FWD'],1600,wait=True); time.sleep(.3)
        sample('car_blocked'); shot('native-car-collision')
        lua('_pzCraftTestCar:permanentlyRemove(); _pzCraftTestCar=nil')
        time.sleep(.3)
        sample('car_removed')
        pz.send_input(['FWD'],1200,wait=True); time.sleep(.2)
        sample('car_passed')
    finally:
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
        pz.cmd('block 10744 1 9995 minecraft:air')
        lua('if _pzCraftTestCar then _pzCraftTestCar:permanentlyRemove(); _pzCraftTestCar=nil end')
        pz.cmd('native on'); pz.cmd('heal')
        time.sleep(.6)
        lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
        lua('getCore():setOptionPauseOnFocusloss(' + str(focus_pause).lower() + ')')
