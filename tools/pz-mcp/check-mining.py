"""A short mining check with nearby zombie AI temporarily suspended and restored."""
import json
import time
from pathlib import Path
import pzcore as pz

OUT=Path(__file__).parent
pause=pz.lua('return getCore():getOptionPauseOnFocusloss()')['results'][0]
try:
    pz.lua('getCore():setOptionPauseOnFocusloss(false); UIManager.getSpeedControls():SetCurrentGameSpeed(1); _pzHeldZeds={}; local a=getCell():getZombieList(); for i=0,a:size()-1 do local z=a:get(i); table.insert(_pzHeldZeds,{z,z:isUseless()}); z:setUseless(true) end')
    pz.cmd('heal')
    s=pz.state()['player']; pz.cmd(f'tp {10770-s["x"]} {9990-s["y"]}')
    time.sleep(2)
    pz.cmd('block 10770 0 9992 minecraft:oak_planks'); pz.cmd('block 10769 0 9992 minecraft:oak_planks')
    time.sleep(.5)
    pz.send_input(look={'yaw':0,'pitch':20,'relative':False},hotbar=2,wait=True)
    time.sleep(.3)
    before=pz.state()
    pz.send_input(['ATTACK'],3500,wait=True); time.sleep(.5)
    after=pz.state()
    (OUT/'mining-results.json').write_text(json.dumps({'before':before,'after':after},indent=2))
    print(json.dumps({'beforeBlocks':before['blocks'],'afterBlocks':after['blocks'], 'health':after['health'],'nativeBlocks':after['nativeBlocks']}),flush=True)
    (OUT/'native-mined.png').write_bytes(pz.screenshot(.7))
    assert after['blocks'] < before['blocks'], 'block not mined'
finally:
    pz.lua('UIManager.getSpeedControls():SetCurrentGameSpeed(1)')
    pz.cmd('block 10770 0 9992 minecraft:air'); pz.cmd('block 10769 0 9992 minecraft:air')
    time.sleep(.6)
    pz.lua('if _pzHeldZeds then for _,v in ipairs(_pzHeldZeds) do v[1]:setUseless(v[2]) end; _pzHeldZeds=nil end; UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    pz.lua('getCore():setOptionPauseOnFocusloss('+str(pause).lower()+')')
