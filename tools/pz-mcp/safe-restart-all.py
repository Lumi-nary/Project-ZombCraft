"""Full restart (restart-all.py) with the zombies moved away first: python safe-restart-all.py LABEL [restart-all options]

restart-all.py stops both games, rebuilds, approves, relaunches and pauses; the world runs for some seconds while PZ comes up, and
a freeze hook cannot survive a PZ restart. So every zombie within 30 tiles of Steve is first moved out to where far zombies stand
(their new places are saved with the world). Prints restart-all's summary, then Steve's health before and after and how many
zombies are near; exits 1 if restart-all failed or Steve lost health."""
import subprocess
import sys
from pathlib import Path

import pzcore as p

HERE = Path(__file__).parent


def lua(t):
    v = p.lua(t)['results']
    return v[0] if v else None


NEAR = 'local px,py=getPlayer():getX(),getPlayer():getY();local a=getCell():getZombieList();local n12,n30=0,0;for i=0,a:size()-1 do local z=a:get(i);local d=math.sqrt((z:getX()-px)^2+(z:getY()-py)^2);if d<12 then n12=n12+1 end;if d<30 then n30=n30+1 end end;return n12.." within 12, "..n30.." within 30, "..a:size().." in all"'
base = p.state()
assert base['paused'], 'the world is running: is the owner playing?'
hp0 = base['health']['steveHealth']
print('disperse:', lua('local px,py=getPlayer():getX(),getPlayer():getY();local a=getCell():getZombieList();local near,far={},{};for i=0,a:size()-1 do local z=a:get(i);local d=math.sqrt((z:getX()-px)^2+(z:getY()-py)^2);if d<30 then near[#near+1]=z elseif d>45 and d<130 and not z:isDead() then far[#far+1]=z end end;if #far<5 then return "few far zombies" end;for _,z in ipairs(near) do local f=far[ZombRand(#far)+1];local nx=f:getX()+ZombRand(-3,4);local ny=f:getY()+ZombRand(-3,4);z:setX(nx);z:setY(ny);z:setLastX(nx);z:setLastY(ny) end;return #near.." zombies moved out of 30 tiles"'))
print('zombies before:', lua(NEAR))
r = subprocess.run([sys.executable, str(HERE / 'restart-all.py')] + sys.argv[1:], cwd=HERE)
try:
    lua('UIManager.getSpeedControls():SetCurrentGameSpeed(0)')
    s = p.state()
    hp1 = s['health']['steveHealth']
    print('after: paused', s['paused'], '| hp', hp0, '->', hp1, '| zombies', lua(NEAR))
    sys.exit(1 if r.returncode or hp1 < hp0 - 0.01 else 0)
except Exception as e:
    print('state after restart unavailable:', repr(e))
    sys.exit(1)
