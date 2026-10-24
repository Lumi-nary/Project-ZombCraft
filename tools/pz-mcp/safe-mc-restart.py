"""Minecraft-only restart with the zombies made harmless first: python safe-mc-restart.py
The restart helper (mc-restart.py) unpauses PZ for a few seconds while Minecraft starts; zombies standing next to Steve once took 6 HP in
that window. This moves every zombie within 30 tiles of Steve out to the distance of far zombies, freezes all zombies on every tick
(Events.OnTick) for the whole restart, runs mc-restart.py, then releases the zombies (their own flags restored) and pauses again."""
import runpy, sys, time
from pathlib import Path
import pzcore as p
HERE = Path(__file__).parent
def lua(t):
    v = p.lua(t)['results']; return v[0] if v else None
base = p.state()
hp0 = base['health']['steveHealth']
print('disperse:', lua('local px,py=getPlayer():getX(),getPlayer():getY();local a=getCell():getZombieList();local near,far={},{};for i=0,a:size()-1 do local z=a:get(i);local d=math.sqrt((z:getX()-px)^2+(z:getY()-py)^2);if d<30 then near[#near+1]=z elseif d>45 and d<130 and not z:isDead() then far[#far+1]=z end end;if #far<5 then return "few far zombies" end;for _,z in ipairs(near) do local f=far[ZombRand(#far)+1];local nx=f:getX()+ZombRand(-3,4);local ny=f:getY()+ZombRand(-3,4);z:setX(nx);z:setY(ny);z:setLastX(nx);z:setLastY(ny) end;return #near.." zombies moved out of 30 tiles"'))
lua('_pzSrHeld={};_pzSrSeen={};function _pzSrFreeze() local a=getCell():getZombieList();for i=0,a:size()-1 do local z=a:get(i);if not _pzSrSeen[z] then _pzSrSeen[z]=true;table.insert(_pzSrHeld,{z,z:isUseless()}) end;z:setUseless(true) end end;_pzSrFreeze();Events.OnTick.Add(_pzSrFreeze);return 1')
try:
    runpy.run_path(str(HERE / 'mc-restart.py'), run_name='__main__')
finally:
    lua('Events.OnTick.Remove(_pzSrFreeze);if _pzSrHeld then for _,v in ipairs(_pzSrHeld) do v[1]:setUseless(v[2]) end;_pzSrHeld=nil end;UIManager.getSpeedControls():SetCurrentGameSpeed(0);return 1')
s = p.state()
print('after: paused', s['paused'], 'hp', s['health']['steveHealth'], '(before', hp0, ')')
